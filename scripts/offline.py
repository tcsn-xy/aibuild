#!/usr/bin/env python3
"""Start/stop a single authoritative local world; clients join instead of opening its save."""
import argparse
from contextlib import contextmanager
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import urllib.request
import zipfile
import uuid

from bridge import DEFAULT, atomic, status
from projection import read

ROOT = Path(__file__).resolve().parents[1]


def worlds(instance):
    out = []
    for folder in sorted((instance / 'saves').iterdir()):
        if folder.is_symlink() or not (folder / 'level.dat').is_file():
            continue
        try:
            data = read(folder / 'level.dat')['Data']
            out.append({'id': folder.name, 'name': data['LevelName'],
                        'version': data['Version']['Name'], 'path': str(folder.resolve())})
        except (ValueError, KeyError, OSError):
            out.append({'id': folder.name, 'error': '世界元数据无法读取'})
    return out


def select_world(instance, name):
    matches = [w for w in worlds(instance) if w['id'] == name or w.get('name') == name]
    if len(matches) != 1 or 'error' in matches[0]:
        raise ValueError('世界名未找到或不唯一，请先 worlds 列出目录ID')
    world = Path(matches[0]['path'])
    if world.parent != (instance / 'saves').resolve() or matches[0]['version'] != '26.3':
        raise ValueError('只加载实例内的26.3世界，不自动转换')
    return world


def alive(pid):
    try:
        os.kill(int(pid), 0)
        state=subprocess.run(['ps','-p',str(int(pid)),'-o','stat='],text=True,capture_output=True).stdout.strip()
        return bool(state) and not state.startswith('Z')
    except (ProcessLookupError, ValueError):
        return False


def owned(instance, descriptor):
    try:
        runtime=Path(descriptor['runtime']).resolve()
        if not runtime.is_relative_to((instance/'aibuild-background').resolve()) or not alive(descriptor['pid']):return False
        command=subprocess.run(['ps','-p',str(int(descriptor['pid'])),'-o','command='],text=True,capture_output=True).stdout
        return str(runtime/'launch.args') in command
    except (ValueError,KeyError,OSError):return False


@contextmanager
def world_lock(world):
    # Java's DirectoryLock uses a POSIX record lock, not flock. Never unlink session.lock.
    with (world / 'session.lock').open('r+b') as file:
        try:
            fcntl.lockf(file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise RuntimeError('世界正在运行，不能启动第二个写入者；先退出这个世界') from exc
        try:
            yield
        finally:
            fcntl.lockf(file, fcntl.LOCK_UN)


def sha(path, kind='sha256'):
    h = hashlib.new(kind)
    with path.open('rb') as file:
        for block in iter(lambda: file.read(1024*1024), b''):
            h.update(block)
    return h.hexdigest()


def server_classpath(instance, runtime):
    parent = json.loads((instance / '.parent/26.3.json').read_text())
    metadata = parent['downloads']['server']
    bundle = runtime.parent / 'server-26.3.jar'
    cache = Path.home() / '.gradle/caches/fabric-loom/26.3/minecraft-server.jar'
    if not bundle.exists() or sha(bundle, 'sha1') != metadata['sha1']:
        if cache.exists() and sha(cache, 'sha1') == metadata['sha1']:
            shutil.copy2(cache, bundle)
        else:
            for attempt in range(3):
                try:
                    with urllib.request.urlopen(metadata['url'], timeout=30) as response, bundle.with_suffix('.tmp').open('wb') as out:
                        shutil.copyfileobj(response, out)
                    os.replace(bundle.with_suffix('.tmp'), bundle)
                    if sha(bundle, 'sha1') != metadata['sha1']:
                        raise RuntimeError('官方服务端SHA1不符')
                    break
                except (OSError, RuntimeError):
                    if attempt == 2: raise
    libraries = runtime / 'libraries'
    libraries.mkdir(exist_ok=True)
    cp = []
    with zipfile.ZipFile(bundle) as jar:
        for listing, prefix in [('META-INF/versions.list', 'META-INF/versions/'),
                                ('META-INF/libraries.list', 'META-INF/libraries/')]:
            for line in jar.read(listing).decode().splitlines():
                expected, _, entry = line.split('\t')
                target = libraries / entry
                if not target.resolve().is_relative_to(libraries.resolve()):
                    raise ValueError('非法官方归档路径')
                target.parent.mkdir(parents=True, exist_ok=True)
                if not target.exists() or sha(target) != expected:
                    target.write_bytes(jar.read(prefix + entry))
                if sha(target) != expected: raise RuntimeError('服务器组件SHA256不符')
                cp.append(str(target))
    loader = json.loads((instance / '26.3-Fabric.json').read_text())
    library_root = Path(os.environ.get('AIBUILD_LIBRARY_ROOT',str(instance.parents[1] / 'libraries')))
    for library in loader['libraries']:
        group, name, version, *classifier = library['name'].split(':')
        entry = library.get('downloads', {}).get('artifact', {}).get('path')
        if not entry:
            entry = f'{group.replace(".", "/")}/{name}/{version}/{name}-{version}' + ''.join('-'+c for c in classifier) + '.jar'
        file = library_root / entry
        if not file.exists(): raise FileNotFoundError(file)
        cp.append(str(file))
    return cp


def launch(instance, name, dimension, artifact=None):
    root = instance / 'aibuild-background'
    root.mkdir(exist_ok=True)
    connection = root / 'connection.json'
    if connection.exists():
        old = json.loads(connection.read_text())
        if owned(instance,old):
            if Path(old['world']) == select_world(instance, name):
                return {'already_running': True, **old}
            raise RuntimeError('另一后台世界正在运行，请先 stop；不偷偷切世界')
    starting = root / 'process.json'
    if starting.exists() and owned(instance,json.loads(starting.read_text())):
        raise RuntimeError('后台正在启动，请查询 status，不重复启动')
    world = select_world(instance, name)
    artifact = Path(artifact) if artifact else next(iter((instance / 'mods').glob('ai-builder-1.6.0+mc26.3.jar')), None)
    if not artifact or not artifact.is_file(): raise RuntimeError('未安装1.6.0正式桥接')
    with zipfile.ZipFile(artifact) as jar:
        meta = json.loads(jar.read('fabric.mod.json'))
        if meta['version'] != '1.6.0+mc26.3' or meta['environment'] != '*': raise RuntimeError('产物不支持后台模式')
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    runtime = root / ('runtime-' + stamp)
    runtime.mkdir()
    (runtime / 'mods').mkdir()
    (runtime/'server.properties').write_text('server-ip=127.0.0.1\nonline-mode=false\nenforce-secure-profile=false\nmax-players=1\npause-when-empty-seconds=0\nenable-rcon=false\nenable-query=false\nallow-flight=true\n')
    for folder in ('config','data'):
        if (instance/folder).exists():shutil.copytree(instance / folder, runtime / folder, dirs_exist_ok=True)
    mod_hashes = {}
    for file in (instance / 'mods').glob('*.jar'):
        with zipfile.ZipFile(file) as jar:
            data = json.loads(jar.read('fabric.mod.json'), strict=False) if 'fabric.mod.json' in jar.namelist() else {}
        if data.get('id') == 'ai_builder': continue
        shutil.copy2(file, runtime / 'mods' / file.name)
        mod_hashes[file.name] = sha(file)
    shutil.copy2(artifact, runtime / 'mods' / artifact.name)
    qa_mod=os.environ.get('AIBUILD_BACKGROUND_QA_JAR')
    if qa_mod:
        if not (instance/'.aibuild-disposable-qa').exists(): raise RuntimeError('QA仅允许一次性实例')
        shutil.copy2(qa_mod,runtime/'mods'/Path(qa_mod).name)
    cp = server_classpath(instance, runtime)
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0)); port = reservation.getsockname()[1]
    backup = root / 'backups' / (world.name + '-' + stamp)
    with world_lock(world):
        if any(p.is_symlink() for p in world.rglob('*')):
            raise RuntimeError('世界内有链接文件，停止以避免修改范围外数据')
        shutil.copytree(world, backup)
        atomic(runtime / 'backup.json', {'world': str(world), 'backup': str(backup), 'mods': mod_hashes,
                                        'artifact_sha256': sha(artifact)})
    # If a client wins the lock during this narrow handoff, Minecraft refuses startup.
    configuration = runtime / 'background.json'
    atomic(configuration, {'world': str(world), 'dimension': dimension, 'port': port,
                           'runtime': str(runtime), 'bridge': str(instance / 'aibuild-bridge'),
                           'connection': str(connection)})
    java = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '25'], text=True).strip()) / 'bin/java'
    arguments = ['-Xmx4G', '-Djava.awt.headless=true', '-Daibuild.background.config='+str(configuration),
                 '-cp', os.pathsep.join(cp), 'net.fabricmc.loader.impl.launch.knot.KnotServer', 'nogui']
    argsfile = runtime / 'launch.args'
    argsfile.write_text('\n'.join(json.dumps(arg,ensure_ascii=False) for arg in arguments)+'\n')
    with (runtime / 'console.log').open('w') as log:
        process = subprocess.Popen([str(java), '@'+str(argsfile)], cwd=runtime,
                                   stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
    atomic(starting, {'pid': process.pid, 'runtime': str(runtime), 'world': str(world)})
    end = time.monotonic() + 180
    while time.monotonic() < end:
        if process.poll() is not None:
            raise RuntimeError('后台启动失败，目标世界有备份；日志：'+str(runtime / 'console.log'))
        if connection.exists():
            data = json.loads(connection.read_text())
            if data['pid'] == process.pid:
                return {**data, 'backup': str(backup), 'runtime': str(runtime)}
        time.sleep(.25)
    # Do not kill a potentially writing server. Keep process record for status/stop.
    raise RuntimeError('后台启动仍未完成；查询 status，不重复启动；日志：'+str(runtime / 'console.log'))


def backend_status(instance):
    root = instance / 'aibuild-background'
    process_file = root / 'process.json'
    if not process_file.exists(): return {'running': False}
    process = json.loads(process_file.read_text())
    out = {**process, 'running': owned(instance,process)}
    if out['running']:
        try: out['bridge'] = status(instance)
        except RuntimeError: pass
    return out


def stop(instance):
    data = backend_status(instance)
    if not data['running']: return data
    bridge = data.get('bridge', {})
    if bridge.get('players', 0): raise RuntimeError('玩家仍在后台世界，请先退出该世界；不会踢出玩家')
    stop_id=uuid.uuid4().hex
    result=Path(data['runtime'])/'stop-result.json'
    result.unlink(missing_ok=True)
    atomic(Path(data['runtime']) / 'stop.json', {'id':stop_id,'save_and_stop': True})
    end = time.monotonic()+120
    while time.monotonic()<end:
        if result.exists():
            response=json.loads(result.read_text())
            if response.get('id')==stop_id:raise RuntimeError(response['error'])
        if not alive(data['pid']):
            receipt=Path(data['runtime'])/'exit.json'
            if not receipt.exists() or not json.loads(receipt.read_text()).get('saved'): raise RuntimeError('后台已退出，但保存回执缺失；必须检查日志')
            return {'stopped': True, 'world': data['world'], 'saved': True}
        time.sleep(.25)
    raise RuntimeError('保存退出尚未完成，不强杀；请查看后台日志')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--instance',type=Path,default=DEFAULT)
    commands=parser.add_subparsers(dest='op',required=True)
    commands.add_parser('worlds');commands.add_parser('status');commands.add_parser('stop')
    start=commands.add_parser('start');start.add_argument('world');start.add_argument('--dimension',default='minecraft:overworld')
    start.add_argument('--artifact',type=Path,help=argparse.SUPPRESS)
    args=parser.parse_args();instance=args.instance.resolve()
    root=instance/'aibuild-background';root.mkdir(exist_ok=True)
    with (root/'.launcher.lock').open('a+b') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        if args.op=='worlds':result=worlds(instance)
        elif args.op=='start':result=launch(instance,args.world,args.dimension,args.artifact)
        elif args.op=='stop':result=stop(instance)
        else:result=backend_status(instance)
        print(json.dumps(result,ensure_ascii=False,indent=2))


if __name__=='__main__':
    try: main()
    except (OSError,ValueError,RuntimeError,KeyError) as error: raise SystemExit(str(error))
