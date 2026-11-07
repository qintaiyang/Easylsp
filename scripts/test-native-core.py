#!/usr/bin/env python3
"""Desktop regression for the actual C selector; no Android/Hook compatibility claim."""
from pathlib import Path
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as directory:
    directory = Path(directory)
    source = directory / 'fixture.c'
    source.write_text('int fixture_value(int value) { return value + 1; }\n')
    libraries = []
    for name, build_id in [('wrong', '11111111'), ('matching', '22222222')]:
        library = directory / name / 'libcandidate.so'
        library.parent.mkdir()
        subprocess.run(['cc', '-shared', '-fPIC', str(source), '-Wl,--build-id=0x' + build_id, '-o', str(library)], check=True)
        libraries.append(str(library))
    test = directory / 'core-test'
    subprocess.run(['cc', '-std=c17', '-Wall', '-Wextra', '-Werror', '-I' + str(repo / 'native-runtime/tests/stubs'), str(repo / 'native-runtime/tests/core_binding_test.c'), '-ldl', '-pthread', '-o', str(test)], check=True)
    subprocess.run([str(test), *libraries], check=True)

    callee = directory / 'libm7callee.so'
    caller = directory / 'libm7caller.so'
    subprocess.run(['cc','-shared','-fPIC',str(repo/'native-entry/tests/callee.c'),'-Wl,-soname,libm7callee.so','-o',str(callee)],check=True)
    subprocess.run(['cc','-shared','-fPIC',str(repo/'native-entry/tests/caller.c'),'-L'+str(directory),'-lm7callee','-Wl,-z,now,-z,relro,-rpath,$ORIGIN','-o',str(caller)],check=True)
    test = directory / 'plt-test'
    subprocess.run(['cc','-std=c17','-Wall','-Wextra','-Werror','-I'+str(repo/'native-runtime/tests/stubs'),str(repo/'native-runtime/tests/core_plt_test.c'),'-ldl','-pthread','-o',str(test)],check=True)
    subprocess.run([str(test),str(caller)],check=True)

    for fixture in ["core_event_test", "core_jni_test"]:
        test = directory / fixture
        import os
        java = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-17-openjdk-amd64"))
        includes = ["-I" + str(java / "include"), "-I" + str(java / "include/linux")] if fixture == "core_jni_test" else []
        subprocess.run(["cc", "-std=c17", "-Wall", "-Wextra", "-Werror", "-I" + str(repo / "native-runtime/tests/stubs"), *includes, str(repo / ("native-runtime/tests/" + fixture + ".c")), "-ldl", "-pthread", "-o", str(test)], check=True)
        subprocess.run([str(test)], check=True)
