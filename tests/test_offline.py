import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
import offline


class OfflineTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.instance=(Path(self.temp.name)/'instance').resolve();self.world=self.instance/'saves/example'
        self.world.mkdir(parents=True);(self.world/'level.dat').write_bytes(b'fixture')
        (self.world/'session.lock').write_bytes(b'\xe2\x98\x83')
        self.catalog=[{'id':'example','name':'示例世界','version':'26.3','path':str(self.world)}]

    def test_select_by_name_or_id(self):
        with patch.object(offline,'worlds',return_value=self.catalog):
            self.assertEqual(offline.select_world(self.instance,'示例世界'),self.world)
            self.assertEqual(offline.select_world(self.instance,'example'),self.world)

    def test_duplicate_name_refused(self):
        with patch.object(offline,'worlds',return_value=self.catalog+self.catalog):
            with self.assertRaisesRegex(ValueError,'不唯一'):offline.select_world(self.instance,'示例世界')

    def test_outside_world_and_version_refused(self):
        for changes in ({'path':str(self.instance/'foreign')},{'version':'26.1'}):
            with patch.object(offline,'worlds',return_value=[{**self.catalog[0],**changes}]):
                with self.assertRaises(ValueError):offline.select_world(self.instance,'example')

    def test_lock_preserves_bytes_and_excludes_other_process(self):
        original=(self.world/'session.lock').read_bytes()
        child="import fcntl,sys; f=open(sys.argv[1],'r+b'); fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)"
        with offline.world_lock(self.world):
            result=subprocess.run([sys.executable,'-c',child,str(self.world/'session.lock')],capture_output=True)
            self.assertNotEqual(result.returncode,0)
        self.assertEqual((self.world/'session.lock').read_bytes(),original)
        self.assertEqual(subprocess.run([sys.executable,'-c',child,str(self.world/'session.lock')],capture_output=True).returncode,0)

    def test_stopping_while_player_present_never_sends_request(self):
        runtime=self.instance/'aibuild-background/runtime-fixture';runtime.mkdir(parents=True)
        with patch.object(offline,'backend_status',return_value={'running':True,'runtime':str(runtime),'pid':1,'bridge':{'players':1}}):
            with self.assertRaisesRegex(RuntimeError,'玩家'):offline.stop(self.instance)
        self.assertFalse((runtime/'stop.json').exists())

    def test_failed_save_cannot_report_success(self):
        runtime=self.instance/'aibuild-background/runtime-fixture';runtime.mkdir(parents=True)
        (runtime/'exit.json').write_text(json.dumps({'saved':False}))
        with patch.object(offline,'backend_status',return_value={'running':True,'runtime':str(runtime),'world':str(self.world),'pid':1,'bridge':{'players':0}}),patch.object(offline,'alive',return_value=False):
            with self.assertRaisesRegex(RuntimeError,'保存回执'):offline.stop(self.instance)


if __name__=='__main__':unittest.main()
