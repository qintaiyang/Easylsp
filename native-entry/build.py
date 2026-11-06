#!/usr/bin/env python3
from pathlib import Path
import argparse, subprocess, shutil
p=argparse.ArgumentParser(); p.add_argument('--ndk',required=True,type=Path);p.add_argument('--runtime',required=True,type=Path);p.add_argument('--output',required=True,type=Path);a=p.parse_args()
r=Path(__file__).resolve().parents[1]; a.output.mkdir(parents=True,exist_ok=True)
cc=a.ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang'
flags=['-std=c17','-O2','-g','-Wall','-Wextra','-Werror','-I'+str(r/'native-runtime/src/main/cpp/include'),'-I'+str(r/'zygisk-host/third_party'),'-Wl,-z,max-page-size=16384']
subprocess.run([str(cc),*flags,'-fPIE','-pie',str(r/'native-entry/launcher.c'),'-o',str(a.output/'easylsp-native-run')],check=True)
subprocess.run([str(cc),*flags,'-fPIC','-shared',str(r/'native-entry/bootstrap.c'),str(r/'zygisk-host/third_party/cJSON.c'),'-L'+str(a.runtime),'-leasylsp_native','-lshadowhook','-ldl','-llog','-Wl,-rpath,$ORIGIN','-Wl,-z,defs','-o',str(a.output/'libeasylsp_bootstrap.so')],check=True)
for name in ['libeasylsp_native.so','libshadowhook.so','libshadowhook_nothing.so']: shutil.copy2(a.runtime/name,a.output/name)
shutil.copy2(r/'native-runtime/src/main/cpp/include/easylsp_native_core.h',a.output/'easylsp_native_core.h')
print(a.output)
