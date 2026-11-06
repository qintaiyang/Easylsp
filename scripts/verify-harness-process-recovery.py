#!/usr/bin/env python3
"""Actual Android SIGKILL, persistent root SDK, fixed bridge rebind, then HTTP cancel.

Requires installed debug/test APKs and the app's prepared Ubuntu/Harness runtime.
Uses a local controlled model; never reads the developer's model credentials.
"""
import argparse
import json
from pathlib import Path
import select
import shlex
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--output', default='/tmp/easylsp-phase3-process-recovery')
args = parser.parse_args()
adb = ['adb', '-s', args.serial]
prefix = Path(args.output)
release = threading.Event()
state = {'requests': 0, 'cancelledHttp': False}


class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        if self.path == '/release':
            release.set()
        body = json.dumps(state).encode()
        self.send_response(200)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        assert any(t['function']['name'] == 'mcp__easylsp__get_project_info' for t in request['tools'])
        state['requests'] += 1
        index = state['requests']
        if index == 2:
            assert release.wait(90), 'Recovery client did not release the model'
        if index == 4:
            ready, _, _ = select.select([self.connection], [], [], 30)
            state['cancelledHttp'] = bool(ready and self.connection.recv(1) == b'')
            return
        if index <= 2:
            delta = {'tool_calls': [{'index': 0, 'id': f'project-{index}', 'type': 'function', 'function': {
                'name': 'mcp__easylsp__get_project_info', 'arguments': '{}'}}]}
        else:
            delta = {'content': '进程恢复后的真实工具已完成'}
        def event(value, finish):
            return 'data: ' + json.dumps({'id': f'reply-{index}', 'model': 'fixture', 'choices': [
                {'index': 0, 'delta': value, 'finish_reason': finish}]}) + '\n\n'
        body = (event(delta, None) + event({}, 'tool_calls' if index <= 2 else 'stop') + 'data: [DONE]\n\n').encode()
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def root(command):
    return subprocess.check_output(adb + ['exec-out', 'su -c ' + shlex.quote(command)], text=True)


def instrument(method, output, extra=()):
    return subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        'io.github.easylsp.app.HarnessProcessRecoveryDeviceTest#' + method, *extra,
        'io.github.easylsp.test/androidx.test.runner.AndroidJUnitRunner'], stdout=output, stderr=subprocess.STDOUT)


server = ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
port = server.server_port
threading.Thread(target=server.serve_forever, daemon=True).start()
subprocess.run(adb + ['reverse', f'tcp:{port}', f'tcp:{port}'], check=True)
marker = '/data/user/0/io.github.easylsp/files/phase3-process-recovery-ready.json'
try:
    root('rm -f ' + marker)
    with prefix.with_suffix('.prepare.log').open('w') as log:
        prepare = instrument('prepareForExternalProcessKill', log, ['-e', 'fixturePort', str(port)])
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            raw = root('if test -f ' + marker + '; then cat ' + marker + '; fi')
            if raw:
                saved = json.loads(raw)
                break
            if prepare.poll() is not None:
                raise RuntimeError('Prepare failed; see ' + str(prefix.with_suffix('.prepare.log')))
            time.sleep(.25)
        else:
            raise TimeoutError('Android did not reach the recovery boundary')
        root('kill -9 ' + str(saved['androidPid']))
        prepare.wait(timeout=15)
    with prefix.with_suffix('.resume.log').open('w') as log:
        resume = instrument('reconnectFinishAndCancelWithoutReplayingInput', log)
        resume.wait(timeout=100)
    result_log = prefix.with_suffix('.resume.log').read_text()
    if 'OK (1 test)' not in result_log:
        raise RuntimeError(result_log)
    evidence = json.loads(root('cat /data/user/0/io.github.easylsp/files/phase3-process-recovery-evidence.json'))
    assert state == {'requests': 4, 'cancelledHttp': True}, state
    prefix.with_suffix('.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2))
    print(json.dumps(evidence, ensure_ascii=False))
finally:
    release.set()
    server.shutdown()
    subprocess.run(adb + ['reverse', '--remove', f'tcp:{port}'], check=True)
