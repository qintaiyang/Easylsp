package io.github.easylsp.app.platform.phone

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import io.github.easylsp.app.EasylspApp

class PhoneAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { (application as EasylspApp).phone.connect(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType in setOf(AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
            (application as EasylspApp).phone.changed()
    }
    override fun onInterrupt() { (application as EasylspApp).phone.disconnect() }
    override fun onDestroy() { (application as EasylspApp).phone.disconnect(); super.onDestroy() }
}
