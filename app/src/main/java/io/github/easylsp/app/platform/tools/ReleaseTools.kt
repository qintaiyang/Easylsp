package io.github.easylsp.app.platform.tools

import io.github.easylsp.app.EasylspApp
import org.json.JSONObject

class ReleaseTools(private val graph: EasylspApp) {
    private fun receipt(binding: RunBinding, id: String): JSONObject {
        val record = graph.releasePreparations.status(id)
        require(binding.projectBinding == null || binding.projectBinding.id == record.getString("projectId")) { "发行不属于当前项目" }
        return record
    }
    fun definitions(binding: RunBinding): List<ToolDefinition> = listOf(
        ToolDefinition("activate_plugin_release", "Enable an installed fixed release with its pinned dependencies in dependency order, or disable/delete its complete joint runtime group. Native changes apply to the next process; does not restart the target. operation: enable, disable, delete.",
            BuiltinToolSchemas.create("activate_plugin_release", mapOf("pluginId" to "string", "operation" to "string"), listOf("pluginId", "operation")), writes = true,
            invoke = { args ->
                val plugin = requireNotNull(graph.store.dao.plugin(args.getString("pluginId")))
                require(plugin.releaseId != null && (binding.projectBinding == null || binding.projectBinding.id == plugin.projectId)) { "固定发行不属于当前项目" }
                when (args.getString("operation")) {
                    "enable" -> graph.releaseActivation.enable(plugin)
                    "disable" -> graph.releaseActivation.disable(plugin)
                    "delete" -> graph.releaseActivation.disable(plugin, true)
                    else -> error("operation必须为enable/disable/delete")
                }
            }, groupId = "release.local"),
        ToolDefinition("prepare_plugin_release", "Prepare a fixed local v3 ZIP from an explicit pluginId and successful native buildId (required for NATIVE). manifest is the complete release metadata. Works in independent conversations with real explicit plugin identity. Does not upload, submit a PR or enable plugins. Read plugin-release reference first.",
            BuiltinToolSchemas.create("prepare_plugin_release", mapOf("pluginId" to "string", "buildId" to "string", "manifest" to "object", "javaPluginId" to "string"), listOf("pluginId", "manifest")), writes = true,
            preview = { args ->
                val plugin = requireNotNull(graph.store.dao.plugin(args.getString("pluginId")))
                "准备固定本地发行 · ${plugin.name} r${plugin.revision} · buildId=${args.optString("buildId", "Java source")}\n${args.getJSONObject("manifest").toString(2)}"
            }, invoke = { args ->
                val plugin = requireNotNull(graph.store.dao.plugin(args.getString("pluginId"))) { "插件不存在" }
                require(binding.projectBinding == null || binding.projectBinding.id == plugin.projectId) { "插件不属于当前项目" }
                graph.releasePreparations.prepare(plugin.id, args.optString("buildId").ifEmpty { null }, args.getJSONObject("manifest"), args.optString("javaPluginId").ifEmpty { null })
            }, groupId = "release.local"),
        ToolDefinition("get_plugin_release_status", "Read the fixed local preparation receipt. PREPARED_LOCAL does not mean uploaded, submitted, approved or indexed.",
            BuiltinToolSchemas.create("get_plugin_release_status", mapOf("preparationId" to "string"), listOf("preparationId")), invoke = { args ->
                val record = graph.releasePreparations.status(args.getString("preparationId"))
                require(binding.projectBinding == null || binding.projectBinding.id == record.getString("projectId")) { "发行不属于当前项目" }; record
            }, groupId = "release.local"),
        ToolDefinition("publish_plugin_release", "Upload or resume an immutable prepared release to the logged-in author's public GitHub repository. Requires a preparationId, owner/repo and full sourceCommit. Credentials are configured only in the author UI; never request or pass tokens. Returns real fixed release/asset links; upload does not mean market approval.",
            BuiltinToolSchemas.create("publish_plugin_release", mapOf("preparationId" to "string", "repository" to "string", "sourceCommit" to "string"), listOf("preparationId", "repository", "sourceCommit")), writes = true,
            preview = { args -> val record = receipt(binding, args.getString("preparationId")); "公开上传固定发行 · ${args.getString("repository")}\n源码 ${args.getString("sourceCommit")}\n${record.getJSONObject("manifest").toString(2)}\n大小 ${record.getLong("archiveBytes")} 字节" },
            invoke = { args -> receipt(binding, args.getString("preparationId")); graph.releasePublishing.publish(args.getString("preparationId"), args.getString("repository"), args.getString("sourceCommit")) }, groupId = "release.github"),
        ToolDefinition("submit_market_release", "Submit or resume a market PR for the fixed uploaded preparation. Creates a fork/branch/PR under the already logged-in author. A pending or merged PR does not mean indexed. Returns real PR links. No token arguments.",
            BuiltinToolSchemas.create("submit_market_release", mapOf("preparationId" to "string"), listOf("preparationId")), writes = true,
            preview = { args -> val record = receipt(binding, args.getString("preparationId")); "投稿到 qintaiyang/Easylsp-Market\n${record.optString("releaseUrl", "尚未上传")}\n${record.getJSONObject("manifest").toString(2)}" },
            invoke = { args -> receipt(binding, args.getString("preparationId")); graph.releasePublishing.submit(args.getString("preparationId")) }, groupId = "release.github"),
        ToolDefinition("get_market_submission_status", "Query fixed GitHub PR and verified official catalog status: UPLOADED, PENDING_REVIEW, CLOSED, MERGED_AWAITING_INDEX, INDEXED or WITHDRAWN. Waiting for human review does not keep a model task running. Network errors remain explicit.",
            BuiltinToolSchemas.create("get_market_submission_status", mapOf("preparationId" to "string"), listOf("preparationId")),
            invoke = { args -> receipt(binding, args.getString("preparationId")); graph.releasePublishing.status(args.getString("preparationId")) }, groupId = "release.github")
    )
}
