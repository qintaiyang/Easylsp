package io.github.easylsp.app.platform.bridge
import android.app.Service
import android.content.Intent
import android.os.IBinder
import io.github.easylsp.app.EasylspApp
class BridgeService : Service() {
    override fun onBind(intent: Intent): IBinder = (application as EasylspApp).bridge.binder
}
