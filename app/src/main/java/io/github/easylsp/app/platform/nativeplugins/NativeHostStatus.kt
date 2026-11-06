package io.github.easylsp.app.platform.nativeplugins

import org.json.JSONObject

fun nativeHostProblem(host: JSONObject, rootGranted: Boolean): String? {
    return when {
        !rootGranted -> "root 授权待恢复"
        host.optString("status") == "UNAVAILABLE" -> host.optString("detail", "宿主查询失败")
        host.optString("runtimeStatus") == "PENDING_REBOOT" -> "宿主更新待重启手机"
        host.optString("runtimeStatus") == "NOT_INSTALLED" -> "宿主尚未安装，请到执行环境导出安装包"
        host.optString("status") == "NOT_QUERIED" -> "正在查询宿主"
        host.optInt("hostProtocolVersion") != 1 -> "宿主协议不满足，请更新宿主"
        host.optJSONArray("capabilities")?.let { caps -> (0 until caps.length()).any { caps.getString(it) == "native.publish" } } != true -> "宿主能力不满足，请更新宿主"
        host.optString("runtimeStatus") != "COMPANION_LOADED" -> "本次启动尚无宿主运行证据"
        else -> null
    }
}
