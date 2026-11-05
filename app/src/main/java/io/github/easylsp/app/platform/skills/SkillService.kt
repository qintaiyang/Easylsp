package io.github.easylsp.app.platform.skills

import io.github.easylsp.app.platform.tasks.FileTaskService
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject

/** Dedicated process: arbitrary BeanShell loops cannot be stopped by thread interruption alone. */
class SkillService : FileTaskService() {
    override fun onTaskCancelled() { android.os.Process.killProcess(android.os.Process.myPid()) }
    override suspend fun execute(payload: JSONObject, progress: (String) -> Unit): JSONObject = runInterruptible {
        SkillScript.run(payload.getString("source"), payload.getJSONObject("arguments"), payload.getString("workspace"), progress)
    }
}
