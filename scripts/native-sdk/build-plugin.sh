#!/bin/bash
set -eu
sdk=$(cd "$(dirname "$0")/.." && pwd)
work=$(cd "$1" && pwd)
# Only controlled C17 inputs; neither host libraries nor plugin install scripts are used.
lib="$sdk/sysroot/usr/lib/aarch64-linux-android/34"
clang-18 --version
ld.lld-18 --version
printf '{ global: easylsp_plugin_init_v1; local: *; };\n' > "$work/exports.map"
mapfile -d '' sources < <(find "$work/src" -type f -name '*.c' -print0 | sort -z)
clang-18 --target=aarch64-linux-android34 --sysroot="$sdk/sysroot" -fuse-ld=lld-18 \
    -std=c17 -Wall -Wextra -Werror -O2 -fPIC -fvisibility=hidden -shared -nostdlib \
    -I "$work/include" -I "$work/src" "$lib/crtbegin_so.o" "${sources[@]}" \
    -L "$lib" -Wl,--no-undefined -Wl,--hash-style=both -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
    -Wl,-z,relro,-z,now -Wl,--version-script="$work/exports.map" -Wl,-soname,"plugin-$2.so" \
    "$sdk/lib/libclang_rt.builtins-aarch64-android.a" -lc -ldl -llog -lm "$lib/crtend_so.o" \
    -o "$work/plugin.so"
python3 "$sdk/bin/validate-plugin.py" "$work/plugin.so" > "$work/elf.json"
cat "$work/elf.json"
