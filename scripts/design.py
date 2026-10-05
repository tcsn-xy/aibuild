#!/usr/bin/env python3
"""Deterministic local examples and helpers; the mod receives only the final JSON voxel list."""
from pathlib import Path
import argparse,json
class Design:
 def __init__(self,name):self.name=name;self.blocks={}
 def set(self,x,y,z,state):self.blocks[x,y,z]=state;return self
 def fill(self,x1,y1,z1,x2,y2,z2,state):
  for y in range(y1,y2+1):
   for z in range(z1,z2+1):
    for x in range(x1,x2+1):self.set(x,y,z,state)
  return self
 def room(self,x,y,z,w,h,d,wall,floor):
  self.fill(x,y,z,x+w-1,y+h-1,z+d-1,wall)
  self.fill(x+1,y+1,z+1,x+w-2,y+h-2,z+d-2,'minecraft:air')
  self.fill(x,y,z,x+w-1,y,z+d-1,floor);return self
 def door(self,x,y,z,facing='north',wood='oak'):
  for half,dy in [('lower',0),('upper',1)]:self.set(x,y+dy,z,f'minecraft:{wood}_door[facing={facing},half={half},hinge=left,open=false,powered=false]')
  return self
 def roof(self,x,y,z,w,d,wood='spruce'):
  # Symmetric gable along z, complete occupied layers with overhang.
  for i in range((w+1)//2):
   left=x+i;right=x+w-1-i
   self.fill(left,y+i,z,left,y+i,z+d-1,f'minecraft:{wood}_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]')
   self.fill(right,y+i,z,right,y+i,z+d-1,f'minecraft:{wood}_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]')
  return self
 def export(self,origin,rotation=0):
  # Structural blocks first, fragile furniture/multiblocks last; retain deterministic ordering.
  def key(item):
   (x,y,z),state=item;late=any(t in state for t in ['_door[','_bed[','torch','lantern','flower_pot']);return (late,y,z,x)
  return {'version':1,'name':self.name,'origin':origin,'rotation':rotation,'blocks':[{'pos':list(p),'state':s} for p,s in sorted(self.blocks.items(),key=key)]}
def house():
 d=Design('两层橡木屋');d.room(1,0,1,13,6,11,'minecraft:oak_planks','minecraft:stone_bricks')
 d.room(1,5,1,13,5,11,'minecraft:oak_planks','minecraft:oak_planks')
 for x in [1,13]:
  for z in [1,11]:d.fill(x,1,z,x,9,z,'minecraft:oak_log[axis=y]')
 for y in [2,7]:
  for x in [3,4,9,10]:d.fill(x,y,1,x,y+1,1,'minecraft:glass');d.fill(x,y,11,x,y+1,11,'minecraft:glass')
  for z in [3,4,8,9]:d.fill(1,y,z,1,y+1,z,'minecraft:glass');d.fill(13,y,z,13,y+1,z,'minecraft:glass')
 d.door(7,1,1);d.fill(5,0,0,9,0,0,'minecraft:stone_bricks')
 # Internal stairs rise toward the second floor with an unobstructed stairwell and landing.
 d.fill(10,5,5,11,5,9,'minecraft:air')
 for i in range(5):
  z=9-i;d.fill(10,1,z,11,i+1,z,'minecraft:oak_planks')
  d.fill(10,i+1,z,11,i+1,z,'minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]')

 d.roof(0,10,0,15,13)
 # Fill gable ends beneath the sloped roof, leaving rooms hollow.
 for i in range(1,7):
  d.fill(i,10,1,14-i,9+i,1,'minecraft:oak_planks');d.fill(i,10,11,14-i,9+i,11,'minecraft:oak_planks')
 d.set(7,8,1,'minecraft:glass');d.set(7,8,11,'minecraft:glass')
 d.set(3,1,8,'minecraft:crafting_table');d.set(3,1,9,'minecraft:bookshelf')
 d.set(4,1,8,'minecraft:red_bed[facing=east,part=foot,occupied=false]');d.set(5,1,8,'minecraft:red_bed[facing=east,part=head,occupied=false]')
 d.set(7,9,6,'minecraft:lantern[hanging=true,waterlogged=false]');d.set(7,4,6,'minecraft:lantern[hanging=true,waterlogged=false]')
 # lanterns need blocks above; ceiling of the first floor already exists, second needs tie beam.
 d.fill(3,10,6,11,10,6,'minecraft:oak_log[axis=x]')
 return d
def tower():
 d=Design('小型石塔');d.room(1,0,1,11,16,11,'minecraft:stone_bricks','minecraft:stone_bricks')
 d.fill(1,15,1,11,15,11,'minecraft:stone_bricks');d.door(6,1,1);d.fill(5,0,0,7,0,0,'minecraft:stone_bricks')
 for y in [5,10]:
  d.fill(2,y,2,10,y,10,'minecraft:stone_bricks')
  d.fill(9,y,8,10,y,10,'minecraft:air')
 for y in [3,8,13]:
  for z in [1,11]:d.fill(6,y,z,6,y+1,z,'minecraft:glass')
  for x in [1,11]:d.fill(x,y,6,x,y+1,6,'minecraft:glass')
 # Ascending spiral stairs around the inside edge, continuous one-block rise.
 path=[(x,9,'west') for x in range(9,2,-1)]+[(3,z,'north') for z in range(8,2,-1)]+[(x,3,'east') for x in range(4,10)]
 for i,(x,z,face) in enumerate(path[:15]):
  y=i+1;d.fill(x,1,z,x,y-1,z,'minecraft:stone_bricks');d.set(x,y,z,f'minecraft:stone_brick_stairs[facing={face},half=bottom,shape=straight,waterlogged=false]');d.fill(x,y+1,z,x,y+2,z,'minecraft:air')
 for x in range(1,12,2):d.set(x,16,1,'minecraft:stone_bricks');d.set(x,16,11,'minecraft:stone_bricks')
 for z in range(3,10,2):d.set(1,16,z,'minecraft:stone_bricks');d.set(11,16,z,'minecraft:stone_bricks')
 return d
def courtyard():
 d=Design('小型庭院');d.fill(0,0,0,26,0,22,'minecraft:grass_block');d.fill(11,0,0,15,0,22,'minecraft:stone_bricks');d.fill(0,0,10,26,0,12,'minecraft:stone_bricks')
 for x in [0,26]:d.fill(x,1,0,x,1,22,'minecraft:oak_fence')
 for z in [0,22]:d.fill(0,1,z,26,1,z,'minecraft:oak_fence')
 d.fill(11,1,0,15,1,0,'minecraft:air')
 for x,z in [(2,2),(17,2),(2,15),(17,15)]:
  d.room(x,0,z,8,5,6,'minecraft:oak_planks','minecraft:stone_bricks');d.door(x+4,1,z);d.roof(x-1,5,z-1,10,8)
  d.fill(x+1,2,z,x+2,3,z,'minecraft:glass')
 return d
def main():
 p=argparse.ArgumentParser();p.add_argument('kind',choices=['house','tower','courtyard']);p.add_argument('--origin',type=int,nargs=3,required=True);p.add_argument('--rotation',type=int,choices=[0,90,180,270],default=0);p.add_argument('--out',type=Path,required=True);a=p.parse_args();a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(globals()[a.kind]().export(a.origin,a.rotation),ensure_ascii=False,separators=(',',':'))+'\n');print(a.out)
if __name__=='__main__':main()
