"""Detect a running formal client without printing process arguments or tokens."""
from pathlib import Path
import subprocess,re,json
DEFAULT=Path.home()/'Library/Application Support/minecraft/versions/26.3-Fabric'
def assert_closed(base=DEFAULT):
 base=base.resolve()
 for line in subprocess.check_output(['ps','-axo','pid,command'],text=True).splitlines():
  parts=line.strip().split(None,1)
  if len(parts)!=2 or not re.search(r'(^|/)(java|javaw)(\s|$)',parts[1]):continue
  pid,command=parts
  if '--gameDir' in command and str(base) in command.split('--gameDir',1)[1]:raise RuntimeError('正式游戏正在运行，请关闭后安装/卸载。')
  cwd=subprocess.run(['lsof','-a','-p',pid,'-d','cwd','-Fn'],text=True,capture_output=True).stdout
  directory=next((l[1:] for l in cwd.splitlines() if l.startswith('n')),None)
  for match in re.finditer(r'@([^\s]+\.args)',command):
   f=Path(match.group(1))
   if not f.is_absolute() and directory:f=Path(directory)/f
   if not f.exists():continue
   try:args=[json.loads(l) for l in f.read_text().splitlines() if l.strip()]
   except (ValueError,OSError):continue
   if '--gameDir' in args and Path(args[args.index('--gameDir')+1]).resolve()==base:raise RuntimeError('正式游戏正在运行，请关闭后安装/卸载。')
  if directory and Path(directory).resolve()==base and ('KnotClient' in command or 'net.minecraft' in command):raise RuntimeError('正式游戏正在运行，请关闭后安装/卸载。')
