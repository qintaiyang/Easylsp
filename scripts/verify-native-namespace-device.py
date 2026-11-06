#!/usr/bin/env python3
"""Two independent class-loader namespaces in the controlled Demo process."""
import argparse, json, re, shlex, subprocess, zipfile
from pathlib import Path
p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, required=True)
p.add_argument('--claimed', action='store_true', help='A Zygisk plugin already owns the process')
a = p.parse_args(); a.output.mkdir(parents=True, exist_ok=True)
def adb(*args): return subprocess.check_output(['adb', *args], text=True, stderr=subprocess.STDOUT)
def root(command): return adb('shell', 'su -c ' + shlex.quote(command))
package = 'io.github.easylsp.demo'
info = adb('shell', 'pm', 'list', 'packages', '-U', package)
uid = int(re.search(r'uid:(\d+)', info).group(1))
base = '/data/user/0/' + package + '/files/namespace'
root('am force-stop ' + package)
with zipfile.ZipFile('demo-target/build/outputs/apk/debug/demo-target-debug.apk') as apk:
    (a.output / 'libdemo_namespace.so').write_bytes(apk.read('lib/arm64-v8a/libdemo_namespace.so'))
libs = [a.output / 'libdemo_namespace.so'] + [Path('native-runtime/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a') / name for name in ['libshadowhook.so', 'libeasylsp_native.so']]
try:
    for index in range(2):
        directory = base + '/' + str(index)
        root('mkdir -p ' + directory)
        for lib in libs:
            adb('push', str(lib), '/data/local/tmp/' + lib.name)
            root('cp /data/local/tmp/' + lib.name + ' ' + directory + '/' + lib.name)
        root(f'chown -R {uid}:{uid} {base}; chmod -R 700 {base}; restorecon -RF {base}')
    root(f'echo native-plugin-namespace > /data/user/0/{package}/files/startup-fault.txt; chown {uid}:{uid} /data/user/0/{package}/files/startup-fault.txt; restorecon -F /data/user/0/{package}/files/startup-fault.txt')
    launch = adb('shell', 'am', 'start', '-W', '-n', package + '/.DemoActivity')
    pid = adb('shell', 'pidof', package).strip()
    logs = adb('logcat', '-d', '-v', 'raw', '--pid=' + pid, '-s', 'EasylspDemoNative:I', 'AndroidRuntime:E', '*:S')
    (a.output / 'namespace.log').write_text(launch + '\n' + logs)
    results = [int(value) for value in re.findall(r'namespace loader=\d+ result=(-?\d+)', logs)]
    assert results == ([-8, -8] if a.claimed else [0, -8]), logs
    pointers = re.findall(r'namespace directory=\S+ core=(\S+) claim=(\S+)', logs)
    assert len(pointers) == 2 and pointers[0] != pointers[1], logs
    (a.output / 'results.json').write_text(json.dumps(dict(pid=int(pid), claimed=a.claimed, results=results, pointers=pointers), indent=2) + '\n')
finally:
    root('am force-stop ' + package + '; rm -rf ' + base)
