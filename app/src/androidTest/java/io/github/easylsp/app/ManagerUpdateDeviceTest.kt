package io.github.easylsp.app

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import io.github.easylsp.app.platform.AppLinks
import io.github.easylsp.app.platform.ManagerUpdate
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class ManagerUpdateDeviceTest {
    @get:Rule(order = 0) val visible = VisibleActivityRule()
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()

    @Test fun upgradeVisibilityAndReleasePageIntent() = runBlocking {
        lateinit var vm: MainViewModel
        ui.runOnIdle { vm = ViewModelProvider(ui.activity)[MainViewModel::class.java] }
        (MainViewModel::class.java.getDeclaredField("updateCheck").apply { isAccessible = true }.get(vm) as Job?)?.join()
        ui.runOnIdle { vm.managerUpdate.value = null }
        ui.onNodeWithTag("manager-upgrade").assertDoesNotExist()
        val url = AppLinks.SOURCE + "/releases/tag/v1.0.0"
        Intents.init()
        try {
            Intents.intending(hasData(url)).respondWith(ActivityResult(Activity.RESULT_OK, null))
            ui.runOnIdle { vm.managerUpdate.value = ManagerUpdate("1.0.0", url) }
            ui.onNodeWithTag("manager-upgrade").assertTextContains("升级 1.0.0").performClick()
            org.junit.Assert.assertTrue(Intents.getIntents().any { it.dataString == url })
            ui.runOnIdle { vm.managerUpdate.value = null }
            ui.onNodeWithTag("manager-upgrade").assertDoesNotExist()
        } finally { Intents.release() }
    }
}
