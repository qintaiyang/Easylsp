#!/usr/bin/env python3
"""Publish only to an existing temporary profile and assert the owner stays unhooked."""
import argparse,json,re,shlex,subprocess,time,uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--user',type=int,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();assert a.user>0;a.output.mkdir(parents=True,exist_ok=True)
adb=['adb','-s',a.serial];package='io.github.easylsp.demo';ctl='/data/adb/modules/easylsp_native_host/bin/easylsp-hostctl';stage='/data/local/tmp/easylsp-m7-profile';plugin='m7-'+uuid.uuid4().hex

def run(*args):return subprocess.run([*adb,*args],check=True,text=True,capture_output=True).stdout
def root(script):return run('shell','su -c '+shlex.quote(script))
def shell(*args):return run('shell',shlex.join(args))
def call(*args):return root(shlex.join([ctl,*args]))
uid=int(re.search(r'uid:(\d+)',shell('pm','list','packages','-U','--user',str(a.user),package)).group(1));assert uid//100000==a.user
assert not any(x['packageName']==package for x in json.loads(call('status'))['deployments'])
apk=shell('pm','path','--user',str(a.user),package).splitlines()[0].removeprefix('package:');version=int(re.search(r'versionCode=(\d+)',shell('dumpsys','package',package)).group(1))
root(f'mkdir -p {stage} && chown 2000:2000 {stage}')
run('push','app/build/generated/nativeAcceptanceAssets/fixed-native/early-symbol-A/plugin.so',stage+'/plugin.so')
root(f'mkdir -p {stage}/artifacts/{plugin}/m7 && cp {stage}/plugin.so {stage}/artifacts/{plugin}/m7/plugin.so')
snapshot=dict(schema=1,hostProtocolVersion=1,generation=plugin,publishRequestId=plugin,backend='ZYGISK',userId=a.user,uid=uid,packageName=package,targetVersionCode=version,targetInstallIdentity=apk,policy='NATIVE_ONLY',orderedPlugins=[dict(localPluginId=plugin,buildId='m7',sourceRevision=1,processScope='main',processName=package,processType='app',config={})])
f=a.output/'snapshot.json';f.write_text(json.dumps(snapshot));run('push',str(f),stage+'/snapshot.json')
original=root(f'if [ -f /data/user/0/{package}/files/startup-fault.txt ]; then cat /data/user/0/{package}/files/startup-fault.txt; fi')

def launch(user,label,expected):
    shell('am','force-stop','--user',str(user),package)
    root('nsenter -t 1 -m -- run-as '+package+' --user '+str(user)+' sh -c '+shlex.quote('mkdir -p files; echo native-plugin > files/startup-fault.txt'))
    shell('am','start','-W','--user',str(user),'-n',package+'/.DemoActivity')
    table=root('ps -A -n -o UID,PID,NAME');pid=next(int(parts[1]) for line in table.splitlines() if len(parts:=line.split())==3 and parts[2]==package and (int(parts[0])==uid if user else int(parts[0])//100000==0))
    logs=shell('logcat','-d','-v','raw','--pid='+str(pid),'-s','EasylspDemoNative:I','*:S');(a.output/(label+'.log')).write_text(logs)
    assert 'constructor result='+str(expected) in logs,logs
    return dict(userId=user,pid=pid,expected=expected,status='PASSED')
try:
    (a.output/'publish.json').write_text(call('publish',stage+'/snapshot.json',stage+'/artifacts'))
    results=[launch(a.user,'profile-enabled',1010),launch(0,'owner-isolated',8)]
    (a.output/'profile-events.json').write_text(call('events',str(uid)))
    call('disable',str(uid),package);results.append(launch(a.user,'profile-disabled',8))
    (a.output/'results.json').write_text(json.dumps(results,indent=2)+'\n');print(json.dumps(results))
finally:
    call('disable',str(uid),package)
    for user in [0,a.user]:shell('am','force-stop','--user',str(user),package)
    restore=('printf %s '+shlex.quote(original)+' > files/startup-fault.txt') if original else 'rm -f files/startup-fault.txt'
    root('nsenter -t 1 -m -- run-as '+package+' sh -c '+shlex.quote(restore))
    root(f'rm -rf {stage} /data/adb/easylsp/artifacts/{plugin}')
