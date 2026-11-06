#!/usr/bin/env python3
"""Measure a single controlled configuration; never changes modules or reboots the phone."""
import argparse, json, math, re, shlex, statistics, subprocess, time, uuid
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--mode',choices=['no-host','unscoped','one','multi','diagnostic'],required=True);p.add_argument('--samples',type=int,default=20);p.add_argument('--output',type=Path,required=True);p.add_argument('--candidates',type=Path,default=Path('build/native-candidates'));a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
package='io.github.easylsp.demo';ctl='/data/adb/modules/easylsp_native_host/bin/easylsp-hostctl';stage='/data/local/tmp/easylsp-startup-budget'
def cmd(*values):return subprocess.check_output(['adb',*values],text=True,stderr=subprocess.STDOUT)
def shell(*values):return cmd('shell',shlex.join(values))
def root(script):return cmd('shell','su -c '+shlex.quote(script))
def control(*values):return json.loads(root(shlex.join([ctl,*values])))
def send(value):
    file=a.output/'snapshot.json';file.write_text(json.dumps(value));cmd('push',str(file),stage+'/snapshot.json');return stage+'/snapshot.json'
status=control('status');assert not any(r['packageName']==package for r in status['deployments']), 'Preserve existing Demo publication'
uid=int(re.search(r'uid:(\d+)',shell('pm','list','packages','-U',package)).group(1));apk=shell('pm','path',package).splitlines()[0][8:];version=int(re.search(r'versionCode=(\d+)',shell('dumpsys','package',package)).group(1))
shell('mkdir','-p',stage);plugins=[];generation=str(uuid.uuid4());diagnostic_run=None
base=dict(schema=1,hostProtocolVersion=1,backend='ZYGISK',generation=generation,publishRequestId=generation,userId=uid//100000,uid=uid,packageName=package,targetVersionCode=version,targetInstallIdentity=apk,policy='NATIVE_ONLY',orderedPlugins=plugins)
if a.mode=='no-host':
    assert root('test -f /data/adb/modules/easylsp_native_host/disable; echo $?').strip()=='0', 'Disable host and reboot before baseline'
    assert status['runtimeStatus']=='UNKNOWN_UNTIL_PROCESS_EVIDENCE', 'Baseline needs a reboot after disabling the host'
try:
    for name in (['early-symbol-A'] if a.mode=='one' else ['chain-a','chain-b'] if a.mode=='multi' else []):
        build=str(uuid.uuid4());dest=f'{stage}/input/{name}/{build}';root(shlex.join(['mkdir','-p',dest]));cmd('push',str(a.candidates/name/'plugin.so'),stage+'/plugin.so');root(shlex.join(['cp',stage+'/plugin.so',dest+'/plugin.so']))
        plugins.append(dict(localPluginId=name,buildId=build,sourceRevision=1,processScope='main',processName=package,config={}))
    if plugins:control('publish',send(base),stage+'/input')
    if a.mode=='diagnostic':
        diagnostic_run=str(uuid.uuid4());elapsed=float(shell('cat','/proc/uptime').split()[0])*1000
        base['diagnostic']=dict(diagnosticRunId=diagnostic_run,mode='ENTRY_ONLY',processName=package,expiresAt=int(time.time()*1000)+600000,expiresElapsed=int(elapsed)+600000,nativeSocket='budget-'+diagnostic_run)
        control('diagnostic',send(base))
    root('setprop debug.easylsp.perf_target '+package)
    rows=[]
    for index in range(a.samples):
        shell('am','force-stop',package)
        cmd('shell','run-as '+package+' sh -c '+shlex.quote('mkdir -p files; echo native-plugin > files/startup-fault.txt'))
        started=time.time();launch=shell('am','start','-W','-n',package+'/.DemoActivity');pid=int(shell('pidof',package).strip())
        logs=cmd('logcat','-d','-v','raw','--pid='+str(pid),'-s','EasylspHostPerf:I','EasylspNative:I','EasylspDemoNative:I','*:S')
        assert 'constructor result='+str(1010 if a.mode=='one' else 1280 if a.mode=='multi' else 8) in logs,logs
        metrics=root('cat /proc/'+str(pid)+'/status; ls /proc/'+str(pid)+'/fd; dumpsys meminfo '+str(pid))
        (a.output/f'{index:02d}.log').write_text(launch+'\n'+logs+'\n'+metrics)
        timing={phase:[int(v) for v in re.findall(r'phase='+phase+r'(?: plugin=\S+)? elapsedUs=(\d+)',logs)] for phase in ['pre','post','companion_query','plugin_init']}
        total=re.search(r'TotalTime:\s*(\d+)',launch);assert total,launch
        threads=int(re.search(r'^Threads:\s*(\d+)',metrics,re.M).group(1))
        pss=re.search(r'TOTAL PSS:\s*(\d+)',metrics);assert pss,metrics
        fds=sum(1 for line in metrics.splitlines() if re.fullmatch(r'\d+',line.strip()))
        events=control('events',str(uid)) if plugins else dict(events=[])
        dropped=max([e.get('dropped',0) for e in events.get('events',[]) if e.get('pid')==pid] or [0])
        rows.append(dict(sample=index,pid=pid,totalMs=int(total.group(1)),wallMs=round((time.time()-started)*1000),pssKb=int(pss.group(1)),fds=fds,threads=threads,dropped=dropped,**timing))
    def summary(values):
        ordered=sorted(values);return dict(p50=statistics.median(ordered),p95=ordered[math.ceil(.95*len(ordered))-1],max=max(ordered)) if ordered else None
    result=dict(mode=a.mode,samples=rows,generation=generation,bootId=shell('cat','/proc/sys/kernel/random/boot_id').strip(),host=status,summary={key:summary([r[key] for r in rows]) for key in ['totalMs','pssKb','fds','threads','dropped']})
    for phase in ['pre','post','companion_query','plugin_init']:result['summary'][phase+'Us']=summary([value for r in rows for value in r[phase]])
    (a.output/'results.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result['summary'],indent=2))
finally:
    if plugins:control('disable',str(uid),package)
    if diagnostic_run:control('clear-diagnostic',str(uid),package,diagnostic_run)
    root("setprop debug.easylsp.perf_target ''");shell('am','force-stop',package)
