#!/usr/bin/env python3
"""Controlled root diagnostic overrides: entry/observe/normal, scope, deadline and run cleanup."""
import argparse,json,re,shlex,subprocess,tempfile,time,uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial');p.add_argument('--output',type=Path,default=Path('build/device-acceptance/engine201/diagnostic'));args=p.parse_args();args.output.mkdir(parents=True,exist_ok=True)
adb=['adb']+(['-s',args.serial] if args.serial else []);package='io.github.easylsp.demo';ctl='/data/adb/modules/easylsp_native_host/bin/easylsp-hostctl';stage='/data/local/tmp/easylsp-diagnostic-test'
def cmd(*v):return subprocess.check_output([*adb,*v],text=True,stderr=subprocess.STDOUT)
def shell(*v):return cmd('shell',shlex.join(v))
def root(v):return cmd('shell','su -c '+shlex.quote(v))
def control(*v):return json.loads(root(shlex.join([ctl,*v])))
def send(record):
 with tempfile.TemporaryDirectory() as directory:
  path=Path(directory)/'snapshot.json';path.write_text(json.dumps(record));cmd('push',str(path),stage+'/snapshot.json')
 return stage+'/snapshot.json'
uid=int(re.search(r'uid:(\d+)',shell('pm','list','packages','-U',package)).group(1));apk=shell('pm','path',package).splitlines()[0][8:];version=int(re.search(r'versionCode=(\d+)',shell('dumpsys','package',package)).group(1))
assert not any(r['packageName']==package for r in control('status')['deployments'])
shell('mkdir','-p',stage);build=str(uuid.uuid4());destination=stage+'/input/diagnostic-plugin/'+build
root(shlex.join(['mkdir','-p',destination]));cmd('push','build/native-candidates/early-symbol-A/plugin.so',stage+'/plugin.so');root(shlex.join(['cp',stage+'/plugin.so',destination+'/plugin.so']))
generation=str(uuid.uuid4());base=dict(schema=1,hostProtocolVersion=1,backend='ZYGISK',generation=generation,publishRequestId=generation,userId=uid//100000,uid=uid,packageName=package,targetVersionCode=version,targetInstallIdentity=apk,policy='NATIVE_ONLY',orderedPlugins=[dict(localPluginId='diagnostic-plugin',buildId=build,sourceRevision=1,processScope='main',processName=package,config={})])
results=[]
try:
 control('publish',send(base),stage+'/input')
 for label,mode,expected in [('entry','ENTRY_ONLY',8),('java-no-plugins','JAVA_NO_PLUGINS',8),('observe','NATIVE_OBSERVE',8),('normal','NORMAL',1010),('expired','ENTRY_ONLY',1010),('other-process','ENTRY_ONLY',1010)]:
  run=str(uuid.uuid4());endpoint='easylsp-test-'+run
  elapsed=float(shell('cat','/proc/uptime').split()[0])*1000;now=int(time.time()*1000)
  diagnostic=dict(diagnosticRunId=run,mode=mode,processName=package if label!='other-process' else package+':worker',expiresAt=now+(60000 if label!='expired' else -1),expiresElapsed=int(elapsed)+(60000 if label!='expired' else -1),nativeSocket=endpoint)
  record=dict(base,generation=run,publishRequestId=run,orderedPlugins=[],diagnostic=diagnostic);control('diagnostic',send(record))
  collector=None
  if mode=='NATIVE_OBSERVE':
   collector=subprocess.Popen([*adb,'shell','su -c '+shlex.quote('/data/local/tmp/easylsp-root-socket-collector '+endpoint+' '+stage+'/observe.bin')],stdout=subprocess.PIPE,text=True)
   if collector.stdout.readline().strip()!='READY':raise RuntimeError('collector did not bind')
  path=args.output/(label+'.log')
  with path.open('w') as output:
   logger=subprocess.Popen([*adb,'logcat','-v','threadtime','-T','1','-s','EasylspHost:I','EasylspDiag:I','EasylspNative:I','EasylspDemoNative:I','EasylspDemoFault:I','*:S'],stdout=output)
   try:
    shell('am','force-stop',package);cmd('shell','run-as '+package+' sh -c '+shlex.quote('mkdir -p files; echo native-plugin > files/startup-fault.txt'));shell('am','start','-W','-n',package+'/.DemoActivity');pid=int(shell('pidof',package).strip());time.sleep(.4)
   finally:logger.terminate();logger.wait()
  logs='\n'.join(line for line in path.read_text().splitlines() if re.match(r'\S+\s+\S+\s+'+str(pid)+r'\s',line));path.write_text(logs+'\n')
  assert f'constructor result={expected}' in logs,label
  applies=label not in ('expired','other-process');assert (f'"diagnosticRunId":"{run}"' in logs)==applies,label
  if expected==8:assert 'phase=plugin_result' not in logs,label
  if mode=='NATIVE_OBSERVE':
   assert 'phase=native_observation code=0' in logs,logs
   tail=collector.communicate(timeout=8)[0];assert collector.returncode==0,tail
   cmd('pull',stage+'/observe.bin',str(args.output/'observe.bin'))
  control('clear-diagnostic',str(uid),package,'unrelated-run')
  control('clear-diagnostic',str(uid),package,run)
  results.append(dict(case=label,passed=True,pid=pid,diagnosticRunId=run,expected=expected))
 (args.output/'results.json').write_text(json.dumps(results,indent=2)+'\n');print('Diagnostic override tests passed')
finally:control('disable',str(uid),package);shell('am','force-stop',package)
