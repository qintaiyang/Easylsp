#!/usr/bin/env python3
"""Build the standard-API thin host and root CLI; never bundles a Zygisk provider."""
import argparse
from pathlib import Path
import shutil
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--ndk', required=True, type=Path)
parser.add_argument('--runtime', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
base = Path(__file__).resolve().parent
repo = base.parent
out = base / 'build/host'
out.mkdir(parents=True, exist_ok=True)
toolchain = args.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
cc = toolchain / 'aarch64-linux-android31-clang'
cxx = toolchain / 'aarch64-linux-android31-clang++'
common = ['-O2', '-g', '-Wall', '-Wextra', '-Werror', '-fPIC', '-fvisibility=hidden', '-I' + str(base / 'src'), '-I' + str(base / 'third_party'), '-I' + str(repo / 'native-runtime/src/main/cpp/include')]
objects = []
for name in ['protocol', 'companion']:
    obj = out / (name + '.o')
    subprocess.run([str(cc), *common, '-std=c17', '-c', str(base / f'src/{name}.c'), '-o', str(obj)], check=True)
    objects.append(str(obj))
json_obj = out / 'cJSON.o'
subprocess.run([str(cc), *common, '-std=c17', '-c', str(base / 'third_party/cJSON.c'), '-o', str(json_obj)], check=True)
objects.append(str(json_obj))
subprocess.run([str(cxx), *common, '-std=c++17', '-fno-exceptions', '-fno-rtti', '-fno-threadsafe-statics', '-nostdlib++', '-shared', str(base / 'src/host.cpp'), *objects, '-llog', '-ldl', '-Wl,-z,max-page-size=16384', '-Wl,-z,defs', '-o', str(out / 'host.so')], check=True)
subprocess.run([str(cc), *common, '-std=c17', '-fPIE', '-pie', str(base / 'src/hostctl.c'), str(out / 'protocol.o'), str(json_obj), '-Wl,-z,max-page-size=16384', '-o', str(out / 'easylsp-hostctl')], check=True)
for api, name in [(3, 'libeasylsp-next.so'), (4, 'libeasylsp-hyos.so')]:
    subprocess.run([str(cc), *common, '-std=c17', '-shared', '-DEH_NEXT_API_VERSION=' + str(api), str(base / 'src/next_host.c'), str(out / 'protocol.o'), str(json_obj), '-llog', '-ldl', '-Wl,-z,max-page-size=16384', '-Wl,-z,defs', '-o', str(out / name)], check=True)
stage = out / 'module'
if stage.exists():
    shutil.rmtree(stage)
shutil.copytree(base / 'module', stage)
for source, destination in [(out / 'host.so', stage / 'zygisk/arm64-v8a.so'), (out / 'easylsp-hostctl', stage / 'bin/easylsp-hostctl')]:
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    subprocess.run([str(toolchain / 'llvm-strip'), '--strip-debug', str(destination)], check=True)
for name in ['libshadowhook.so', 'libshadowhook_nothing.so', 'libeasylsp_native.so']:
    destination = stage / 'runtime/0.9-dev1/arm64-v8a' / name
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(args.runtime / name, destination)
for name in ['libeasylsp-next.so', 'libeasylsp-hyos.so']:
    destination = stage / 'lib/arm64-v8a' / name
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(out / name, destination)
licenses = stage / 'licenses'
licenses.mkdir()
for name in ['LICENSE', 'THIRD_PARTY_NOTICES.md']:
    shutil.copy2(repo / name, licenses / name)
shutil.copy2(base / 'third_party/cJSON-LICENSE', licenses)
shutil.copy2(repo / 'app/src/main/assets/third-party/shadowhook/LICENSE', licenses / 'ShadowHook-LICENSE')
shutil.copy2(repo / 'native-runtime/third_party/shadowhook/UPSTREAM.md', licenses / 'ShadowHook-HOST.md')
shutil.copy2(repo / 'native-runtime/shadowhook-helper-fd.patch', licenses)
shutil.copy2(base / 'third_party/zygisk.hpp', licenses / 'zygisk-api-v4.hpp')
args.output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(args.output, 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(stage.rglob('*')):
        if path.is_file():
            info = zipfile.ZipInfo(path.relative_to(stage).as_posix(), (2026, 11, 6, 0, 0, 0))
            info.external_attr = (0o100755 if path.suffix == '.sh' or path.name in ('easylsp-hostctl', 'update-binary') else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes())
print(args.output)
