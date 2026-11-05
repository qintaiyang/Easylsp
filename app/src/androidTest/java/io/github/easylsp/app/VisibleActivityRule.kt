package io.github.easylsp.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Apply before ActivityScenario launches, including Activity recreation. */
class VisibleActivityRule : ExternalResource() {
    private val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity is MainActivity) {
                activity.setTurnScreenOn(true)
                activity.setShowWhenLocked(true)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
    override fun before() = application.registerActivityLifecycleCallbacks(callbacks)
    override fun after() = application.unregisterActivityLifecycleCallbacks(callbacks)
}
