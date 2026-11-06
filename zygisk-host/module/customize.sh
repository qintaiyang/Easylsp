#!/system/bin/sh
[ "$ARCH" = "arm64" ] || abort "Easylsp requires arm64"
[ "$API" -ge 31 ] || abort "Easylsp requires Android 12+"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/easylsp-hostctl" 0 0 0755
ui_print "Easylsp experimental host installed. Reboot required."
ui_print "Published data is kept at /data/adb/easylsp across host upgrades/removal."
ui_print "Recovery: su -c /data/adb/modules/easylsp_native_host/bin/easylsp-hostctl\ disable-all"
