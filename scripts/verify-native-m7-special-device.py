#!/usr/bin/env python3
"""Controlled isolated/app-zygote acceptance against an installed M7 host and Demo."""
import argparse,json,re,shlex,subprocess,time,uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
adb=['adb','-s',a.serial];package='io.github.easylsp.demo';ctl='/data/adb/modules/easylsp_native_host/bin/easylsp-hostctl';stage='/data/local/tmp/easylsp-m7-special';prefix='m7-'+uuid.uuid4().hex

def run(*args):return subprocess.run([*adb,*args],check=True,text=True,capture_output=True).stdout
def root(script):return run('shell','su -c '+shlex.quote(script))
def shell(*args):return run('shell',shlex.join(args))
def call(*args):return root(shlex.join([ctl,*args]))
status=json.loads(call('status'));assert not any(x['packageName']==package for x in status['deployments'])
uid=int(re.search(r'uid:(\d+)',shell('pm','list','packages','-U',package)).group(1))
apk=shell('pm','path',package).splitlines()[0].removeprefix('package:');version=int(re.search(r'versionCode=(\d+)',shell('dumpsys','package',package)).group(1))
root(f'mkdir -p {stage} && chown 2000:2000 {stage}')
run('push','app/build/generated/nativeAcceptanceAssets/fixed-native/early-symbol-A/plugin.so',stage+'/plugin.so')
plugins=[]
for kind,name in [('isolated',':isolated:'+package+'.NativeIsolatedService'),('app_zygote',':appzygote:'+package+'.NativeAppZygoteService')]:
    plugin=prefix+'-'+kind;root(f'mkdir -p {stage}/artifacts/{plugin}/m7 && cp {stage}/plugin.so {stage}/artifacts/{plugin}/m7/plugin.so')
    plugins.append(dict(localPluginId=plugin,buildId='m7',sourceRevision=1,processScope='exact',processName=package+name,processType=kind,config={}))
snapshot=dict(schema=1,hostProtocolVersion=1,generation=prefix,publishRequestId=prefix,backend='ZYGISK',userId=0,uid=uid,packageName=package,targetVersionCode=version,targetInstallIdentity=apk,policy='NATIVE_ONLY',orderedPlugins=plugins)
f=a.output/'snapshot.json';f.write_text(json.dumps(snapshot));run('push',str(f),stage+'/snapshot.json')
try:
    (a.output/'publish.json').write_text(call('publish',stage+'/snapshot.json',stage+'/artifacts'))
    shell('am','force-stop',package)
    with (a.output/'processes.log').open('w') as output:
        capture=subprocess.Popen([*adb,'logcat','-T','1','-v','threadtime','-s','EasylspDemoM7:I','EasylspHost:I','EasylspNative:I','EasylspDemoNative:I','AndroidRuntime:E','*:S'],stdout=output,stderr=subprocess.STDOUT)
        try:
            shell('am','start','-W','-n',package+'/.DemoActivity','--ez','verifySpecialProcesses','true')
            for _ in range(20):
                time.sleep(.5)
                logs=(a.output/'processes.log').read_text()
                if len(re.findall(r'bound=.*result=',logs))>=2:break
        finally:capture.terminate();capture.wait()
    events=json.loads(call('events',str(uid),'isolated'));(a.output/'events.json').write_text(json.dumps(events,indent=2))
    services={}
    for name in ['NativeIsolatedService','NativeAppZygoteService']:
        match=re.search(r'bound=.*'+name+r' result=(\d+) uid=(9\d{4}) pid=(\d+)',logs)
        assert match,'missing service result: '+name
        services[name]=dict(result=int(match[1]),uid=int(match[2]),pid=int(match[3]))
    result=dict(status='PASSED' if all(x['result']==1010 for x in services.values()) else 'FAILED_PROVIDER_COVERAGE',services=services,parentDeferred='phase=app_zygote_parent' in logs)
    (a.output/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    assert result['status']=='PASSED',json.dumps(result)
    print('M7 special processes passed')
finally:
    call('disable',str(uid),package);shell('am','force-stop',package)
    root('rm -rf '+stage+' '+' '.join('/data/adb/easylsp/artifacts/'+x['localPluginId'] for x in plugins))
