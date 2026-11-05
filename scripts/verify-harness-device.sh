#!/usr/bin/env bash
set -euo pipefail

# All mount commands live in a script executed *after* the native namespace entry.
# Do not inline them into nested adb / su / sh -c command strings.
serial="${1:-127.0.0.1:15555}"
repository=$(cd "$(dirname "$0")/.." && pwd)
staging=$(mktemp -d /tmp/easylsp-harness-device.XXXXXX)
trap 'rm -rf "$staging"' EXIT
cp "$repository"/harness/{runtime.mjs,session-repair.mjs,model-http.mjs,model-profile.mjs,control.mjs,android-policy.mjs,android-mcp.mjs,bridge-control.mjs,host.mjs,boot.mjs,daemon.mjs,runtime-test.mjs,host-test.mjs,model-protocol-test.mjs,resource-limit-test.mjs,request-timeout-test.mjs,capabilities-test.mjs} "$staging/"
cat > "$staging/verify.sh" <<'SCRIPT'
set -e
mount --rbind /dev /data/user/0/io.github.easylsp/files/linux/ubuntu/dev
mount --rbind /proc /data/user/0/io.github.easylsp/files/linux/ubuntu/proc
exec chroot /data/user/0/io.github.easylsp/files/linux/ubuntu /usr/bin/env PATH=/opt/easylsp-node-v24.11.0/bin:/usr/bin:/bin /bin/sh -c 'cd /opt/easylsp-harness; node runtime-test.mjs && node host-test.mjs && node model-protocol-test.mjs && node resource-limit-test.mjs && node request-timeout-test.mjs'
SCRIPT
adb -s "$serial" push "$staging/." /data/local/tmp/easylsp-harness-verification/
adb -s "$serial" shell "su -c 'cp /data/local/tmp/easylsp-harness-verification/*.mjs /data/user/0/io.github.easylsp/files/linux/ubuntu/opt/easylsp-harness/; /data/user/0/io.github.easylsp/files/linux/bin/namespace /system/bin/sh /data/local/tmp/easylsp-harness-verification/verify.sh'"
