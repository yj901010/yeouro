#!/usr/bin/env python3
"""Local first-slice smoke test. Creates synthetic sessions; never prints credentials."""
import argparse
import datetime as dt
import json
import urllib.error
import urllib.request
import uuid
from urllib.parse import urlparse

parser = argparse.ArgumentParser()
parser.add_argument('--base-url', default='http://127.0.0.1:8080/api/v1')
args = parser.parse_args()
base = args.base_url.rstrip('/')
if urlparse(base).hostname not in {'127.0.0.1', 'localhost', '::1'}:
    raise SystemExit('This synthetic smoke script only targets loopback hosts.')
checks = 0

def request(method, path, data=None, token=None, expected=200):
    global checks
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(base + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read()
    if status != expected:
        raise AssertionError(f'{method} {path}: expected {expected}, got {status}')
    checks += 1
    return json.loads(raw) if raw else None

request('GET', '/health')
owner = request('POST', '/trips', {'destination': {'name': 'Smoke test destination'}}, expected=201)
trip_id, owner_token = owner['trip']['id'], owner['accessToken']
path = '/trips/' + trip_id
request('GET', path, expected=401)
request('POST', path + '/invites', {'consent': False, 'role': 'COMPANION'}, owner_token, expected=400)
invite = request('POST', path + '/invites', {'consent': True, 'role': 'COMPANION'}, owner_token, expected=201)
companion = request('POST', '/invites/join', {'code': invite['code'], 'displayName': 'Smoke companion', 'consent': True}, expected=201)
assert companion['role'] == 'COMPANION'
companion_token = companion['accessToken']
request('POST', path + '/invites', {'consent': True, 'role': 'DRIVER'}, companion_token, expected=403)
request('PUT', path + '/readiness', {'ready': True}, companion_token)
observed = dt.datetime.now(dt.timezone.utc).isoformat()
location = {'latitude': 37.0, 'longitude': 127.0, 'accuracyMeters': 10.0, 'observedAt': observed, 'source': 'SIMULATED'}
request('PUT', path + '/location', location, owner_token)
visible = request('GET', path, token=companion_token)
assert visible['location']['source'] == 'SIMULATED'
assert any(p['ready'] for p in visible['participants'])
proposal = {'requestId': str(uuid.uuid4()), 'place': {'name': 'Smoke pickup alternative'}}
first = request('POST', path + '/proposals', proposal, companion_token, expected=201)
retry = request('POST', path + '/proposals', proposal, companion_token, expected=201)
assert len(first['proposals']) == len(retry['proposals']) == 1
p = first['proposals'][0]
p_path = path + '/proposals/' + p['id']
accepted = request('POST', p_path + '/decision', {'decision': 'ACCEPT', 'expectedVersion': p['version']}, owner_token)
assert accepted['proposals'][0]['status'] == 'APPLY_REQUESTED'
done = request('POST', p_path + '/navigation-result', {'outcome': 'DEMO_APPLIED', 'reason': 'Explicit local smoke simulation'}, owner_token)
assert done['proposals'][0]['status'] == 'DEMO_APPLIED'
request('POST', path + '/sharing/revoke', {}, owner_token)
request('GET', path, token=companion_token, expected=401)
ended = request('POST', path + '/end', {}, owner_token)
assert ended['status'] == 'ENDED'
print(f'PASS: {checks} local HTTP checks; no real SDK/location operation performed.')
