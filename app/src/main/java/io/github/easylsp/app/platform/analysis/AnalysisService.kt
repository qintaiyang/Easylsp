package io.github.easylsp.app.platform.analysis
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.platform.tasks.FileTaskService
import org.json.JSONObject
class AnalysisService : FileTaskService() {
    companion object { private val engine = AnalysisEngine() }
    override suspend fun execute(payload: JSONObject, progress: (String) -> Unit): JSONObject {
        val projectId = payload.getString("projectId"); val revision = payload.getLong("sampleRevision")
        val sample = requireNotNull((application as EasylspApp).store.dao.sample(projectId, revision)) { "请先采集或导入 APK 样本" }
        return engine.execute(sample, payload.getString("action"), payload.optJSONObject("arguments") ?: JSONObject(), progress)
    }
}
