#!/usr/bin/env python3
"""Durable task orchestration; all world operations use the existing file bridge."""
import argparse
from contextlib import contextmanager
import hashlib
import json
import math
from pathlib import Path
import time
import uuid

import bridge
import projection


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(',', ':')).encode()).hexdigest()


def triple(value, positive=False):
    if not isinstance(value, list) or len(value) != 3 or any(type(n) is not int for n in value):
        raise ValueError('坐标/尺寸必须为三个整数')
    if positive and any(n < 1 for n in value):
        raise ValueError('尺寸必须为正数')
    return value


def split_regions(regions):
    """Bound volume before generating tiles or requesting any world data."""
    tiles = []
    for region in regions:
        origin, size = triple(region['origin']), triple(region['size'], True)
        if math.prod(size) > 524288:
            raise ValueError('源区域体积超过524288；明确缩小范围')
        count = math.prod((n + 63) // 64 for n in size)
        if len(tiles) + count > 16:
            raise ValueError('源区域分区后超过16个64格区域')
        for y in range(0, size[1], 64):
            for z in range(0, size[2], 64):
                for x in range(0, size[0], 64):
                    shift = [x, y, z]
                    tiles.append({'origin': [origin[k] + shift[k] for k in range(3)],
                                  'size': [min(64, size[k] - shift[k]) for k in range(3)]})
    if not tiles or sum(math.prod(t['size']) for t in tiles) > 524288:
        raise ValueError('需提供源区域，合计体积最多524288格')
    return tiles


def contains(region, pos):
    return all(region['origin'][k] <= pos[k] < region['origin'][k] + region['size'][k]
               for k in range(3))


def move_spec(raw):
    offset = triple(raw['offset'])
    if not any(offset) or any(abs(n) > 512 for n in offset):
        raise ValueError('偏移必须非零，各轴最多512格')
    regions = split_regions(raw['regions'])
    excluded = {tuple(triple(p)) for p in raw.get('exclude', [])}
    if any(not any(contains(r, p) for r in regions) for p in excluded):
        raise ValueError('排除坐标必须在源区域内')
    cleanup, additions = raw.get('cleanup', []), raw.get('additions', [])
    for items, keys in [(cleanup, ('expected', 'restore')), (additions, ('expected', 'state'))]:
        seen = set()
        for item in items:
            pos = tuple(triple(item['pos']))
            if pos in seen or pos in excluded:
                raise ValueError('重复或已排除的清理/基础坐标')
            seen.add(pos)
            if any(not isinstance(item.get(k), str) or not item[k] for k in keys):
                raise ValueError('清理/基础必须明确指定预期状态及最终状态')
    if {tuple(b['pos']) for b in cleanup} & {tuple(b['pos']) for b in additions}:
        raise ValueError('清理与新增基础重叠，请合成一份明确最终状态')
    return {'offset': offset, 'regions': regions, 'exclude': sorted(map(list, excluded)),
            'cleanup': cleanup, 'additions': additions}


@contextmanager
def task_lock(root):
    """OS lock disappears on process exit; no stale lock guessing."""
    root.mkdir(parents=True, exist_ok=True)
    with (root / '.workflow.lock').open('a+b') as lock:
        try:
            import fcntl
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise RuntimeError('同一任务已有工具运行') from exc
        except ImportError as exc:
            raise RuntimeError('统一工具目前需要macOS或Linux；其他平台可用bridge.py') from exc
        try:
            yield
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)


class Task:
    def __init__(self, root, instance, timeout=900):
        self.root = Path(root).resolve()
        self.instance = Path(instance).resolve()
        self.timeout = timeout
        self.path = self.root / 'task.json'
        self.data = json.loads(self.path.read_text()) if self.path.exists() else None
        if self.data and self.data['instance'] != str(self.instance):
            raise ValueError('任务绑定其他实例')

    def save(self):
        bridge.atomic(self.path, self.data)

    def binding(self, active=True):
        status = bridge.status(self.instance, active)
        if self.data and self.data.get('world') is not None:
            if any(status.get(k) != self.data[k] for k in ('world', 'dimension')):
                raise RuntimeError('任务绑定其他世界/维度，禁止执行')
        return status

    def create(self, kind, inputs):
        if self.data:
            if self.data['kind'] != kind or self.data['input_hash'] != digest(inputs):
                raise RuntimeError('已有任务输入不同，请用新任务目录')
            return
        self.data = {'schema': 1, 'kind': kind, 'instance': str(self.instance),
                     'world': None, 'dimension': None, 'input_hash': digest(inputs),
                     'inputs': inputs, 'phase': 'PREPARING', 'requests': {}, 'job_id': None}
        self.save()

    def recover(self, key):
        entry = self.data['requests'].get(key)
        if not entry or entry['phase'] != 'PENDING':
            return entry
        result = self.instance / 'aibuild-bridge/results' / (entry['id'] + '.json')
        if result.exists():
            answer = json.loads(result.read_text())
            if answer.get('session') != entry['session'] or answer.get('id', entry['id']) != entry['id']:
                raise RuntimeError('结果会话/请求ID不符')
        elif key == 'execute':
            # A server-side receipt identifies a durable job even if the reply was lost.
            folder = {'move': 'moves', 'build': 'jobs'}.get(self.data['kind'], 'transactions')
            journals = Path(self.data['world']) / 'aibuild-data' / self.data['dimension'].replace(':', '_').replace('/', '_')
            matches = []
            for receipt in (journals / folder).glob('*/request.json'):
                saved = json.loads(receipt.read_text())
                if (saved.get('id') == entry['id'] and saved.get('session') == entry['session']
                        and saved.get('world') == self.data['world']
                        and saved.get('dimension') == self.data['dimension']
                        and (receipt.parent / 'progress.json').exists()):
                    matches.append(saved)
            if len(matches) > 1:
                raise RuntimeError('原请求对应多个任务，停止核对')
            if not matches:
                return entry
            answer = {'ok': True, 'session': entry['session'],
                      'data': {'id': matches[0]['job'], 'recovered_from_journal': True}}
        else:
            return entry
        if answer:
            # Large scans/inventory summaries stay in separate local response files.
            bridge.atomic(self.root / entry['response_file'], answer)
            entry['phase'] = 'DONE' if answer['ok'] else 'FAILED'
            entry['completed_at'] = time.time()
            entry['error'] = answer.get('error')
            data = answer.get('data', {})
            if key == 'execute' and answer['ok']:
                self.data['job_id'] = data.get('id', data.get('job'))
                self.data['phase'] = 'SUBMITTED' if self.data['job_id'] else 'COMPLETE'
            self.save()
        return entry

    def exchange(self, key, op, body):
        existing = self.recover(key)
        if existing and existing['payload_hash'] != digest({'op': op, 'body': body}):
            raise RuntimeError('请求内容变化，禁止复用请求编号')
        if not existing:
            status = self.binding()
            self.data.update(world=status['world'], dimension=status['dimension'])
            rid = uuid.uuid4().hex
            existing = {'id': rid, 'session': status['session'], 'op': op,
                        'phase': 'PENDING', 'response_file': 'responses/' + rid + '.json',
                        'payload_hash': digest({'op': op, 'body': body})}
            self.data['requests'][key] = existing
            self.save()  # Durable intent BEFORE IPC; a crash here must not cause a resend.
            bridge.atomic(self.instance / 'aibuild-bridge/requests' / (rid + '.json'),
                          {'id': rid, 'session': status['session'], 'world': status['world'],
                           'dimension': status['dimension'], 'op': op, **body})
        end = time.monotonic() + self.timeout
        while True:
            entry = self.recover(key)
            if entry['phase'] == 'FAILED':
                raise RuntimeError(entry.get('error') or '请求失败')
            if entry['phase'] == 'DONE':
                return json.loads((self.root / entry['response_file']).read_text()).get('data', {})
            status = self.binding(False)
            if not status.get('connected') or status['session'] != entry['session']:
                raise RuntimeError('连接已更换，先 status 核对原结果；未确认请求不会自动重发：' + entry['id'])
            if time.monotonic() >= end:
                raise RuntimeError('请求超时；先 status 核对，重跑同一命令只等待原请求：' + entry['id'])
            time.sleep(.15)

    def write_plan(self, plan):
        bridge.atomic(self.root / 'plan.json', plan)
        self.data['plan_hash'] = digest(plan)
        self.data['phase'] = 'PREPARED'
        self.save()

    def plan(self):
        plan = json.loads((self.root / 'plan.json').read_text())
        if digest(plan) != self.data['plan_hash']:
            raise RuntimeError('施工图已改变，请使用新任务目录重新准备')
        return plan

    def prepare_move(self, raw):
        spec = move_spec(raw)
        self.create('move', spec)
        if self.data.get('plan_hash'):
            return self.plan()
        source = {}
        excluded = {tuple(p) for p in spec['exclude']}
        for i, tile in enumerate(spec['regions']):
            scan = self.exchange('scan-' + str(i), 'scan', tile)
            for b in scan['blocks']:
                pos = tuple(tile['origin'][k] + b[k] for k in range(3))
                state = scan['palette'][b[3]]
                if pos not in excluded and state not in ('minecraft:air', 'minecraft:cave_air', 'minecraft:void_air'):
                    if pos in source and source[pos] != state:
                        raise RuntimeError('重复扫描区域现场变化，请重新准备')
                    source[pos] = state
        if not source or len(source) > 262144:
            raise ValueError('源非空气方块须为1～262144格')
        destinations = {tuple(p[k] + spec['offset'][k] for k in range(3)) for p in source}
        extras = {tuple(b['pos']) for b in spec['cleanup'] + spec['additions']}
        if destinations & excluded or set(source) & extras:
            raise ValueError('目标覆盖排除位置，或基础/清理覆盖搬迁源，必须调整方案')
        if len(set(source) | destinations | extras) > 524288:
            raise ValueError('统一改动位置超过524288格')
        plan = {**spec, 'source': [{'pos': list(p), 'expected': s} for p, s in sorted(source.items())]}
        self.write_plan(plan)
        return plan

    def check(self, refresh=False):
        plan = self.plan()
        self.binding()
        if self.data['job_id'] or 'execute' in self.data['requests']:
            raise RuntimeError('已经发送过施工；先 status，再继续/回滚原任务')
        if refresh:
            # Refresh only after a completed/failed check, or after its old session ended.
            old = self.recover('check')
            if old and old['phase'] == 'PENDING' and old['session'] == self.binding()['session']:
                raise RuntimeError('原检查仍未确认，禁止并发重查')
            if old:
                self.data['requests']['check-' + old['id']] = self.data['requests'].pop('check')
                self.save()
        kind = self.data['kind']
        op = {'move': 'move-check', 'projection': 'projection-check',
              'demolish': 'demolish-check', 'build': 'check'}[kind]
        result = self.exchange('check', op, plan if kind in ('move', 'demolish') else {'plan': plan})
        entry = self.data['requests']['check']
        self.data['check'] = {'token': result['token'], 'session': entry['session'],
                              'checked_at': entry['completed_at']}
        self.data['phase'] = 'CHECKED'
        self.save()
        summary = {k: v for k, v in result.items() if k not in ('inventory_snapshots', 'targets', 'entity_targets')}
        if kind == 'move':
            source = [b['pos'] for b in plan['source']]
            destination = [[p[k] + plan['offset'][k] for k in range(3)] for p in source]
            positions = source + destination + [b['pos'] for b in plan['cleanup'] + plan['additions']]
            def bounds(points):
                return [min(p[k] for p in points) for k in range(3)] + [max(p[k] for p in points) for k in range(3)]
            summary.update(source_bounds=bounds(source), destination_bounds=bounds(destination),
                           bounds=bounds(positions), cleanup_positions=len(plan['cleanup']),
                           addition_positions=len(plan['additions']))
        return summary

    def execute(self, confirmed):
        if not confirmed:
            raise RuntimeError('需已有用户对此具体任务的授权，并传入 --confirmed')
        self.plan()
        if 'execute' in self.data['requests']:
            entry = self.recover('execute')
            if entry['phase'] == 'DONE':
                return self.inspect()
            check = self.data['check']
        else:
            status = self.binding()
            check = self.data.get('check')
            if not check or check['session'] != status['session']:
                raise RuntimeError('未检查或会话改变，请 check --refresh')
            if time.time() - check['checked_at'] > 55:
                raise RuntimeError('检查令牌临近失效，请 check --refresh；尚未发送施工')
        op = {'move': 'move', 'projection': 'projection-build',
              'demolish': 'demolish', 'build': 'build'}[self.data['kind']]
        return self.exchange('execute', op, {'token': check['token']})

    def inspect(self):
        if not self.data:
            return bridge.status(self.instance)
        for key in list(self.data['requests']):
            self.recover(key)
        status = self.binding(False) if self.data['world'] else bridge.status(self.instance)
        progress = None
        if self.data['job_id']:
            job = self.data['job_id']
            folder = 'moves' if job.startswith('mv-') else 'transactions' if job.startswith('tx-') else 'jobs'
            path = Path(status['journals']) / folder / job / 'progress.json'
            if path.exists():
                raw = json.loads(path.read_text())
                progress = raw.get('progress', raw)
                self.data['phase'] = progress['phase']
                self.save()
        return {'kind': self.data['kind'], 'phase': self.data['phase'], 'job_id': self.data['job_id'],
                'world': self.data['world'], 'dimension': self.data['dimension'],
                'plan_hash': self.data.get('plan_hash'), 'progress': progress,
                'pending': [e for e in self.data['requests'].values() if e['phase'] == 'PENDING'],
                'connected': status.get('connected'), 'session': status.get('session'),
                'updated': status.get('updated'),
                'chunk_loading': status.get('chunk_loading')}

    def control(self, action):
        info = self.inspect()
        if not info.get('job_id'):
            raise RuntimeError('未确认任务编号，禁止猜测或重新施工；核对原请求 results')
        live = self.binding()
        if action in ('pause', 'cancel'):
            job = info['job_id']
            channel = 'move' if job.startswith('mv-') else 'transaction' if job.startswith('tx-') else 'job'
            if live.get(channel, {}).get('id') != job:
                raise RuntimeError('目标不是当前活动任务，禁止暂停/取消其他施工')
        pending = [(k, e) for k, e in self.data['requests'].items() if e['phase'] == 'PENDING']
        if pending:
            if len(pending) == 1 and pending[0][1]['op'] == action:
                return self.exchange(pending[0][0], action, {'job': info['job_id']})
            raise RuntimeError('存在未确认请求，先核对原请求结果')
        # Each deliberate control invocation has its own ID; a timeout remains pending.
        key = action + '-' + uuid.uuid4().hex
        return self.exchange(key, action, {'job': info['job_id']})


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--instance', type=Path, default=bridge.DEFAULT)
    parser.add_argument('--timeout', type=float, default=900)
    commands = parser.add_subparsers(dest='op', required=True)
    p = commands.add_parser('prepare-projection')
    p.add_argument('file', type=Path); p.add_argument('--origin', type=int, nargs=3, required=True)
    p.add_argument('--rotation', type=int, choices=(0, 90, 180, 270), default=0)
    p.add_argument('--task', type=Path, required=True)
    for op in ('prepare-move', 'prepare-demolish', 'prepare-build'):
        p = commands.add_parser(op); p.add_argument('file', type=Path)
        p.add_argument('--task', type=Path, required=True)
    for op in ('check', 'execute', 'status', 'resume', 'rollback', 'pause', 'cancel'):
        p = commands.add_parser(op); p.add_argument('--task', type=Path, required=True)
        if op == 'check': p.add_argument('--refresh', action='store_true')
        if op == 'execute': p.add_argument('--confirmed', action='store_true', required=True)
    args = parser.parse_args(argv)
    if not math.isfinite(args.timeout) or args.timeout <= 0:
        raise ValueError('超时须为有限正数')
    with task_lock(args.task):
        task = Task(args.task, args.instance, args.timeout)
        if args.op == 'prepare-projection':
            plan = projection.convert(args.file, args.origin, args.rotation)
            task.create('projection', plan)
            if not task.data.get('plan_hash'): task.write_plan(plan)
            result = {'phase': task.data['phase'], 'blocks': len(plan['blocks']),
                      'block_entities': sum('nbt' in b for b in plan['blocks']),
                      'entities': len(plan['entities']), 'plan_hash': task.data['plan_hash']}
        elif args.op == 'prepare-move':
            plan = task.prepare_move(json.loads(args.file.read_text()))
            result = {'phase': task.data['phase'], 'source_blocks': len(plan['source']),
                      'regions': len(plan['regions']), 'offset': plan['offset'],
                      'plan_hash': task.data['plan_hash']}
        elif args.op in ('prepare-build', 'prepare-demolish'):
            plan = json.loads(args.file.read_text())
            if args.op == 'prepare-demolish':
                if set(plan) - {'origin', 'size', 'job', 'deployments'}: raise ValueError('未知拆除字段')
                if 'origin' in plan:
                    triple(plan['origin']); size = triple(plan['size'], True)
                    if any(n > 64 for n in size): raise ValueError('拆除单范围最多64格')
                if 'origin' not in plan and 'job' not in plan: raise ValueError('必须指定拆除范围或原施工任务')
            task.create('build' if args.op == 'prepare-build' else 'demolish', plan)
            if not task.data.get('plan_hash'): task.write_plan(plan)
            result = {'phase': task.data['phase'], 'plan_hash': task.data['plan_hash']}
        else:
            if not task.data: raise RuntimeError('任务不存在，请先 prepare')
            if args.op == 'check': result = task.check(args.refresh)
            elif args.op == 'execute': result = task.execute(args.confirmed)
            elif args.op == 'status': result = task.inspect()
            else: result = task.control({'rollback': 'undo'}.get(args.op, args.op))
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError) as exc:
        raise SystemExit(str(exc))
