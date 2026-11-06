package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.platform.nativeplugins.TargetApplications
import org.junit.Assert.*
import org.junit.Test

/** Read the controlled Demo in a temporary profile created by the acceptance script. */
class M7TargetDeviceTest {
    @get:org.junit.Rule(order = 0) val visible = VisibleActivityRule()
    @get:org.junit.Rule(order = 1) val activity = androidx.test.ext.junit.rules.ActivityScenarioRule(MainActivity::class.java)
    @Test fun rootQueryPreservesProfileIdentity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = instrumentation.targetContext.applicationContext as EasylspApp
        val user = InstrumentationRegistry.getArguments().getString("m7UserId")!!.toInt()
        val targets = TargetApplications(graph)
        val owner = targets.info("io.github.easylsp.demo", 0)
        val profile = targets.info("io.github.easylsp.demo", user)
        assertEquals(user, profile.applicationInfo!!.uid / 100000)
        assertEquals(owner.applicationInfo!!.uid % 100000, profile.applicationInfo!!.uid % 100000)
        assertNotEquals(owner.applicationInfo!!.uid, profile.applicationInfo!!.uid)
        assertEquals(owner.longVersionCode, profile.longVersionCode)
        assertEquals(owner.applicationInfo!!.sourceDir, profile.applicationInfo!!.sourceDir)
        assertTrue(profile.applicationInfo!!.sourceDir.endsWith("/base.apk"))
    }
}
