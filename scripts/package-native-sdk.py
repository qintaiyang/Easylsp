#!/usr/bin/env python3
"""Package target files only. Never ship/execute the NDK's x86_64 host compiler on a phone."""
import argparse
import io
import json
from pathlib import Path
import tarfile

parser = argparse.ArgumentParser()
parser.add_argument('--ndk', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
assert 'Pkg.Revision = 28.2.13676358' in (args.ndk / 'source.properties').read_text()
llvm = args.ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
args.output.parent.mkdir(parents=True, exist_ok=True)
with tarfile.open(args.output, 'w') as archive:
    archive.add(llvm / 'sysroot/usr/include', 'sysroot/usr/include')
    lib = llvm / 'sysroot/usr/lib/aarch64-linux-android/31'
    for name in ['crtbegin_so.o', 'crtend_so.o', 'libc.so', 'libdl.so', 'liblog.so', 'libm.so']:
        archive.add(lib / name, 'sysroot/usr/lib/aarch64-linux-android/31/' + name)
    for name in ['libc++_static.a', 'libc++abi.a']:
        archive.add(llvm / 'sysroot/usr/lib/aarch64-linux-android' / name, 'lib/' + name)
    archive.add(llvm / 'lib/clang/19/lib/linux/aarch64/libunwind.a', 'lib/libunwind.a')
    archive.add(llvm / 'lib/clang/19/lib/linux/libclang_rt.builtins-aarch64-android.a', 'lib/libclang_rt.builtins-aarch64-android.a')
    for name in ['NOTICE', 'NOTICE.toolchain', 'source.properties']:
        archive.add(args.ndk / name, 'licenses/' + name)
    for path in sorted((Path(__file__).parent / 'native-sdk').iterdir()):
        if path.is_file():
            archive.add(path, 'bin/' + path.name)
    metadata = json.dumps(dict(sdkVersion='p0-v4-api31-m7', ndk='28.2.13676358', compiler='Ubuntu clang-18/lld-18', abi='arm64-v8a', minApi=31, languages=['c17', 'c++17'], stl='libc++ static; exceptions/RTTI confined to plugin')).encode()
    entry = tarfile.TarInfo('sdk.json')
    entry.size = len(metadata)
    archive.addfile(entry, io.BytesIO(metadata))
print(args.output)
