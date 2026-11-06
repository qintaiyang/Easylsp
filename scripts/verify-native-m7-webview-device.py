#!/usr/bin/env python3
"""Verify plugin initialization in an explicitly named renderer, plus a completed WebView page."""
import argparse,json,re,shlex,subprocess,time,uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--process-name',required=True);p.add_argument('--target-package',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
adb=['adb','-s',a.serial];package=a.target_package;client='io.github.easylsp.demo';assert a.process_name.startswith(package+':');ctl='/data/adb/modules/easylsp_native_host/bin/easylsp-hostctl';stage='/data/local/tmp/easylsp-m7-webview';plugin='m7-'+uuid.uuid4().hex

def run(*args):return subprocess.run([*adb,*args],check=True,text=True,capture_output=True).stdout
def root(script):return run('shell','su -c '+shlex.quote(script))
def shell(*args):return run('shell',shlex.join(args))
def call(*args):return root(shlex.join([ctl,*args]))
assert not any(x['packageName']==package for x in json.loads(call('status'))['deployments'])
uid=int(re.search(r'uid:(\d+)',shell('pm','list','packages','-U',package)).group(1));apk=shell('pm','path',package).splitlines()[0].removeprefix('package:');version=int(re.search(r'versionCode=(\d+)',shell('dumpsys','package',package)).group(1))
root(f'mkdir -p {stage} && chown 2000:2000 {stage}')
run('push','app/build/generated/nativeAcceptanceAssets/fixed-native/early-symbol-A/plugin.so',stage+'/plugin.so')
root(f'mkdir -p {stage}/artifacts/{plugin}/m7 && cp {stage}/plugin.so {stage}/artifacts/{plugin}/m7/plugin.so')
snapshot=dict(schema=1,hostProtocolVersion=1,generation=plugin,publishRequestId=plugin,backend='ZYGISK',userId=0,uid=uid,packageName=package,targetVersionCode=version,targetInstallIdentity=apk,policy='NATIVE_ONLY',orderedPlugins=[dict(localPluginId=plugin,buildId='m7',sourceRevision=1,processScope='exact',processName=a.process_name,processType='isolated',config={})])
f=a.output/'snapshot.json';f.write_text(json.dumps(snapshot));run('push',str(f),stage+'/snapshot.json')
try:
    (a.output/'publish.json').write_text(call('publish',stage+'/snapshot.json',stage+'/artifacts'))
    shell('am','force-stop',client)
    with (a.output/'processes.log').open('w') as output:
        capture=subprocess.Popen([*adb,'logcat','-T','1','-v','threadtime','-s','EasylspDemoM7:I','EasylspHost:I','EasylspNative:I','AndroidRuntime:E','chromium:E','*:S'],stdout=output,stderr=subprocess.STDOUT)
        try:
            shell('am','start','-W','-n',client+'/.DemoActivity','--ez','verifyWebView','true')
            for _ in range(20):
                time.sleep(.5);logs=(a.output/'processes.log').read_text()
                if 'webview page finished' in logs:break
        finally:capture.terminate();capture.wait()
    table=root('ps -A -n -o UID,PID,NAME');(a.output/'process-table.txt').write_text(table)
    renderers=[line.split() for line in table.splitlines() if len(line.split())==3 and line.split()[2]==a.process_name]
    assert renderers,'no exact renderer process'
    events=json.loads(call('events',str(uid),'isolated'));(a.output/'events.json').write_text(json.dumps(events,indent=2))
    initialized=[x for x in events['events'] if x['pid'] in {int(row[1]) for row in renderers} and x['pluginId']==plugin and x['phase']=='plugin_initialized']
    result=dict(status='PASSED' if initialized and 'webview page finished' in logs else 'FAILED',targetPackage=package,rendererPids=[int(row[1]) for row in renderers],processName=a.process_name,pluginInitialized=bool(initialized),pageFinished='webview page finished' in logs,scope='provider package and exact renderer name across clients; no per-client isolation or Chromium internals claim')
    (a.output/'result.json').write_text(json.dumps(result,indent=2)+'\n');assert result['status']=='PASSED',json.dumps(result);print(json.dumps(result))
finally:
    call('disable',str(uid),package);shell('am','force-stop',client)
    root(f'rm -rf {stage} /data/adb/easylsp/artifacts/{plugin}')
