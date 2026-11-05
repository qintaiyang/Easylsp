#!/usr/bin/env bash
set -euo pipefail
serial="${1:-127.0.0.1:15555}"
repository=$(cd "$(dirname "$0")/.." && pwd)
evidence="$repository/app/build/native-p0-device"
mkdir -p "$evidence"
adb_device=(adb -s "$serial")
"${adb_device[@]}" get-state
instrument() {
    "${adb_device[@]}" shell am instrument -w -r -e class "io.github.easylsp.app.NativePluginP0DeviceTest#$1" \
        io.github.easylsp.test/androidx.test.runner.AndroidJUnitRunner | tee "$evidence/$1.txt"
    rg -q '^OK \(1 test\)' "$evidence/$1.txt"
}
fixture() {
    "${adb_device[@]}" shell am force-stop io.github.easylsp.demo
    "${adb_device[@]}" shell run-as io.github.easylsp.demo mkdir -p files
    printf 'native-plugin\n' | "${adb_device[@]}" shell run-as io.github.easylsp.demo sh -c "'cat > files/startup-fault.txt'"
}
launch() {
    "${adb_device[@]}" shell am start -W -n io.github.easylsp.demo/.DemoActivity
}
instrument preparePrototype
"${adb_device[@]}" exec-out run-as io.github.easylsp cat files/native-p0-publication.json > "$evidence/publication.json"
fixture
"${adb_device[@]}" shell am force-stop io.github.easylsp
launch > "$evidence/launch.txt"
target_pid=$("${adb_device[@]}" shell pidof io.github.easylsp.demo | tr -d '\r')
"${adb_device[@]}" shell logcat -d -v raw --pid="$target_pid" -s EasylspNative:I EasylspDemoNative:I '*:S' > "$evidence/enabled.log"
manager_pid=$("${adb_device[@]}" shell pidof io.github.easylsp || true)
test -z "$manager_pid"
python3 "$repository/scripts/verify-native-p0-evidence.py" "$evidence/publication.json" "$evidence/enabled.log"
# Explicit disable changes the next snapshot; the running target retains its loaded code.
instrument disablePrototype
fixture
launch > "$evidence/disabled-launch.txt"
target_pid=$("${adb_device[@]}" shell pidof io.github.easylsp.demo | tr -d '\r')
"${adb_device[@]}" shell logcat -d -v raw --pid="$target_pid" -s EasylspNative:I EasylspDemoNative:I '*:S' > "$evidence/disabled.log"
rg -q '^constructor result=8 errno=33$' "$evidence/disabled.log"
if rg -q '"phase":"plugin_initialized"' "$evidence/disabled.log"; then exit 1; fi
printf 'P0 constructor/FD/manager-absent/disable evidence: %s\n' "$evidence"
