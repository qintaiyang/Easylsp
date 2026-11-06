#!/system/bin/sh
# Keep user deployments for explicit recovery/removal. No plugin is loaded once this
# module is uninstalled and the device has rebooted. Delete /data/adb/easylsp
# explicitly only when its retained releases and rollback records are no longer wanted.
