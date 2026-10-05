import sys,unittest,importlib.util,os
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from projection import *
N=lambda x:Number(x,3)
class ProjectionTests(unittest.TestCase):
 def test_typed_nbt(self):
  self.assertEqual(snbt({'v':Number(1,1),'u':Array([1,-2],11),'p':[Number(.25,6)]}),'{"v":1b,"u":[I;1,-2],"p":[0.25d]}')
 def test_negative_offset(self):
  r={'Size':dict(zip('xyz',map(N,[-2,1,1]))),'Position':dict(zip('xyz',map(N,[7,2,-3]))),'BlockStatePalette':[{'Name':'minecraft:air'},{'Name':'minecraft:stone'}],'BlockStates':Array([4],12)}
  vox,low,pos=decode(r);self.assertEqual(low,[6,2,-3]);self.assertEqual(vox[(6,2,-3)]['Name'],'minecraft:air');self.assertEqual(vox[(7,2,-3)]['Name'],'minecraft:stone')
 def test_actual_source_preserved(self):
  fixture=os.environ.get('AIBUILD_LITEMATIC_FIXTURE')
  if not fixture:self.skipTest('Set AIBUILD_LITEMATIC_FIXTURE to the optional 36-furnace integration fixture')
  path=Path(fixture)
  p=convert(path,[0,150,0]);self.assertEqual(len(p['entities']),22);self.assertEqual(sum('nbt'in b for b in p['blocks']),119)
  self.assertEqual(sum('minecraft:hopper_minecart'in e['nbt'] for e in p['entities']),4)
  self.assertEqual(sum('minecraft:sand' in b['state_nbt'] for b in p['blocks']),2)
  self.assertEqual(sum('minecraft:soul_fire' in b['state_nbt'] for b in p['blocks']),1)
  self.assertTrue(any('Items' in b.get('nbt','') for b in p['blocks']))
 def test_truncation(self):
  with self.assertRaises(ValueError):Reader(b'\0').take(2)
if __name__=='__main__':unittest.main()
