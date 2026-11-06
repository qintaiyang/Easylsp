#!/usr/bin/env python3
"""Run host protocol and root transaction behavior without contacting a device."""
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest

repo = Path(__file__).resolve().parents[1]
host = repo / 'zygisk-host'

class HostProtocolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.directory = Path(cls.temp.name)
        common = ['cc', '-std=c17', '-Wall', '-Wextra', '-Werror', '-I' + str(host / 'src'), '-I' + str(host / 'third_party')]
        shared = [str(host / 'src/protocol.c'), str(host / 'third_party/cJSON.c')]
        cls.binary = cls.directory / 'hostctl'
        subprocess.run(common + [str(host / 'src/hostctl.c'), *shared, '-lm', '-o', str(cls.binary)], check=True)
        protocol = cls.directory / 'protocol-test'
        subprocess.run(common + [str(host / 'tests/protocol_test.c'), *shared, '-pthread', '-lm', '-o', str(protocol)], check=True)
        subprocess.run([str(protocol)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory(dir=self.directory)
        self.root = Path(self.workspace.name)
        self.store = self.root / 'store'
        self.source = self.root / 'input/plugin/build'
        self.source.mkdir(parents=True)
        # Hostctl only receives accepted artifacts; this fixture exercises the local
        # root-copy ELF header boundary, not runtime loading or Android compatibility.
        elf = bytearray(64)
        elf[:7] = b'\x7fELF\x02\x01\x01'
        struct.pack_into('<HH', elf, 16, 3, 183)
        (self.source / 'plugin.so').write_bytes(elf)
        self.snapshot = dict(schema=1, hostProtocolVersion=1, generation='A', publishRequestId='request-A', backend='ZYGISK', userId=0, uid=10123, packageName='io.github.demo', targetVersionCode=1, targetInstallIdentity='/data/app/demo/base.apk', policy='NATIVE_ONLY', orderedPlugins=[dict(localPluginId='plugin', buildId='build', processScope='main', processName='io.github.demo', sourceRevision=1, config={})])

    def tearDown(self):
        self.workspace.cleanup()

    def command(self, *arguments, succeeds=True):
        result = subprocess.run([str(self.binary), '--data-dir', str(self.store), *map(str, arguments)], text=True, capture_output=True)
        if succeeds:
            self.assertEqual(0, result.returncode, result.stderr)
            return json.loads(result.stdout)
        self.assertNotEqual(0, result.returncode)
        return result

    def publish(self, snapshot=None, succeeds=True):
        file = self.root / 'snapshot.json'
        file.write_text(json.dumps(snapshot or self.snapshot))
        return self.command('publish', file, self.root / 'input', succeeds=succeeds)

    @unittest.skipUnless(os.getuid() == 0, 'requires root')
    def test_native_target_gc_and_special_process_contract(self):
        target = dict(schema=1,targetId='controlled',generation='native-A',uid=0,packageName='io.github.demo',executable='/data/local/tmp/controlled',processName='',plugins=[dict(localPluginId='plugin',buildId='build',instanceId='one',sourceRevision=1,config={})])
        file=self.root/'native.json'; file.write_text(json.dumps(target))
        self.command('native-publish',file,self.root/'input')
        self.assertEqual('controlled',self.command('status')['nativeTargets'][0]['targetId'])
        self.assertTrue(self.command('gc')[0]['retained'])
        self.command('native-disable','controlled')
        self.assertTrue(self.command('gc')[0]['removed'])
        self.snapshot['orderedPlugins'][0].update(processType='isolated',processScope='all')
        self.publish(succeeds=False)
        self.snapshot['orderedPlugins'][0].update(processScope='exact')
        for process in ['com.google.android.webview:sandboxed_process0','io.github.demo','io.github.demo:']:
            self.snapshot['orderedPlugins'][0]['processName']=process
            self.publish(succeeds=False)
        self.snapshot['orderedPlugins'][0].update(processName='io.github.demo:isolated:service')
        self.assertEqual('A',self.publish()['generation'])
        self.snapshot.update(generation='bad',publishRequestId='bad',policy='NATIVE_AND_JAVA')
        self.publish(succeeds=False)

    @unittest.skipUnless(os.getuid() == 0, 'root CLI transaction tests require root (CI can use sudo)')
    def test_current_generation_and_request_receipts_are_distinct(self):
        self.assertEqual('A', self.publish()['generation'])
        old = self.snapshot.copy()
        self.snapshot.update(generation='B', publishRequestId='request-B')
        self.assertEqual('B', self.publish()['generation'])
        self.assertEqual('A', self.publish(old)['generation'])
        self.assertEqual('B', self.command('status')['deployments'][0]['generation'])
        self.command('disable', '10123', 'io.github.demo')
        self.assertEqual([], self.command('status')['deployments'])
        self.publish()
        self.assertEqual([], self.command('status')['deployments'])
        self.assertTrue(self.command('gc')[0]['retained'])

    @unittest.skipUnless(os.getuid() == 0, 'requires root')
    def test_rejected_input_does_not_replace_current(self):
        self.publish()
        bad = dict(self.snapshot, generation='broken', publishRequestId='broken', uid=99123)
        self.publish(bad, succeeds=False)
        self.assertEqual('A', self.command('status')['deployments'][0]['generation'])
        bad = dict(self.snapshot, generation='broken', publishRequestId='broken', orderedPlugins=[dict(self.snapshot['orderedPlugins'][0], buildId='../escape')])
        self.publish(bad, succeeds=False)
        self.assertEqual('A', self.command('status')['deployments'][0]['generation'])

    @unittest.skipUnless(os.getuid() == 0, 'requires root')
    def test_prune_keeps_current_and_gc_releases_disabled_history(self):
        self.publish()
        old_build = self.source
        self.source = self.root / 'input/plugin/new-build'
        self.source.mkdir(parents=True)
        (self.source / 'plugin.so').write_bytes((old_build / 'plugin.so').read_bytes())
        self.snapshot.update(generation='B', publishRequestId='request-B')
        self.snapshot['orderedPlugins'][0]['buildId'] = 'new-build'
        self.publish()
        self.assertEqual(1, self.command('prune-history')['removedReceipts'])
        rows = self.command('gc')
        self.assertTrue(next(row for row in rows if row['buildId'] == 'build')['removed'])
        self.assertTrue(next(row for row in rows if row['buildId'] == 'new-build')['retained'])
        self.command('disable', '10123', 'io.github.demo')
        self.assertEqual(1, self.command('prune-history')['removedReceipts'])
        self.assertTrue(self.command('gc')[0]['removed'])

    @unittest.skipUnless(os.getuid() == 0, 'requires root')
    def test_diagnostic_clear_is_bound_to_run_and_validates_external_mode(self):
        snapshot = dict(self.snapshot, orderedPlugins=[], diagnostic=dict(mode='ENTRY_ONLY', diagnosticRunId='run-A', processName='io.github.demo', expiresAt=1900000000000, expiresElapsed=9000000))
        file = self.root / 'diagnostic.json'; file.write_text(json.dumps(snapshot))
        self.command('diagnostic', file)
        saved = self.store / 'diagnostics/10123/io.github.demo.json'
        self.assertTrue(json.loads(saved.read_text())['bootId'])
        self.command('clear-diagnostic', '10123', 'io.github.demo', 'other-run')
        self.assertTrue(saved.exists())
        self.command('clear-diagnostic', '10123', 'io.github.demo', 'run-A')
        self.assertFalse(saved.exists())
        snapshot['diagnostic']['mode'] = 'unknown'; file.write_text(json.dumps(snapshot))
        self.command('diagnostic', file, succeeds=False)

    @unittest.skipUnless(os.getuid() == 0, 'requires root')
    def test_receipt_repaired_after_commit_interruption(self):
        self.publish()
        (self.store / 'history/request-A.json').unlink()
        self.snapshot.update(generation='B', publishRequestId='request-B')
        self.publish()
        self.assertEqual('A', json.loads((self.store / 'history/request-A.json').read_text())['generation'])

if __name__ == '__main__':
    unittest.main()
