#!/bin/bash
set -eu
sdk=$(cd "$(dirname "$0")/.." && pwd)
work=$(cd "$1" && pwd)
lib="$sdk/sysroot/usr/lib/aarch64-linux-android/31"
clang-18 --version
ld.lld-18 --version
printf '{ global: easylsp_plugin_init_v1; local: *; };\n' > "$work/exports.map"
mkdir -p "$work/objects"
objects=()
cpp=false
while IFS= read -r -d '' source; do
    object="$work/objects/${#objects[@]}.o"
    if [[ "$source" == *.cpp ]]; then
        cpp=true
        clang-18 --target=aarch64-linux-android31 --sysroot="$sdk/sysroot" -std=c++17 -nostdinc++ -isystem "$sdk/sysroot/usr/include/c++/v1" -fexceptions -frtti \
            -Wall -Wextra -Werror -O2 -fPIC -fvisibility=hidden -fvisibility-inlines-hidden -I "$work/include" -I "$work/src" -c "$source" -o "$object"
    else
        clang-18 --target=aarch64-linux-android31 --sysroot="$sdk/sysroot" -std=c17 \
            -Wall -Wextra -Werror -O2 -fPIC -fvisibility=hidden -I "$work/include" -I "$work/src" -c "$source" -o "$object"
    fi
    objects+=("$object")
done < <(find "$work/src" -type f \( -name '*.c' -o -name '*.cpp' \) -print0 | sort -z)
stl=()
if "$cpp"; then
    stl=("-Wl,--start-group" "$sdk/lib/libc++_static.a" "$sdk/lib/libc++abi.a" "$sdk/lib/libunwind.a" "-Wl,--end-group")
fi
clang-18 --target=aarch64-linux-android31 --sysroot="$sdk/sysroot" -fuse-ld=lld-18 -shared -nostdlib \
    "$lib/crtbegin_so.o" "${objects[@]}" "${stl[@]}" \
    -L "$lib" -Wl,--no-undefined -Wl,--hash-style=both -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
    -Wl,-z,relro,-z,now -Wl,--exclude-libs,ALL -Wl,--version-script="$work/exports.map" -Wl,-soname,"plugin-$2.so" \
    "$sdk/lib/libclang_rt.builtins-aarch64-android.a" -lc -ldl -llog -lm "$lib/crtend_so.o" -o "$work/plugin.so"
python3 "$sdk/bin/validate-plugin.py" "$work/plugin.so" > "$work/elf.json"
cat "$work/elf.json"
