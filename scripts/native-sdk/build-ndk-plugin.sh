#!/bin/bash
# Desktop NDK counterpart: source-only static dependencies live in src/ and include/.
set -eu
work=$(cd "$1" && pwd)
ndk=$2
shift 2
repo=$(cd "$(dirname "$0")/../.." && pwd)
# Run this desktop helper from the source repository; phone SDK uses build-plugin.sh.
compiler="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang"
printf '{ global: easylsp_plugin_init_v1; local: *; };\n' > "$work/exports.map"
mkdir -p "$work/objects"
objects=()
cpp=false
while IFS= read -r -d '' source; do
    object="$work/objects/${#objects[@]}.o"
    if [[ "$source" == *.cpp ]]; then
        cpp=true
        "$compiler++" -std=c++17 -fexceptions -frtti -fvisibility-inlines-hidden -Wall -Wextra -Werror -O2 -fPIC -fvisibility=hidden \
            -I "$repo/native-runtime/src/main/cpp/include" -I "$work/include" -I "$work/src" -c "$source" -o "$object"
    else
        "$compiler" -std=c17 -Wall -Wextra -Werror -O2 -fPIC -fvisibility=hidden \
            -I "$repo/native-runtime/src/main/cpp/include" -I "$work/include" -I "$work/src" -c "$source" -o "$object"
    fi
    objects+=("$object")
done < <(find "$work/src" -type f \( -name '*.c' -o -name '*.cpp' \) -print0 | sort -z)
linker="$compiler"
stl=()
if "$cpp"; then linker="$compiler++"; stl=(-static-libstdc++); fi
"$linker" -shared "${objects[@]}" "$@" "${stl[@]}" -Wl,--no-undefined -Wl,--hash-style=both \
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-z,relro,-z,now -Wl,--exclude-libs,ALL \
    -Wl,--version-script="$work/exports.map" -lc -ldl -llog -lm -o "$work/plugin.so"
python3 "$repo/scripts/native-sdk/validate-plugin.py" "$work/plugin.so" > "$work/elf.json"
cat "$work/elf.json"
