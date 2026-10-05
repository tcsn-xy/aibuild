#!/usr/bin/env python3
"""Local file bridge. Plans are checked separately; build requires explicit confirmation."""
import argparse,json,os,time,uuid,sys
from pathlib import Path
DEFAULT=Path.home()/'Library/Application Support/minecraft/versions/26.3-Fabric'
def atomic(path,data):
 path.parent.mkdir(parents=True,exist_ok=True);temp=path.with_name(path.name+'.'+uuid.uuid4().hex+'.tmp')
 try:
  with temp.open('x',encoding='utf8') as f:json.dump(data,f,ensure_ascii=False,separators=(',',':'));f.flush();os.fsync(f.fileno())
  os.replace(temp,path)
 finally:temp.unlink(missing_ok=True)
def status(instance,active=False):
 p=instance/'aibuild-bridge/status.json'
 if not p.exists():raise RuntimeError('没有连接记录。进单人世界后输入 /aibuild connect。')
 s=json.loads(p.read_text())
 if active and (not s.get('connected') or time.time()*1000-s.get('updated',0)>10000):raise RuntimeError('连接未启用或状态已过期，请在游戏输入 /aibuild connect。')
 return s
def request(instance,op,body=None,timeout=180,request_id=None):
 s=status(instance,True);rid=request_id or uuid.uuid4().hex;r={'id':rid,'session':s['session'],'world':s['world'],'dimension':s['dimension'],'op':op,**(body or {})}
 root=instance/'aibuild-bridge';atomic(root/'requests'/f'{rid}.json',r);result=root/'results'/f'{rid}.json';end=time.monotonic()+timeout
 while time.monotonic()<end:
  if result.exists():
   answer=json.loads(result.read_text())
   if answer.get('session')==s['session']:
    if not answer['ok']:raise RuntimeError(answer.get('error','请求失败'))
    return answer.get('data',{})
  now=status(instance)
  if not now.get('connected') or now.get('session')!=s['session']:raise RuntimeError('连接关闭或会话已更换；任务结果未确认，请查看 status。')
  time.sleep(.15)
 raise RuntimeError(f'请求超时，ID={rid}；检查 status/results，不要盲目重复施工。')
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--instance',type=Path,default=DEFAULT);p.add_argument('--timeout',type=float,default=180)
 sub=p.add_subparsers(dest='op',required=True)
 sub.add_parser('status');sub.add_parser('history')
 a=sub.add_parser('anchor');a.add_argument('--pos',type=int,nargs=3)
 a=sub.add_parser('scan');a.add_argument('--origin',type=int,nargs=3);a.add_argument('--size',type=int,nargs=3,default=[24,16,24]);a.add_argument('--out',type=Path,required=True)
 a=sub.add_parser('check');a.add_argument('plan',type=Path);a.add_argument('--out',type=Path)
 a=sub.add_parser('build');a.add_argument('token');a.add_argument('--confirmed',action='store_true',required=True,help='仅在用户已确认本次建筑后使用')
 a=sub.add_parser('move-check');a.add_argument('plan',type=Path);a.add_argument('--out',type=Path)
 a=sub.add_parser('move');a.add_argument('token');a.add_argument('--confirmed',action='store_true',required=True)
 for op in ['move-resume','move-undo']:
  a=sub.add_parser(op);a.add_argument('--job',required=True)
 for op in ['move-pause','move-cancel']:sub.add_parser(op)
 a=sub.add_parser('projection-check');a.add_argument('plan',type=Path);a.add_argument('--out',type=Path)
 a=sub.add_parser('demolish-check');a.add_argument('--origin',type=int,nargs=3);a.add_argument('--size',type=int,nargs=3);a.add_argument('--job');a.add_argument('--deployment',action='append',default=[]);a.add_argument('--out',type=Path)
 for op in ['projection-build','demolish']:
  a=sub.add_parser(op);a.add_argument('token');a.add_argument('--confirmed',action='store_true',required=True)
 for op in ['transaction-resume','transaction-undo']:
  a=sub.add_parser(op);a.add_argument('--job',required=True)
 for op in ['transaction-pause','transaction-cancel']:sub.add_parser(op)
 a=sub.add_parser('carts');a.add_argument('carts',type=Path,help='含 deployment_id 与 carts 的JSON；重复提交同一ID不会重复生成')
 for op in ['pause','cancel','resume','undo']:
  a=sub.add_parser(op)
  if op in ['resume','undo']:a.add_argument('--job')
 args=p.parse_args();body={}
 if args.op=='status':data=status(args.instance)
 elif args.op=='history':
  s=status(args.instance);data=[]
  for f in sorted(Path(s['journals']).glob('jobs/*/progress.json'),key=lambda f:f.stat().st_mtime,reverse=True):data.append(json.loads(f.read_text()))
  for f in sorted(Path(s['journals']).glob('transactions/*/progress.json'),key=lambda f:f.stat().st_mtime,reverse=True):data.append(json.loads(f.read_text())['progress'])
  for f in sorted(Path(s['journals']).glob('moves/*/progress.json'),key=lambda f:f.stat().st_mtime,reverse=True):data.append(json.loads(f.read_text()))
 else:
  if args.op=='anchor' and args.pos:body['pos']=args.pos
  elif args.op=='scan':
   body['size']=args.size
   if args.origin:body['origin']=args.origin
  elif args.op=='move-check':body=json.loads(args.plan.read_text())
  elif args.op in ['check','projection-check']:body['plan']=json.loads(args.plan.read_text())
  elif args.op in ['build','projection-build','demolish','move']:body['token']=args.token
  elif args.op=='demolish-check':
   if args.origin is not None:
    if args.size is None:raise ValueError('--origin requires --size')
    body.update(origin=args.origin,size=args.size)
   if args.job:body['job']=args.job
   body['deployments']=args.deployment
  elif args.op in ['transaction-resume','transaction-undo','move-resume','move-undo']:body['job']=args.job
  elif args.op=='carts':
   payload=json.loads(args.carts.read_text());body['deployment_id']=payload['deployment_id'];body['carts']=payload['carts']
  elif args.op in ['resume','undo'] and args.job:body['job']=args.job
  data=request(args.instance,{'carts':'spawn_carts'}.get(args.op,args.op),body,args.timeout)
  if getattr(args,'out',None):
   atomic(args.out,data)
   if args.op=='scan':data={k:v for k,v in data.items() if k!='blocks'};data['saved']=str(args.out.resolve())
 print(json.dumps(data,ensure_ascii=False,indent=2))
if __name__=='__main__':
 try:main()
 except (RuntimeError,OSError,ValueError,KeyError) as e:print(str(e),file=sys.stderr);sys.exit(1)
