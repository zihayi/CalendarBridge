import hashlib
import json
import secrets
import socket
import unittest
import test_bridge
from pairing import verification_code
from discovery import DiscoveryServer


class PairingTests(unittest.TestCase):
    setUp = test_bridge.BridgeTests.setUp
    tearDown = test_bridge.BridgeTests.tearDown
    call = test_bridge.BridgeTests.call

    def begin(self, secret=None):
        self.server.discovery_available = True
        self.assertEqual(self.call('/desktop/pairing/open', {}, admin=True)[0], 200)
        secret = secret or secrets.token_hex(32)
        request = {'clientId': 'fixture-phone', 'device': 'Mock Android', 'commitment': hashlib.sha256(secret.encode()).hexdigest()}
        code, response = self.call('/pair/begin', request, token='not-an-access-token')
        self.assertEqual(code, 200)
        return secret, request, response

    def test_approval_required_and_roles_are_separate(self):
        self.assertEqual(self.call('/desktop/pairing/open', {})[0], 401)
        secret, _, challenge = self.begin()
        proof = {'id': challenge['id'], 'secret': secret}
        self.assertEqual(self.call('/pair/reveal', proof)[1]['state'], 'waiting')
        self.assertNotIn('token', self.call('/pair/finish', proof)[1])
        status = self.call('/desktop/pairing', admin=True)[1]
        expected = verification_code(challenge['id'], self.server.pairing.fingerprint, challenge['nonce'], secret)
        self.assertEqual(status['requests'][0]['code'], expected)
        self.assertEqual(self.call('/desktop/pairing/approve', {'id': challenge['id']}, admin=True)[0], 200)
        result = self.call('/pair/finish', proof)[1]
        self.assertEqual(result['token'], self.config['token'])
        self.assertNotIn('adminToken', result)
        self.assertEqual(self.call('/desktop/pairing', admin=True)[1]['enabledUntil'], 0)

    def test_commitment_cannot_change_and_certificate_is_bound_to_code(self):
        secret, request, challenge = self.begin('1' * 64)
        self.assertEqual(self.call('/pair/begin', request)[1]['nonce'], challenge['nonce'])
        self.assertEqual(self.call('/pair/reveal', {'id': challenge['id'], 'secret': '2' * 64})[0], 400)
        correct = verification_code(challenge['id'], self.server.pairing.fingerprint, challenge['nonce'], secret)
        impostor = verification_code(challenge['id'], '0' * 64, challenge['nonce'], secret)
        self.assertNotEqual(correct, impostor)
        self.assertNotIn('code', challenge)

    def test_rejection_expiry_and_wrong_proof_never_release_token(self):
        secret, _, challenge = self.begin()
        proof = {'id': challenge['id'], 'secret': secret}
        self.call('/pair/reveal', proof)
        self.call('/desktop/pairing/reject', {'id': challenge['id']}, admin=True)
        self.assertEqual(self.call('/pair/finish', proof)[1]['state'], 'rejected')
        self.assertEqual(self.call('/pair/finish', dict(proof, secret='0' * 64))[0], 400)
        self.server.pairing.requests[challenge['id']]['expires'] = 0
        self.assertEqual(self.call('/pair/finish', proof)[0], 400)

    def test_closed_window_and_bounded_requests(self):
        request = {'clientId': 'fixture-phone', 'commitment': 'a' * 64}
        self.assertEqual(self.call('/pair/begin', request)[0], 400)
        self.begin()
        for index in range(7):
            request['commitment'] = hashlib.sha256(str(index).encode()).hexdigest()
            self.assertEqual(self.call('/pair/begin', request)[0], 200)
        request['commitment'] = 'b' * 64
        self.assertEqual(self.call('/pair/begin', request)[0], 400)

    def test_pairing_cannot_replace_an_existing_device_binding(self):
        self.server.commands.register_device({'clientId': 'existing-phone', 'device': 'Existing mock phone', 'canWrite': False})
        self.server.discovery_available = True
        self.call('/desktop/pairing/open', {}, admin=True)
        request = {'clientId': 'different-phone', 'commitment': 'a' * 64}
        self.assertEqual(self.call('/pair/begin', request)[0], 400)
        self.assertEqual(self.server.commands.bound_client(), 'existing-phone')

    def test_discovery_roundtrip_is_nonce_bound_and_contains_no_credentials(self):
        discovery = DiscoveryServer(self.server.server_port, self.server.pairing.fingerprint, self.server.pairing, bind_host='127.0.0.1', port=0)
        try:
            request = {'type': 'calendarbridge-discover', 'version': 1, 'nonce': secrets.token_hex(16)}
            packet = json.dumps(request).encode()
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
                client.settimeout(3)
                client.sendto(packet, ('127.0.0.1', discovery.port))
                result = json.loads(client.recv(4096))
            self.assertEqual(result['nonce'], request['nonce'])
            self.assertEqual(result['port'], self.server.server_port)
            self.assertEqual(result['certificateSha256'], self.server.pairing.fingerprint)
            self.assertNotIn(self.config['token'], json.dumps(result))
            self.assertNotIn(self.config['adminToken'], json.dumps(result))
            self.assertIsNone(discovery.response(packet, '8.8.8.8'))
            self.assertIsNone(discovery.response(b'x' * 513, '192.168.1.2'))
            self.assertIsNone(discovery.response(b'{}', '192.168.1.2'))
        finally:
            discovery.close()
