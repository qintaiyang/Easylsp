#!/usr/bin/env python3
"""Run manually AFTER waking, installing the host and rebooting. Never invoked by build/CI."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import time
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--provider', required=True, help='Provider name AND exact release, recorded as operator-supplied evidence')
parser.add_argument('--suite', choices=['ab', 'hooks', 'isolation'], default='ab', help='A/B smoke, Hook cases, or live-generation/child-scope isolation')
parser.add_argument('--serial')
parser.add_argument('--install-apks', action='store_true', help='Explicitly install/update the prepared debug app and Demo')
parser.add_argument('--bundle', type=Path, default=Path('build/zygisk-native-ready'), help='Prepared artifact directory; use . when running inside the unpacked bundle')
parser.add_argument('--output', type=Path, default=Path('build/device-acceptance'))
args = parser.parse_args()
repo = Path(__file__).resolve().parents[1]
args.output.mkdir(parents=True, exist_ok=True)
adb = ['adb'] + (['-s', args.serial] if args.serial else [])
package = 'io.github.easylsp.demo'
ctl = '/data/adb/modules/easylsp_native_host/bin/easylsp-hostctl'
stage = '/data/local/tmp/easylsp-native-acceptance'

def command(*values):
    return subprocess.check_output([*adb, *values], text=True, stderr=subprocess.STDOUT)

def root(command_text):
    return command('shell', 'su -c ' + shlex.quote(command_text))

def shell(*values):
    return command('shell', shlex.join(values))

if args.install_apks:
    for name in ['app-debug.apk', 'demo-target-debug.apk']:
        print(command('install', '-r', str(args.bundle / 'apk' / name)))
status = json.loads(root(shlex.join([ctl, 'status'])))
environment = dict(provider=args.provider, host=status, fingerprint=shell('getprop', 'ro.build.fingerprint').strip(), pageSize=shell('getconf', 'PAGESIZE').strip(), sdk=shell('getprop', 'ro.build.version.sdk').strip(), selinux=shell('getenforce').strip(), usapPoolEnabled=shell('getprop', 'dalvik.vm.usap_pool_enabled').strip())
(args.output / 'environment.json').write_text(json.dumps(environment, indent=2) + '\n')
if any(row['packageName'] == package for row in status['deployments']):
    raise SystemExit('Demo already has a published snapshot. Preserve/disable it manually before this acceptance run.')
uid = int(re.search(r'uid:(\d+)', shell('pm', 'list', 'packages', '-U', package)).group(1))
apk = shell('pm', 'path', package).splitlines()[0].removeprefix('package:')
version = int(re.search(r'versionCode=(\d+)', shell('dumpsys', 'package', package)).group(1))
shell('mkdir', '-p', stage)

def launch(label, expected, mode='native-plugin'):
    # Read continuously: this ROM can overwrite its main log buffer in under a second.
    capture = args.output / (label + '-capture.log')
    with capture.open('w') as output:
        reader = subprocess.Popen([*adb, 'logcat', '-v', 'threadtime', '-T', '1', '-s', 'EasylspHost:I', 'EasylspNative:I', 'EasylspDemoNative:I', 'EasylspDemoFault:I', '*:S'], stdout=output, stderr=subprocess.STDOUT)
        try:
            shell('am', 'force-stop', package)
            command('shell', 'run-as ' + package + ' sh -c ' + shlex.quote('mkdir -p files; printf %s ' + shlex.quote(mode) + ' > files/startup-fault.txt'))
            shell('am', 'start', '-W', '-n', package + '/.DemoActivity')
            pid = shell('pidof', package).strip().split()[0]
            start_ticks = shell('cat', '/proc/' + pid + '/stat').rsplit(') ', 1)[1].split()[19]
            time.sleep(0.5)
        finally:
            reader.terminate(); reader.wait()
    (args.output / (label + '-process.json')).write_text(json.dumps(dict(pid=int(pid), startTicks=int(start_ticks), expectedResult=expected), indent=2) + '\n')
    logs = '\n'.join(line for line in capture.read_text().splitlines() if re.match(r'\S+\s+\S+\s+' + re.escape(pid) + r'\s', line)) + '\n'
    (args.output / (label + '.log')).write_text(logs)
    if 'constructor result=' + str(expected) not in logs or 'JNI_OnLoad constructor_result=' + str(expected) not in logs:
        raise RuntimeError(f'{label}: expected constructor/JNI_OnLoad result {expected}; inspect saved logs')
    return logs

cases = [('A', 'early-symbol-A', 1010, 'native-plugin'), ('B', 'early-symbol-B', 2010, 'native-plugin')] if args.suite in ['ab', 'isolation'] else [
    ('vaddr', 'elf-vaddr', 1010, 'native-plugin'),
    ('jni', 'jni-onload', 8, 'native-plugin'),
    ('symbol-reload', 'early-symbol-A', 1010, 'native-plugin-lifecycle'),
    ('lifecycle', 'module-lifecycle', 8, 'native-plugin-lifecycle'),
]
results = []
old_process = None
try:
    for label, candidate, expected, mode in cases:
        plugin_id = 'early-symbol' if candidate.startswith('early-symbol') else candidate
        build = 'candidate-' + label + '-' + str(uuid.uuid4())
        remote = stage + '/input/' + plugin_id + '/' + build
        root(shlex.join(['mkdir', '-p', remote]))
        command('push', str(args.bundle / 'native-candidates' / candidate / 'plugin.so'), stage + '/candidate.so')
        root(shlex.join(['cp', stage + '/candidate.so', remote + '/plugin.so']))
        generation = str(uuid.uuid4())
        snapshot = dict(schema=1, hostProtocolVersion=1, generation=generation, publishRequestId=str(uuid.uuid4()), backend='ZYGISK', userId=uid // 100000, uid=uid, packageName=package, targetVersionCode=version, targetInstallIdentity=apk, policy='NATIVE_ONLY', orderedPlugins=[dict(localPluginId=plugin_id, buildId=build, sourceRevision=1, processScope='main', processName=package, config={})])
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'snapshot.json'; source.write_text(json.dumps(snapshot))
            command('push', str(source), stage + '/snapshot.json')
        receipt = root(shlex.join([ctl, 'publish', stage + '/snapshot.json', stage + '/input']))
        (args.output / (label + '-receipt.json')).write_text(receipt)
        if old_process is not None:
            pid, ticks, old_build = old_process
            current_ticks = shell('cat', '/proc/' + pid + '/stat').rsplit(') ', 1)[1].split()[19]
            maps = root('cat /proc/' + pid + '/maps')
            own_maps = '\n'.join(line for line in maps.splitlines() if '/easylsp/artifacts/' in line)
            (args.output / 'old-process-after-B.txt').write_text(own_maps + '\n')
            if current_ticks != ticks or old_build not in own_maps or build in own_maps:
                raise RuntimeError('Publishing B must preserve process A and its original mapping')
            results.append(dict(case='old-process-retains-A', passed=True, pid=int(pid), startTicks=int(ticks)))
        logs = launch(label, expected, mode)
        if 'phase=pre_configured code=0 ' + generation not in logs or 'phase=engine_initialized code=0' not in logs or 'phase=core_loaded code=0' not in logs or 'phase=plugin_result code=0 ' + plugin_id not in logs:
            raise RuntimeError(label + ': missing host generation/core/plugin evidence')
        if label == 'jni':
            markers = ['JNI_OnLoad wrapper before original', 'JNI_OnLoad constructor_result=8', 'JNI_OnLoad wrapper after original']
            positions = [logs.index(marker) for marker in markers]
            if positions != sorted(positions):
                raise RuntimeError('JNI wrapper did not surround original')
        if mode == 'native-plugin-lifecycle':
            for cycle in [1, 2]:
                if f'cycle={cycle} value={expected} JNI=10006' not in logs or f'cycle={cycle} closed' not in logs:
                    raise RuntimeError(label + ': missing real dlopen/dlclose cycle')
            phase = 'hook_installed' if label == 'symbol-reload' else 'module_before_constructors'
            ids = re.findall(r'"phase":"' + phase + r'".*?loadId=(\d+)', logs)
            if len(ids) != 2 or len(set(ids)) != 2:
                raise RuntimeError(label + ': reload must receive a new loadId')
            end_phase = 'hook_unbound' if label == 'symbol-reload' else 'module_before_unload'
            if logs.count('"phase":"' + end_phase + '"') != 2:
                raise RuntimeError(label + ': missing unload callbacks')
        results.append(dict(case=label, passed=True, generation=generation, expectedResult=expected))
        if args.suite == 'isolation' and label == 'A':
            pid = shell('pidof', package).strip().split()[0]
            ticks = shell('cat', '/proc/' + pid + '/stat').rsplit(') ', 1)[1].split()[19]
            old_process = (pid, ticks, build)
            # The phone may be locked; grant only a ten-second test exemption.
            root(shlex.join(['cmd', 'deviceidle', 'tempwhitelist', '-d', '10000', package]))
            service = root(shlex.join(['am', 'startservice', '-n', package + '/.DemoProcessService']))
            if 'Error:' in service:
                raise RuntimeError(service)
            time.sleep(0.5)  # startservice returns before the new process attaches.
            worker = shell('pidof', package + ':worker').strip()
            maps = root('cat /proc/' + worker + '/maps')
            if 'libeasylsp_native.so' in maps or '/easylsp/artifacts/' in maps:
                raise RuntimeError('Main-process scope loaded Core/plugin in worker')
            manager = subprocess.run([*adb, 'shell', 'pidof', 'io.github.easylsp'], text=True, capture_output=True)
            if manager.returncode != 1 or manager.stdout.strip():
                raise RuntimeError('This isolation case requires the manager APK process to be stopped')
            results.append(dict(case='worker-excluded-manager-stopped', passed=True, workerPid=int(worker)))
    root(shlex.join([ctl, 'disable', str(uid), package]))
    logs = launch('disabled', 8)
    if 'phase=core_loaded' in logs or '"phase":"plugin_initialized"' in logs:
        raise RuntimeError('disabled target still loaded Core/plugin')
    results.append(dict(case='disabled', passed=True, expectedResult=8))
    (args.output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
    print(args.suite + ' suite and disabled behavior passed. Evidence: ' + str(args.output))
finally:
    root(shlex.join([ctl, 'disable', str(uid), package]))
    shell('am', 'force-stop', package)
