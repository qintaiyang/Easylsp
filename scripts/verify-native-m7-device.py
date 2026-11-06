#!/usr/bin/env python3
"""Controlled standalone Native acceptance; no host install/reboot or user target edits."""
import argparse, json, shlex, subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--entry',type=Path,default=Path('native-entry/build/outputs/arm64-v8a'));p.add_argument('--fixtures',type=Path,default=Path('build/m7-native-fixtures'));p.add_argument('--output',type=Path,required=True);a=p.parse_args()
a.output.mkdir(parents=True,exist_ok=True)
adb=['adb','-s',a.serial]
def run(*args): return subprocess.run([*adb,*args],check=True,text=True,capture_output=True).stdout
def root(script): return run('shell','su -c '+shlex.quote(script))
remote='/data/local/tmp/easylsp-m7-native'
root(f'mkdir -p {remote} && chown 2000:2000 {remote}')
for directory in [a.entry,a.fixtures]:
    for path in directory.iterdir():
        if path.is_file() and path.suffix!='.h': run('push',str(path),remote+'/'+path.name)
config=dict(schema=1,packageName='org.example.native',generation='m7-controlled',plugins=[dict(path=remote+'/plugin.so',pluginId='m7',buildId='m7-test',instanceId='m7-instance',config={})])
file=a.output/'config.json';file.write_text(json.dumps(config));run('push',str(file),remote+'/config.json')
script=f'chmod 755 {remote}/easylsp-native-run {remote}/m7-program && {remote}/easylsp-native-run --config {remote}/config.json -- {remote}/m7-program {remote}/libm7future.so'
result=subprocess.run([*adb,'shell','su -c '+shlex.quote(script)],text=True,capture_output=True)
(a.output/'native-run.log').write_text(result.stdout+result.stderr)
assert result.returncode==0 and 'M7_NATIVE_OK' in result.stdout,result.stdout+result.stderr
(a.output/'result.json').write_text(json.dumps(dict(serial=a.serial,status='PASSED',scope='real Android standalone Native PLT/runtime registration/lifecycle; no ART or Next provider API claim',output=result.stdout.strip()),indent=2)+'\n')
root(f'rm -rf {remote}')
print(result.stdout.strip())
