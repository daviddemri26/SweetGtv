import argparse
import unittest
from connect_spotify import callback_result, challenge, client_id, endpoint

class SpotifyConnectTests(unittest.TestCase):
    def test_rfc7636_challenge(self):
        self.assertEqual(challenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk'), 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM')
    def test_callback_state_and_single_code(self):
        self.assertEqual(callback_result('/callback?state=known&code=abc', 'known'), 'abc')
        for path in ['/callback?state=wrong&code=abc','/callback?state=known&code=a&code=b',
                     '/callback?state=known&code=', '/callback?state=known&error=x&code=y',
                     '/else?state=known&code=abc','https://else/callback?state=known&code=abc']:
            with self.subTest(path=path), self.assertRaises(ValueError): callback_result(path,'known')
    def test_spotify_tracking_parameters_do_not_invalidate_a_valid_callback(self):
        self.assertEqual(callback_result('/callback?state=known&code=abc&ubi=tracking', 'known'), 'abc')

    def test_declining_is_not_an_auth_code(self):
        self.assertIsNone(callback_result('/callback?state=known&error=access_denied', 'known'))
    def test_client_id_is_public_hex_only(self):
        self.assertEqual(client_id('A'*32), 'a'*32)
        for value in ['a'*31, 'g'*32, 'a'*32+';x']:
            with self.assertRaises(argparse.ArgumentTypeError): client_id(value)
    def test_adb_target_cannot_be_a_shell_expression(self):
        self.assertEqual(endpoint('192.0.2.55:12345'), '192.0.2.55:12345')
        self.assertEqual(endpoint('[::1]:5555'), '[::1]:5555')
        for value in ['host:5555','192.0.2.55:0','192.0.2.55:65536','192.0.2.55:5;echo x']:
            with self.assertRaises(argparse.ArgumentTypeError): endpoint(value)

if __name__ == '__main__': unittest.main()
