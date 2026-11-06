#!/usr/bin/env python3
"""Build controlled Android Native fixtures; does not contact a device."""
import argparse, subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--ndk',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
r=Path(__file__).resolve().parents[2];a.output.mkdir(parents=True,exist_ok=True)
cc=str(a.ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang')
flags=['-std=c17','-O1','-g','-Wall','-Wextra','-Werror','-Wl,-z,relro,-z,now','-Wl,-z,max-page-size=16384']
def build(name,source,*extra): subprocess.run([cc,*flags,str(r/source),*extra,'-o',str(a.output/name)],check=True)
build('libm7callee.so','native-entry/tests/callee.c','-shared','-fPIC','-Wl,-soname,libm7callee.so')
for name in ['libm7caller.so','libm7future.so']:
    build(name,'native-entry/tests/caller.c','-shared','-fPIC','-Wl,-soname,'+name,'-L'+str(a.output),'-lm7callee','-Wl,-rpath,$ORIGIN')
build('m7-program','native-entry/tests/program.c','-fPIE','-pie','-L'+str(a.output),'-lm7caller','-lm7callee','-ldl','-Wl,-rpath,$ORIGIN')
build('plugin.so','examples/native-plugins/plt-runtime/src/main.c','-shared','-fPIC','-fvisibility=hidden','-I'+str(r/'native-runtime/src/main/cpp/include'))

checks=a.output/'checks.o'
subprocess.run([cc,*[f for f in flags if not f.startswith('-Wl,')],'-Dmain=m7_checks','-c',str(r/'native-entry/tests/program.c'),'-o',str(checks)],check=True)
build('m7-next-provider','native-entry/tests/next_provider.c',str(checks),'-fPIE','-pie','-I'+str(r/'zygisk-host/src'),'-L'+str(a.output),'-lm7caller','-lm7callee','-ldl','-Wl,-rpath,$ORIGIN')
checks.unlink()
