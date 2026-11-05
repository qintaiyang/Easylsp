package io.github.easylsp.app.platform.analysis
import android.content.Context
import io.github.easylsp.app.platform.tasks.RemoteTaskClient
import org.json.JSONObject
class AnalysisClient(context: Context) {
    private val remote = RemoteTaskClient(context, AnalysisService::class.java)
    suspend fun call(projectId: String, revision: Long, action: String, arguments: JSONObject = JSONObject(), progress: (String) -> Unit = {}): JSONObject = remote.call(JSONObject().put("projectId", projectId).put("sampleRevision", revision).put("action", action).put("arguments", arguments), progress)
}
