#!/usr/bin/env python3
"""Lossless bounded litematic -> v3 plan. No game access or substitutions."""
import argparse,gzip,struct,json,hashlib,math
from pathlib import Path
class Number:
 def __init__(self,value,kind):self.value,self.kind=value,kind
 def __int__(self):return int(self.value)
 def __float__(self):return float(self.value)
class Array(list):
 def __init__(self,values,kind):super().__init__(values);self.kind=kind
class Reader:
 def __init__(self,data):self.data=data;self.i=0
 def take(self,n):
  if n<0 or self.i+n>len(self.data):raise ValueError('NBT truncated')
  v=self.data[self.i:self.i+n];self.i+=n;return v
 def num(self,t):return struct.unpack('>'+t,self.take(struct.calcsize('>'+t)))[0]
 def string(self):return self.take(self.num('H')).decode('utf8')
 def value(self,t):
  if 1<=t<=6:return Number(self.num({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[t]),t)
  if t==8:return self.string()
  if t in (7,11,12):
   n=self.num('i')
   if not 0<=n<=10000000:raise ValueError('NBT array size')
   return Array([self.num({7:'b',11:'i',12:'q'}[t]) for _ in range(n)],t)
  if t==9:
   k,n=self.num('B'),self.num('i')
   if not 0<=n<=1000000:raise ValueError('NBT list size')
   return [self.value(k) for _ in range(n)]
  if t==10:
   d={}
   while True:
    k=self.num('B')
    if not k:return d
    name=self.string()
    if name in d:raise ValueError('duplicate NBT key')
    d[name]=self.value(k)
  raise ValueError('NBT type '+str(t))
def read(path):
 r=Reader(gzip.decompress(Path(path).read_bytes()))
 if r.num('B')!=10:raise ValueError('compound root required')
 r.string();v=r.value(10)
 if r.i!=len(r.data):raise ValueError('trailing NBT')
 return v
def snbt(v):
 if isinstance(v,Number):
  if not math.isfinite(float(v)):raise ValueError('nonfinite NBT')
  return str(v.value)+{1:'b',2:'s',3:'',4:'L',5:'f',6:'d'}[v.kind]
 if isinstance(v,Array):return '['+{7:'B',11:'I',12:'L'}[v.kind]+';'+','.join(str(x)+{7:'b',11:'',12:'L'}[v.kind] for x in v)+']'
 if isinstance(v,dict):return '{'+','.join(json.dumps(k)+':'+snbt(x) for k,x in v.items())+'}'
 if isinstance(v,list):return '['+','.join(map(snbt,v))+']'
 if isinstance(v,str):return json.dumps(v,ensure_ascii=False)
 raise TypeError(type(v))
def coords(d):return [int(d[k]) for k in 'xyz']
def decode(region):
 size=coords(region['Size']);pos=coords(region['Position']);dims=list(map(abs,size))
 if 0 in dims:raise ValueError('empty region')
 low=[pos[k]+min(0,size[k]+1) for k in range(3)]
 pal=region['BlockStatePalette'];bits=max(2,(len(pal)-1).bit_length());words=[w&((1<<64)-1) for w in region['BlockStates']]
 if len(words)*64<math.prod(dims)*bits:raise ValueError('truncated palette')
 out={}
 for i in range(math.prod(dims)):
  b=i*bits;v=words[b//64]>>(b%64)
  if b%64+bits>64:v|=words[b//64+1]<<(64-b%64)
  idx=v&((1<<bits)-1)
  if idx>=len(pal):raise ValueError('palette index')
  q=(low[0]+i%dims[0],low[1]+i//(dims[0]*dims[2]),low[2]+(i//dims[0])%dims[2]);out[q]=pal[idx]
 return out,low,pos

def convert(path,origin,rotation=0):
 n=read(path)
 if int(n['Version']) not in (5,6,7):raise ValueError('unsupported litematic version')
 blocks={};bes={};entities=[];ticks=[]
 for name,r in n['Regions'].items():
  vox,low,rpos=decode(r)
  for p,state in vox.items():
   if p in blocks and snbt(blocks[p])!=snbt(state):raise ValueError('conflicting overlapping regions')
   blocks[p]=state
  for be in r.get('TileEntities',[]):
   # Litematica block entity coordinates are container-relative, unlike entity Pos.
   p=tuple(low[k]+coords(be)[k] for k in range(3))
   if p not in vox:raise ValueError('block entity outside region')
   if p in bes and snbt(bes[p])!=snbt(be):raise ValueError('conflicting block entities')
   bes[p]=be
  for i,e in enumerate(r.get('Entities',[])):
   p=[rpos[k]+float(e['Pos'][k]) for k in range(3)]
   entities.append({'key':name+'/'+str(i),'pos':p,'nbt':snbt(e)})
  for key,kind in [('PendingBlockTicks','block'),('PendingFluidTicks','fluid')]:
   for t in r.get(key,[]):
    p=[low[k]+coords(t)[k] for k in range(3)]
    ticks.append({'pos':p,'type':kind,'id':t.get('Block',t.get('Fluid')),'delay':int(t['Time']),'priority':int(t.get('Priority',Number(0,3)))})
 if not blocks:raise ValueError('empty schematic')
 mins=[min(p[k] for p in blocks) for k in range(3)];maxs=[max(p[k] for p in blocks) for k in range(3)]
 if any(maxs[k]-mins[k]+1>64 for k in range(3)):raise ValueError('projection exceeds 64 cubed; split explicitly')
 def relative(p):return [p[k]-mins[k] for k in range(3)]
 for e in entities:e['pos']=relative(e['pos'])
 for t in ticks:t['pos']=relative(t['pos'])
 return {'version':3,'name':Path(path).stem+'｜完整投影','source_sha256':hashlib.sha256(Path(path).read_bytes()).hexdigest(),'data_version':int(n['MinecraftDataVersion']),'origin':origin,'rotation':rotation,'blocks':[{'pos':relative(p),'state_nbt':snbt(s),**({'nbt':snbt(bes[p])} if p in bes else {})} for p,s in sorted(blocks.items(),key=lambda v:(v[0][1],v[0][2],v[0][0]))],'entities':entities,'ticks':ticks,'activation':[]}
if __name__=='__main__':
 a=argparse.ArgumentParser();a.add_argument('file',type=Path);a.add_argument('--origin',nargs=3,type=int,required=True);a.add_argument('--rotation',type=int,choices=[0,90,180,270],default=0);a.add_argument('--out',type=Path,required=True);args=a.parse_args()
 p=convert(args.file,args.origin,args.rotation);args.out.parent.mkdir(parents=True,exist_ok=True);args.out.write_text(json.dumps(p,ensure_ascii=False,indent=1)+'\n');print(json.dumps({'blocks':len(p['blocks']),'block_entities':sum('nbt'in b for b in p['blocks']),'entities':len(p['entities']),'ticks':len(p['ticks']),'out':str(args.out)},ensure_ascii=False))
