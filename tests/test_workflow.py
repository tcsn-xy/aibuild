import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import bridge
from workflow import Task, digest, move_spec, split_regions


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.instance = Path(self.temp.name) / 'instance'
        self.world = Path(self.temp.name) / 'world'
        self.state = {'world': str(self.world), 'dimension': 'minecraft:overworld',
                      'session': 'test-session', 'connected': True,
                      'updated': time.time() * 1000,
                      'journals': str(self.world / 'aibuild-data/minecraft_overworld')}
        self.set_status()
        self.task = Task(Path(self.temp.name) / 'task', self.instance, .002)
        self.task.create('build', {})
        self.task.write_plan({'version': 1, 'origin': [0, 64, 0], 'blocks': []})

    def set_status(self):
        self.state['updated'] = time.time() * 1000
        bridge.atomic(self.instance / 'aibuild-bridge/status.json', self.state)

    def respond(self, key, data=None, ok=True):
        e = self.task.data['requests'][key]
        bridge.atomic(self.instance / 'aibuild-bridge/results' / (e['id'] + '.json'),
                      {'session': e['session'], 'ok': ok, 'data': data or {}, 'error': 'fixture failure'})

    def pending(self, key='execute', op='build', body=None):
        with self.assertRaisesRegex(RuntimeError, '超时'):
            self.task.exchange(key, op, body or {'token': 'fixture-token'})
        return self.task.data['requests'][key]

    def test_partition_covers_large_negative_region_once(self):
        tiles = split_regions([{'origin': [-70, 64, -32], 'size': [79, 65, 2]}])
        positions = [(r['origin'][0]+x, r['origin'][1]+y, r['origin'][2]+z)
                     for r in tiles for x in range(r['size'][0])
                     for y in range(r['size'][1]) for z in range(r['size'][2])]
        self.assertEqual(len(positions), 79*65*2)
        self.assertEqual(len(set(positions)), len(positions))
        self.assertEqual(min(p[0] for p in positions), -70)
        self.assertEqual(max(p[1] for p in positions), 128)
        self.assertTrue(all(max(r['size']) <= 64 for r in tiles))

    def test_invalid_and_excessive_regions(self):
        for size in ([0, 1, 1], [True, 1, 1], [10000000, 1, 1], [64, 64, 129]):
            with self.assertRaises(ValueError):
                split_regions([{'origin': [0, 0, 0], 'size': size}])

    def test_input_validation(self):
        spec = {'offset': [0, -30, -30], 'regions': [{'origin': [0, 64, 0], 'size': [2, 2, 2]}]}
        self.assertEqual(move_spec(spec)['offset'], spec['offset'])
        for change in ({'offset': [0, 0, 0]}, {'exclude': [[9, 64, 0]]},
                       {'cleanup': [{'pos': [0, 64, 0], 'expected': 'minecraft:stone'}]}):
            with self.assertRaises(ValueError):
                move_spec({**spec, **change})

    def test_pending_request_never_resent(self):
        entry = self.pending()
        path = self.instance / 'aibuild-bridge/requests' / (entry['id'] + '.json')
        path.unlink()  # server has consumed the request
        with self.assertRaisesRegex(RuntimeError, '超时'):
            Task(self.task.root, self.instance, .002).exchange('execute', 'build', {'token': 'fixture-token'})
        self.assertFalse(path.exists())
        self.assertEqual(len(list(path.parent.glob('*.json'))), 0)

    def test_late_result_recovered_after_reconnect(self):
        self.pending()
        self.respond('execute', {'job': 'saved-job'})
        self.state['session'] = 'new-session'
        self.set_status()
        info = self.task.inspect()
        self.assertEqual(info['job_id'], 'saved-job')
        self.assertFalse(info['pending'])

    def test_lost_reply_uses_bound_durable_receipt(self):
        entry = self.pending()
        job = Path(self.state['journals']) / 'jobs/original-job'
        bridge.atomic(job / 'request.json', {'id': entry['id'], 'session': entry['session'],
                                            'world': str(self.world), 'dimension': self.state['dimension'],
                                            'job': 'original-job'})
        bridge.atomic(job / 'progress.json', {'id': 'original-job', 'phase': 'PAUSED'})
        self.state['session'] = 'new-session'; self.set_status()
        info = self.task.inspect()
        self.assertEqual(info['job_id'], 'original-job')
        self.assertEqual(info['phase'], 'PAUSED')

    def test_receipt_without_checkpoint_not_accepted(self):
        e = self.pending()
        job = Path(self.state['journals']) / 'jobs/incomplete'
        bridge.atomic(job / 'request.json', {'id': e['id'], 'session': e['session'],
                                            'world': str(self.world), 'dimension': self.state['dimension'],
                                            'job': 'incomplete'})
        self.assertIsNone(self.task.inspect()['job_id'])

    def test_failed_result_never_retries(self):
        self.pending(); self.respond('execute', ok=False)
        with self.assertRaisesRegex(RuntimeError, 'fixture failure'):
            self.task.exchange('execute', 'build', {'token': 'fixture-token'})
        self.assertEqual(self.task.data['requests']['execute']['phase'], 'FAILED')

    def test_world_dimension_and_instance_isolation(self):
        self.pending('check', 'check', {'plan': self.task.plan()})
        self.state['dimension'] = 'minecraft:the_nether'; self.set_status()
        with self.assertRaisesRegex(RuntimeError, '世界/维度'):
            self.task.inspect()
        with self.assertRaisesRegex(ValueError, '其他实例'):
            Task(self.task.root, Path(self.temp.name) / 'other')

    def test_changed_payload_and_plan_are_rejected(self):
        self.pending()
        with self.assertRaisesRegex(RuntimeError, '请求内容变化'):
            self.task.exchange('execute', 'build', {'token': 'different'})
        bridge.atomic(self.task.root / 'plan.json', {'version': 2})
        with self.assertRaisesRegex(RuntimeError, '施工图已改变'):
            self.task.plan()

    def test_movement_reads_tiles_and_excludes_blocks(self):
        spec = {'offset': [2, 0, 0], 'regions': [{'origin': [0, 64, 0], 'size': [2, 1, 1]}],
                'exclude': [[0, 64, 0]], 'additions': [], 'cleanup': []}
        task = Task(Path(self.temp.name) / 'move', self.instance)
        scan = {'palette': ['minecraft:stone'], 'blocks': [[0, 0, 0, 0, False], [1, 0, 0, 0, False]]}
        with patch.object(task, 'exchange', return_value=scan) as call:
            plan = task.prepare_move(spec)
        self.assertEqual(plan['source'], [{'pos': [1, 64, 0], 'expected': 'minecraft:stone'}])
        self.assertEqual(call.call_count, 1)

    def test_destination_cannot_overwrite_excluded_block(self):
        spec = {'offset': [-1, 0, 0], 'regions': [{'origin': [0, 64, 0], 'size': [2, 1, 1]}],
                'exclude': [[0, 64, 0]]}
        task = Task(Path(self.temp.name) / 'move', self.instance)
        with patch.object(task, 'exchange', return_value={
                'palette': ['minecraft:stone'], 'blocks': [[1, 0, 0, 0, False]]}):
            with self.assertRaisesRegex(ValueError, '目标覆盖排除'):
                task.prepare_move(spec)

    def test_refresh_old_check_after_session_changed(self):
        self.pending('check', 'check', {'plan': self.task.plan()})
        self.state['session'] = 'new-session'; self.set_status()
        with patch.object(self.task, 'exchange', return_value={'token': 'new-token'}) as call:
            # exchange mock installs the entry like the actual successful exchange
            def exchange(key, op, body):
                self.task.data['requests'][key] = {'session': 'new-session', 'completed_at': time.time()}
                return {'token': 'new-token'}
            call.side_effect = exchange
            self.task.check(refresh=True)
        self.assertEqual(self.task.data['check']['session'], 'new-session')

    def test_old_token_requires_refresh_before_any_submission(self):
        self.task.data.update(world=str(self.world), dimension=self.state['dimension'])
        self.task.data['check'] = {'token': 'old', 'session': self.state['session'], 'checked_at': time.time()-56}
        self.task.save()
        with self.assertRaisesRegex(RuntimeError, '临近失效'):
            self.task.execute(True)
        self.assertNotIn('execute', self.task.data['requests'])

    def test_lost_control_response_waits_same_request(self):
        self.task.data.update(world=str(self.world), dimension=self.state['dimension'], job_id='job')
        self.task.save()
        with self.assertRaisesRegex(RuntimeError, '超时'):
            self.task.control('undo')
        entry = next(iter(self.task.data['requests'].values()))
        request_file = self.instance/'aibuild-bridge/requests'/(entry['id']+'.json')
        request_file.unlink()
        with self.assertRaisesRegex(RuntimeError, '超时'):
            self.task.control('undo')
        self.assertEqual(len(self.task.data['requests']), 1)
        self.assertFalse(request_file.exists())

    def test_check_does_not_replace_unresolved_current_request(self):
        self.pending('check', 'check', {'plan': self.task.plan()})
        with self.assertRaisesRegex(RuntimeError, '原检查仍未确认'):
            self.task.check(refresh=True)


if __name__ == '__main__':
    unittest.main()
