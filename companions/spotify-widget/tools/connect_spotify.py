#!/usr/bin/env python3
"""One-time Spotify PKCE sign-in. Tokens are sent through stdin directly to the protected TV provider."""
import argparse
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, HTTPServer
import ipaddress
import json
from pathlib import Path
import re
import secrets
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

SCOPES = ('playlist-read-private', 'playlist-read-collaborative', 'user-read-recently-played',
          'user-read-playback-state', 'user-library-read', 'user-read-playback-position',
          'user-modify-playback-state')
AUTHORITY = 'com.daviddemri.tv.spotify.setup'


def challenge(verifier):
    return base64.urlsafe_b64encode(hashlib.sha256(verifier.encode('ascii')).digest()).decode().rstrip('=')


def client_id(value):
    if not re.fullmatch(r'[a-fA-F0-9]{32}', value):
        raise argparse.ArgumentTypeError('Client ID must be 32 hexadecimal characters.')
    return value.lower()


def endpoint(value):
    match = re.fullmatch(r'(\[[^]]+\]|[0-9.]+):(\d+)', value)
    if not match:
        raise argparse.ArgumentTypeError('Use a literal TV IP:port.')
    try:
        ipaddress.ip_address(match[1].strip('[]'))
        assert 1 <= int(match[2]) <= 65535
    except (ValueError, AssertionError):
        raise argparse.ArgumentTypeError('Invalid TV IP or port.') from None
    return value


def callback_result(target, expected_state):
    if len(target) > 8192:
        raise ValueError('Invalid callback')
    parts = urllib.parse.urlsplit(target)
    if parts.path != '/callback' or parts.scheme or parts.netloc or parts.fragment:
        raise ValueError('Invalid callback')
    fields = urllib.parse.parse_qs(parts.query, keep_blank_values=True, strict_parsing=True)
    if any(len(fields.get(key, [])) > 1 for key in ('state', 'code', 'error')):
        raise ValueError('Invalid callback')
    state = fields.get('state', [''])[0]
    if not state or not secrets.compare_digest(state, expected_state):
        raise ValueError('Invalid callback state')
    if 'error' in fields:
        if 'code' in fields:
            raise ValueError('Ambiguous callback')
        return None
    code = fields.get('code', [''])[0]
    if not code or len(code) > 4096 or any(ord(c) < 32 for c in code):
        raise ValueError('Invalid authorization code')
    return code


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, newurl):
        return None


def exchange(client, verifier, code, redirect):
    body = urllib.parse.urlencode({'grant_type': 'authorization_code', 'client_id': client,
                                  'code_verifier': verifier, 'code': code, 'redirect_uri': redirect}).encode()
    request = urllib.request.Request('https://accounts.spotify.com/api/token', data=body,
                                     headers={'Content-Type': 'application/x-www-form-urlencoded', 'Accept': 'application/json'})
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=20) as response:
            data = response.read(32769)
    except urllib.error.HTTPError as failure:
        raise RuntimeError('Spotify declined the token exchange (HTTP %d).' % failure.code) from None
    except urllib.error.URLError:
        raise RuntimeError('Spotify token service could not be reached.') from None
    if len(data) > 32768:
        raise RuntimeError('Spotify returned an oversized response.')
    try:
        token = json.loads(data)
        access, refresh = token['access_token'], token['refresh_token']
        granted = set(token['scope'].split())
        if not set(SCOPES) <= granted or not 0 < int(token['expires_in']) <= 86400:
            raise ValueError()
        if not all(isinstance(v, str) and 0 < len(v) <= 4096 and not any(ord(c) < 32 for c in v) for v in (access, refresh)):
            raise ValueError()
        return {'client_id': client, 'access_token': access, 'refresh_token': refresh,
                'expires_in': int(token['expires_in']), 'scope': token['scope']}
    except (KeyError, TypeError, ValueError):
        raise RuntimeError('Spotify did not return the required library and playback authorization.') from None


def provision(adb, target, payload):
    provision_id = secrets.token_hex(16)
    payload = dict(payload, provision_id=provision_id)
    command = [adb, '-s', target, 'shell', 'content', 'write', '--uri', 'content://' + AUTHORITY + '/auth']
    result = subprocess.run(command, input=json.dumps(payload).encode(), capture_output=True, timeout=30)
    if result.returncode or b'Exception' in result.stderr + result.stdout:
        raise RuntimeError('The TV rejected the protected authorization transfer.')
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        result = subprocess.run([adb, '-s', target, 'shell', 'content', 'query', '--uri',
                                 'content://' + AUTHORITY + '/status'], capture_output=True, text=True, timeout=15)
        try:
            status = json.loads(result.stdout.split('status=', 1)[1].strip())
        except (IndexError, ValueError):
            status = {}
        if status.get('provision_id') == provision_id:
            if status.get('connected') and status.get('client_id') == payload['client_id'] and not status.get('provisioning_error'):
                return
            raise RuntimeError('The TV could not save this Spotify authorization.')
        time.sleep(.2)
    raise RuntimeError('The TV did not confirm authorization in time.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-id', required=True, type=client_id)
    parser.add_argument('--endpoint', required=True, type=endpoint)
    parser.add_argument('--adb', default='adb', help='Path to adb (defaults to adb on PATH).')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--authorization-url-file', type=Path,
                        help='Optional non-secret authorization URL file for the setup browser. Never contains tokens.')
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error('Use a loopback port between 1024 and 65535.')
    verifier, state = secrets.token_urlsafe(64), secrets.token_urlsafe(32)
    redirect = 'http://127.0.0.1:%d/callback' % args.port
    result = {}

    class Callback(BaseHTTPRequestHandler):
        def log_message(self, *unused):
            pass  # Callback paths contain authorization codes and must never be logged.

        def do_GET(self):
            try:
                code = callback_result(self.path, state)
            except ValueError:
                self.send_response(400)
                self.end_headers()
                self.wfile.write(b'Invalid authorization callback.')
                return
            result['code'] = code
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Referrer-Policy', 'no-referrer')
            self.end_headers()
            self.wfile.write(b'<!doctype html><script>history.replaceState(null,"","/connected")</script><title>Spotify authorization received</title>'
                             b'<h1>Authorization received</h1><p>You can return to the Spotify panel setup.</p>')

    server = HTTPServer(('127.0.0.1', args.port), Callback)
    server.timeout = .5
    parameters = {'response_type': 'code', 'client_id': args.client_id, 'redirect_uri': redirect,
                  'scope': ' '.join(SCOPES), 'state': state,
                  'code_challenge_method': 'S256', 'code_challenge': challenge(verifier)}
    url = 'https://accounts.spotify.com/authorize?' + urllib.parse.urlencode(parameters)
    if args.authorization_url_file:
        args.authorization_url_file.write_text(url + '\n')
    print('Registered redirect must be: ' + redirect, flush=True)
    print('Open this Spotify authorization URL:\n' + url, flush=True)
    print('Waiting for library and playback authorization; signing in does not start playback.', flush=True)
    deadline = time.monotonic() + 600
    try:
        while 'code' not in result and time.monotonic() < deadline:
            server.handle_request()
    finally:
        server.server_close()
    if 'code' not in result:
        raise RuntimeError('Spotify sign-in timed out. Run setup again when ready.')
    if result['code'] is None:
        raise RuntimeError('Spotify authorization was declined.')
    payload = exchange(args.client_id, verifier, result.pop('code'), redirect)
    provision(args.adb, args.endpoint, payload)
    print('Spotify connected on the TV. Tokens were not saved on this computer.', flush=True)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print('Spotify setup canceled.')
        raise SystemExit(130)
    except Exception as failure:
        # All raised messages are generic; do not expose subprocess responses or token bodies.
        print(str(failure) if isinstance(failure, RuntimeError) else 'Spotify setup failed. Check the connection and try again.')
        raise SystemExit(1)
