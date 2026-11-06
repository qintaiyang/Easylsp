package io.github.easylsp.app.platform.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** OEM pages vary by firmware; launch only an exported, permitted system entry. */
object ManufacturerBackgroundSettings {
    private val vendor get() = Build.MANUFACTURER.lowercase()
    val guidance: String get() = when (vendor) {
        "xiaomi", "redmi", "poco" -> "小米 / HyperOS：允许后台自启动，电量策略选择无限制，并在最近任务中锁定 Easylsp。"
        "oppo", "realme", "oneplus" -> "OPPO / 真我 / 一加：允许自启动和后台活动，并在最近任务中锁定 Easylsp。"
        else -> "允许 Easylsp 自启动和后台活动；系统提供最近任务锁定时，可同时开启。"
    }
    fun autostart(context: Context): Intent {
        val options = when (vendor) {
            "xiaomi", "redmi", "poco" -> listOf(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
            "oppo", "realme", "oneplus" -> listOf(
                Intent("com.oplus.battery.permission.startup.StartupAppListActivity").setPackage("com.oplus.battery"),
                Intent("com.oplus.powermanager.fuelgaue.PowerAppsBgSetting").setPackage("com.oplus.battery")
            ) + if (vendor == "oneplus") listOf(Intent().setComponent(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))) else emptyList()
            else -> emptyList()
        }
        return (options.firstOrNull { intent ->
            val activity = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            activity != null && activity.exported && (activity.permission == null || context.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED)
        } ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
