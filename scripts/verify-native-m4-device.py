#!/usr/bin/env python3
"""Controlled Demo tests for chain ordering, recursion/concurrency, scoped JNI and intercept."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import time
import uuid

parser = argparse.ArgumentParser(); parser.add_argument('--serial'); parser.add_argument('--bundle', type=Path, default=Path('build/zygisk-native-ready')); parser.add_argument('--output', type=Path, default=Path('build/device-acceptance/m4'))
parser.add_argument('--case', action='append', dest='selected_cases', help='Run only selected cases after a targeted fix')
args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
adb = ['adb'] + (['-s', args.serial] if args.serial else [])
package = 'io.github.easylsp.demo'; ctl = '/data/adb/modules/easylsp_native_host/bin/easylsp-hostctl'; stage = '/data/local/tmp/easylsp-m4'
def command(*values): return subprocess.check_output([*adb, *values], text=True, stderr=subprocess.STDOUT)
def root(text): return command('shell', 'su -c ' + shlex.quote(text))
def shell(*values): return command('shell', shlex.join(values))
status = json.loads(root(shlex.join([ctl, 'status'])))
if any(p['packageName'] == package for p in status['deployments']): raise SystemExit('Preserve/disable existing Demo publication first')
uid = int(re.search(r'uid:(\d+)', shell('pm', 'list', 'packages', '-U', package)).group(1))
apk = shell('pm', 'path', package).splitlines()[0].removeprefix('package:')
version = int(re.search(r'versionCode=(\d+)', shell('dumpsys', 'package', package)).group(1))
shell('mkdir', '-p', stage)
results = []
cases = [('AB', ['chain-a','chain-b'], 1280, 188), ('BA', ['chain-b','chain-a'], 10240, 338), ('only-A', ['chain-a'], 1010, 47), ('only-B', ['chain-b'], 220, 38), ('exclusive-conflict', ['chain-a','early-symbol-A'], 1010, 47), ('intercept', ['instruction-intercept'], 8, 7), ('jni-register', ['jni-register'], None, None), ('legacy-v1', ['legacy-v1'], 1010, 7), ('disabled', [], 8, 7), ('jni-baseline', [], None, None), ('jni-AB', ['jni-chain-a','jni-chain-b'], None, None), ('jni-BA', ['jni-chain-b','jni-chain-a'], None, None), ('cpp-AB', ['cpp-a','cpp-b'], 7008, 7)]
if args.selected_cases: cases = [case for case in cases if case[0] in args.selected_cases]
try:
 for label, names, expected, recursive in cases:
    plugins = []
    for index, name in enumerate(names):
        build = str(uuid.uuid4()); plugin = name
        destination = stage + '/artifacts/' + plugin + '/' + build
        root(shlex.join(['mkdir','-p',destination]))
        command('push',str(args.bundle/'native-candidates'/name/'plugin.so'),stage+'/plugin.so')
        root(shlex.join(['cp',stage+'/plugin.so',destination+'/plugin.so']))
        plugins.append(dict(localPluginId=plugin,buildId=build,sourceRevision=1,processScope='main',processName=package,config={}))
    generation = str(uuid.uuid4())
    snapshot = dict(schema=1,hostProtocolVersion=1,generation=generation,publishRequestId=generation,backend='ZYGISK',userId=uid//100000,uid=uid,packageName=package,targetVersionCode=version,targetInstallIdentity=apk,policy='NATIVE_ONLY',orderedPlugins=plugins)
    if plugins:
        with tempfile.TemporaryDirectory() as directory:
            file=Path(directory)/'snapshot.json'; file.write_text(json.dumps(snapshot)); command('push',str(file),stage+'/snapshot.json')
        receipt=root(shlex.join([ctl,'publish',stage+'/snapshot.json',stage+'/artifacts']))
    else: receipt=root(shlex.join([ctl,'disable',str(uid),package]))
    (args.output/(label+'-receipt.json')).write_text(receipt)
    mode = 'native-plugin-jni-register' if label.startswith('jni-') else 'native-plugin-m4'
    capture = args.output/(label+'.log')
    with capture.open('w') as output:
        reader=subprocess.Popen([*adb,'logcat','-v','threadtime','-T','1','-s','EasylspHost:I','EasylspNative:I','EasylspDemoNative:I','EasylspDemoFault:I','AndroidRuntime:E','*:S'],stdout=output,stderr=subprocess.STDOUT)
        try:
            shell('am','force-stop',package)
            command('shell','run-as '+package+' sh -c '+shlex.quote('mkdir -p files; printf %s '+shlex.quote(mode)+' > files/startup-fault.txt'))
            shell('am','start','-W','-n',package+'/.DemoActivity'); time.sleep(1)
        finally: reader.terminate(); reader.wait()
    pid = int(shell('pidof', package).strip())
    logs='\n'.join(line for line in capture.read_text().splitlines() if re.match(r'\S+\s+\S+\s+' + str(pid) + r'\s', line))
    capture.write_text(logs+'\n')
    if label.startswith('jni-'):
        value = {'jni-register': 1010, 'jni-baseline': 8, 'jni-AB': 1130, 'jni-BA': 10130}[label]
        markers=[f'JNI dynamic primary={value} overload=5','JNI isolated loader=0 code=0 value=8','JNI isolated loader=1 code=0 value=8','JNI failure pending exception preserved code=-1',f'JNI failed registration=-1 retained={value}',f'JNI unregister=0 missing=true reregister=0 value={value}', f'JNI threads=4 errors=0 result={value}']
    else:
        markers=[f'constructor result={expected}',f'M4 threads=4 errors=0 result={expected} recursive={recursive}']
        if label=='intercept': markers+=['intercept integer=20 float=9.0']
        if label=='cpp-AB': markers+=['static STL']
        if label=='exclusive-conflict': markers+=['"phase":"hook_conflict"']
    for marker in markers:
        if marker not in logs: raise RuntimeError(label+': missing '+marker)
    events=json.loads(root(shlex.join([ctl,'events',str(uid)])))
    (args.output/(label+'-direct-events.json')).write_text(json.dumps(events,indent=2))
    if plugins and not any(e['pid'] == pid for e in events['events']): raise RuntimeError(label+': no direct process events')
    results.append(dict(case=label,passed=True,pid=pid,generation=generation,expected=expected,recursive=recursive,nsPerCall=re.findall(r'nsPerCall=([0-9.]+)',logs),hotspotNs={key:[float(value) for value in re.findall(key+r'=([0-9.]+)',logs)] for key in ['registerNs','callNs','integerNs','floatNs']}))
    (args.output/'results.json').write_text(json.dumps(results,indent=2)+'\n')
 (args.output/'results.json').write_text(json.dumps(results,indent=2)+'\n'); print('M4 controlled behavior tests passed: '+str(args.output))
finally:
 root(shlex.join([ctl,'disable',str(uid),package])); shell('am','force-stop',package)
