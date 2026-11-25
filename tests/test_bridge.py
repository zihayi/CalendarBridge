import copy
import hashlib
import json
from pathlib import Path
import ssl
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from command_store import CommandStore, prepare_command
from server import make_server, Receiver
from setup_receiver import setup
from fixtures import snapshot, rebuild_instances


class BridgeTests(unittest.TestCase):
    def setUp(self):
        build = ROOT / 'dev' / '.build'
        build.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix='test-', dir=build)
        self.root = Path(self.temporary.name)
        self.config = setup(self.root / 'state', '192.168.1.10')
        self.server = make_server(self.config, host='127.0.0.1', port=0, data_dir=self.root / 'data', state_dir=self.root / 'state')
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.context = ssl.create_default_context(cafile=str(self.root / 'state' / 'server.crt'))
        self.sample = snapshot()

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(3)
        self.temporary.cleanup()

    def call(self, path, data=None, admin=False, token=None, context=None):
        payload = None if data is None else json.dumps(data).encode('utf-8')
        key = token if token is not None else self.config['adminToken' if admin else 'token']
        request = urllib.request.Request(f'https://127.0.0.1:{self.server.server_port}{path}', data=payload, headers={'Authorization': 'Bearer '+key, 'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(request, context=context or self.context, timeout=5) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            with error:
                return error.code, json.load(error)

    def create(self):
        return {'id': str(uuid.uuid4()), 'action': 'create', 'calendarId': 1,
                'changes': {'title': '电脑新日程', 'dtstart': self.sample['events'][0]['dtstart']+7200000,
                            'dtend': self.sample['events'][0]['dtend']+7200000, 'eventTimezone': 'Asia/Shanghai', 'allDay': 0}, 'reminders': [15]}

    def sync(self):
        self.assertEqual(self.call('/api/sync', self.sample)[0], 200)

    def test_tls_and_auth_roles(self):
        self.assertEqual(self.call('/api/health')[1]['version'], 4)
        self.assertEqual(self.call('/api/health', token='wrong')[0], 401)
        self.assertEqual(self.call('/desktop/state')[0], 401)
        self.assertEqual(self.call('/api/commands/next', {'clientId': 'fixture-phone', 'canWrite': True}, admin=True)[0], 401)
        with self.assertRaises(urllib.error.URLError):
            self.call('/api/health', context=ssl.create_default_context())
        fake = type('Request', (), {'client_address': ('192.168.1.20', 1234), 'headers': {'Authorization': 'Bearer '+self.config['adminToken']}, 'server': self.server})()
        self.assertFalse(Receiver.authorized(fake, desktop=True))

    def test_pairing_profiles_and_identity_preserved(self):
        phone = json.loads((self.root / 'state' / 'pairing.json').read_text())
        desktop = json.loads((self.root / 'state' / 'wallnote-connection.json').read_text())
        self.assertNotIn('adminToken', phone)
        self.assertNotIn('token', desktop)
        self.assertEqual(phone['certificateSha256'], desktop['certificateSha256'])
        before = {name: hashlib.sha256((self.root / 'state' / name).read_bytes()).hexdigest() for name in ('server.crt', 'server.key')}
        changed = setup(self.root / 'state', '192.168.1.11', 8767)
        self.assertEqual(changed['token'], self.config['token'])
        self.assertEqual(changed['adminToken'], self.config['adminToken'])
        self.assertEqual(before, {name: hashlib.sha256((self.root / 'state' / name).read_bytes()).hexdigest() for name in before})

    def test_snapshot_save_and_invalid_payload_does_not_replace(self):
        self.sync()
        previous = (self.root / 'data' / 'calendar.json').read_bytes()
        invalid = copy.deepcopy(self.sample); invalid['events'].append(invalid['events'][0])
        self.assertEqual(self.call('/api/sync', invalid)[0], 400)
        self.assertEqual((self.root / 'data' / 'calendar.json').read_bytes(), previous)
        self.assertEqual(self.call('/desktop/state', admin=True)[1]['snapshot']['clientId'], 'fixture-phone')

    def test_idempotent_roundtrip_and_permission_gate(self):
        self.sync(); request = self.create()
        self.assertEqual(self.call('/desktop/commands', request, admin=True)[0], 200)
        self.assertIsNone(self.call('/api/commands/next', {'clientId': 'fixture-phone', 'canWrite': False})[1]['command'])
        first = self.call('/api/commands/next', {'clientId': 'fixture-phone', 'canWrite': True})[1]['command']
        self.assertEqual(first['id'], request['id'])
        self.assertEqual(self.call('/api/commands/next', {'clientId': 'fixture-phone', 'canWrite': True})[1]['command']['id'], request['id'])
        result = {'id': request['id'], 'status': 'succeeded', 'eventId': 90, 'message': '模拟完成', 'clientId': 'fixture-phone'}
        self.assertEqual(self.call('/api/commands/result', result)[0], 200)
        self.assertEqual(self.call('/api/commands/result', result)[0], 200)
        self.assertEqual(self.call('/desktop/commands', request, admin=True)[0], 200)
        self.assertEqual(len(self.call('/desktop/state', admin=True)[1]['commands']), 1)
        self.assertEqual(self.call('/desktop/command/'+request['id'], admin=True)[1]['command']['state'], 'succeeded')
        changed = copy.deepcopy(request); changed['changes']['title'] = '不同内容'
        self.assertEqual(self.call('/desktop/commands', changed, admin=True)[0], 400)

    def test_device_binding(self):
        self.sync()
        other = copy.deepcopy(self.sample); other['clientId'] = 'another-phone'
        self.assertEqual(self.call('/api/sync', other)[0], 400)
        self.assertEqual(self.call('/api/commands/next', {'clientId': 'another-phone', 'canWrite': True})[0], 400)
        self.assertEqual(self.call('/desktop/state', admin=True)[1]['snapshot']['clientId'], 'fixture-phone')

    def test_uncertain_is_not_replayed_and_delivered_cannot_cancel(self):
        self.sync(); request = self.create()
        self.call('/desktop/commands', request, admin=True)
        self.call('/api/commands/next', {'clientId': 'fixture-phone', 'canWrite': True})
        self.assertEqual(self.call('/desktop/cancel', {'id': request['id']}, admin=True)[0], 400)
        self.assertEqual(self.call('/api/commands/result', {'id': request['id'], 'clientId': 'wrong', 'status': 'uncertain'})[0], 400)
        self.call('/api/commands/result', {'id': request['id'], 'clientId': 'fixture-phone', 'status': 'uncertain', 'message': '执行中断'})
        self.call('/desktop/commands', request, admin=True)
        self.assertIsNone(self.call('/api/commands/next', {'clientId': 'fixture-phone', 'canWrite': True})[1]['command'])
        self.assertEqual(CommandStore(self.root / 'data' / 'commands.sqlite3').get(request['id'])['state'], 'uncertain')

    def test_stale_update_and_readonly_null_calendar(self):
        self.sync()
        request = {'id': str(uuid.uuid4()), 'action': 'update', 'eventId': 2, 'expected': copy.deepcopy(self.sample['events'][0]), 'changes': {'title': '电脑修改'}}
        self.sample['events'][0]['title'] = '手机已修改'; rebuild_instances(self.sample); self.sync()
        self.assertEqual(self.call('/desktop/commands', request, admin=True)[0], 400)
        readonly = copy.deepcopy(self.sample); readonly['calendars'][0]['calendar_access_level'] = None
        with self.assertRaises(ValueError): prepare_command(self.create(), readonly)

    def test_no_delete_and_reminder_limits(self):
        request = self.create(); request['action'] = 'delete'
        with self.assertRaises(ValueError): prepare_command(request, self.sample)
        request = self.create(); request['changes']['calendar_id'] = 20
        with self.assertRaises(ValueError): prepare_command(request, self.sample)
        request = self.create(); request['reminders'] = [15, 15]
        with self.assertRaises(ValueError): prepare_command(request, self.sample)
        request = self.create(); request['changes']['dtend'] = request['changes']['dtstart']
        with self.assertRaises(ValueError): prepare_command(request, self.sample)

    def test_recurring_series_duration(self):
        sample = copy.deepcopy(self.sample); sample['events'][0]['rrule'] = 'FREQ=WEEKLY'
        original = sample['events'][0]
        request = {'id': str(uuid.uuid4()), 'action': 'update', 'eventId': 2, 'expected': original, 'changes': {'dtstart': original['dtstart'], 'dtend': original['dtstart']+7200000}}
        command = prepare_command(request, sample)
        self.assertIsNone(command['changes']['dtend'])
        self.assertEqual(command['changes']['duration'], 'PT7200S')


if __name__ == '__main__':
    unittest.main(verbosity=2)
