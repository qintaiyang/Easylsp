#!/usr/bin/env python3
"""Compile fixed desktop-NDK plugin candidates and an offline v3 example; no adb/su."""
import argparse
import json
import os
import re
from pathlib import Path
import subprocess
import zipfile

parser = argparse.ArgumentParser()
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '/opt/android-sdk')
parser.add_argument('--ndk', type=Path, default=sdk / 'ndk/28.2.13676358')
parser.add_argument('--demo-lib', required=True, type=Path, help='The exact unstripped libdemo_early_hook.so shipped in the Demo APK')
parser.add_argument('--output', type=Path, default=Path('build/native-candidates'))
args = parser.parse_args()
repo = Path(__file__).resolve().parents[1]
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=True)
cc = args.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang'
readelf = args.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf'
notes = subprocess.check_output([str(readelf), '-n', str(args.demo_lib)], text=True)
build_id = re.search(r'Build ID: ([0-9a-f]+)', notes).group(1)
symbols = subprocess.check_output([str(readelf), '--dyn-syms', '--wide', str(args.demo_lib)], text=True)
vaddr = re.search(r'\b([0-9a-f]+)\s+\d+\s+FUNC[^\n]*\beasylsp_demo_early_value\b', symbols).group(1)
examples = [(p, p.name, None) for p in sorted((repo / 'examples/native-plugins').iterdir())]
early = repo / 'examples/native-plugins/early-symbol'
examples += [(early, 'early-symbol-A', 1000), (early, 'early-symbol-B', 2000)]
for example, candidate_name, addition in examples:
    sources = sorted([*(example / 'src').rglob('*.c'), *(example / 'src').rglob('*.cpp')])
    if not sources:
        continue
    directory = output / candidate_name
    directory.mkdir(exist_ok=True)
    exports = directory / 'exports.map'
    exports.write_text('{ global: easylsp_plugin_init_v1; local: *; };\n')
    generated_sources = []
    for source in sources:
        text = source.read_text()
        if example.name in ('elf-vaddr', 'instruction-intercept'):
            text = text.replace('REPLACE_FROM_ANALYSIS', build_id).replace('#define TARGET_ELF_VADDR 0x0', '#define TARGET_ELF_VADDR 0x' + vaddr)
        if example.name == 'instruction-intercept':
            for define, symbol in [('TARGET_ELF_VADDR', 'easylsp_demo_intercept_point'), ('FLOAT_ELF_VADDR', 'easylsp_demo_float_point')]:
                address = re.search(r'\b([0-9a-f]+)\s+\d+\s+NOTYPE[^\n]*\b' + symbol + r'\b', symbols).group(1)
                text = re.sub(r'#define ' + define + r' 0x[0-9a-f]+', '#define ' + define + ' 0x' + address, text)
        if addition is not None:
            text = text.replace('result + 1000', 'result + ' + str(addition)).replace('return 1010', 'return ' + str(addition + 10))
        generated = directory / 'src' / source.relative_to(example / 'src')
        generated.parent.mkdir(parents=True, exist_ok=True)
        generated.write_text(text)
        generated_sources.append(generated)
    artifact = directory / 'plugin.so'
    is_cpp = any(source.suffix == '.cpp' for source in sources)
    compiler = str(cc) + ('++' if is_cpp else '')
    subprocess.run([compiler, '-std=c++17' if is_cpp else '-std=c17', '-Wall', '-Wextra', '-Werror', '-O2', '-fPIC', '-fvisibility=hidden', '-shared', '-I' + str(repo / 'native-runtime/src/main/cpp/include'), '-I' + str(example / 'include'), '-I' + str(args.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include'), *map(str, generated_sources), *(['-static-libstdc++'] if is_cpp else []), '-Wl,--exclude-libs,ALL', '-Wl,--no-undefined', '-Wl,--hash-style=both', '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384', '-Wl,--version-script=' + str(exports), '-lc', '-ldl', '-llog', '-lm', '-o', str(artifact)], check=True)
    manifest = dict(formatVersion=3, catalogPluginId='demo.' + candidate_name, publisherId='local-demo', releaseId='demo-' + candidate_name + '-api31-1', versionCode=2, versionName='1.0', name='Demo ' + candidate_name, description='Offline experimental device fixture; not a published marketplace release.', license='GPL-3.0-or-later', sourceUrl='https://github.com/qintaiyang/Easylsp', channel='test', changelog='Initial offline candidate', publishedAt='2026-11-06T00:00:00Z', runtime='NATIVE', pluginAbi=1, sdkVersion='p0-v4-api31-m7', minHostVersion=9, requiredCapabilities=['native.plt' if example.name == 'plt-runtime' else 'native.chain' if example.name in ('chain-a','chain-b') else 'native.jni-register' if example.name.startswith('jni-') and example.name != 'jni-onload' else 'native.intercept' if example.name == 'instruction-intercept' else 'native.elf-vaddr' if example.name == 'elf-vaddr' else 'native.module-lifecycle' if example.name == 'module-lifecycle' else 'native.symbol'], artifacts=[dict(abi='arm64-v8a', minApi=31, artifactPath='native/arm64-v8a/plugin.so')], target=dict(packageName='io.github.easylsp.demo', processScope='main'))
    (directory / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    with zipfile.ZipFile(directory / 'plugin-v3.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.write(directory / 'manifest.json', 'manifest.json')
        archive.write(artifact, 'native/arm64-v8a/plugin.so')
        archive.writestr('README.md', manifest['description'])
        archive.write(repo / 'LICENSE', 'LICENSE')
        for source in generated_sources:
            archive.write(source, source.relative_to(directory).as_posix())
    print(artifact)
(output / 'demo-identity.json').write_text(json.dumps(dict(elfBuildId=build_id, elfVaddr='0x' + vaddr, demoLibrary=str(args.demo_lib.resolve())), indent=2) + '\n')
print(output)
