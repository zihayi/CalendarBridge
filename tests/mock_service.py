"""Loopback-only fixture process for WallNote integration tests. Never uses real phone data."""
import argparse
import json
from pathlib import Path
import sys
import threading

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from server import make_server, persist
from setup_receiver import setup, atomic_json
from command_store import EVENT_FIELDS
from fixtures import snapshot, rebuild_instances

parser = argparse.ArgumentParser()
parser.add_argument('--root', type=Path, required=True)
args = parser.parse_args()
config = setup(args.root / 'state', '192.168.1.10')
service = make_server(config, host='127.0.0.1', port=0, data_dir=args.root / 'data', state_dir=args.root / 'state')
data = snapshot()
service.commands.register_device(data)
persist(data, service.data_dir)
profile_file = args.root / 'state' / 'wallnote-connection.json'
profile = json.loads(profile_file.read_text(encoding='utf-8'))
profile['baseUrl'] = f'https://127.0.0.1:{service.server_port}'
atomic_json(profile_file, profile)
thread = threading.Thread(target=service.serve_forever, daemon=True)
thread.start()
print(json.dumps({'ready': True, 'profile': str(profile_file), 'port': service.server_port}), flush=True)
try:
    for line in sys.stdin:
        request = json.loads(line)
        if request['op'] == 'apply' or request['op'] == 'uncertain':
            while True:
                command = service.commands.next({'clientId': data['clientId'], 'canWrite': True, 'appVersion': 3, 'device': 'Mock Android'})
                if command is None: break
                if request['op'] == 'uncertain':
                    service.commands.finish({'id': command['id'], 'clientId': data['clientId'], 'status': 'uncertain', 'message': '模拟中断'})
                    continue
                if command['action'] == 'create':
                    event = {key: None for key in EVENT_FIELDS}
                    event.update(_id=max(row['_id'] for row in data['events'])+1, calendar_id=command['calendarId'], deleted=0, eventStatus=1)
                    event.update(command['changes']); data['events'].append(event)
                else:
                    event = next(row for row in data['events'] if row['_id'] == command['eventId'])
                    event.update(command['changes'])
                rebuild_instances(data); persist(data, service.data_dir)
                service.commands.finish({'id': command['id'], 'clientId': data['clientId'], 'status': 'succeeded', 'eventId': event['_id'], 'message': '模拟手机已完成'})
        elif request['op'] == 'edit-phone':
            data['events'][0]['title'] = '模拟手机侧更新'; rebuild_instances(data); persist(data, service.data_dir)
        elif request['op'] == 'count':
            print(json.dumps({'count': len(service.commands.status()['commands'])}), flush=True)
        print(json.dumps({'done': request['op']}), flush=True)
finally:
    service.shutdown(); service.server_close(); thread.join(3)
