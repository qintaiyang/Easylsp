#!/usr/bin/env python3
"""Actual adapter/companion binaries with a simulated Next provider ABI, not system Hyos acceptance."""
import argparse, json, shlex, subprocess, uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
adb=['adb','-s',a.serial];stage='/data/local/tmp/easylsp-m7-next';ctl='/data/adb/modules/easylsp_native_host/bin/easylsp-hostctl';target='m7-'+uuid.uuid4().hex;plugin=target

def run(*args):return subprocess.run([*adb,*args],text=True,capture_output=True,check=True).stdout
def root(command):return run('shell','su -c '+shlex.quote(command))
def call(*args):return root(shlex.join([ctl,*args]))
root(f'mkdir -p {stage} && chown 2000:2000 {stage}')
for path in Path('build/m7-native-fixtures').iterdir():
    if path.is_file():run('push',str(path),stage+'/'+path.name)
root(f'mkdir -p {stage}/artifacts/{plugin}/m7 && cp {stage}/plugin.so {stage}/artifacts/{plugin}/m7/plugin.so && chmod 755 {stage}/m7-next-provider')
config=dict(schema=1,targetId=target,generation=target,executable=stage+'/m7-next-provider',uid=0,processName='',packageName='org.example.native',plugins=[dict(localPluginId=plugin,buildId='m7',instanceId='m7',sourceRevision=1,config={})])
f=a.output/'config.json';f.write_text(json.dumps(config));run('push',str(f),stage+'/config.json')
try:
    (a.output/'publish.json').write_text(call('native-publish',stage+'/config.json',stage+'/artifacts'))
    results=[]
    for mode,library,marker in [('native','libeasylsp-next.so','M7_NEXT_ABI3_FIXTURE_OK'),('hyos','libeasylsp-hyos.so','M7_NEXT_ABI4_HYOS_FIXTURE_OK')]:
        output=root(shlex.join([stage+'/m7-next-provider','/data/adb/modules/easylsp_native_host/lib/arm64-v8a/'+library,mode,stage+'/libm7future.so']))
        (a.output/(mode+'.log')).write_text(output);assert marker in output,output
        results.append(dict(mode=mode,status='PASSED',scope='actual Android adapter/companion and Core; simulated provider ABI; not system provider acceptance'))
    (a.output/'results.json').write_text(json.dumps(results,indent=2)+'\n');print(json.dumps(results))
finally:
    call('native-disable',target)
    root(f'rm -rf {stage} /data/adb/easylsp/artifacts/{plugin}')
