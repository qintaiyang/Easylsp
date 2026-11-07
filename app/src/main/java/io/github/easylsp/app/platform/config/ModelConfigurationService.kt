package io.github.easylsp.app.platform.config

import android.content.Context
import io.github.easylsp.app.platform.ai.ModelCapabilities
import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.data.*
import kotlinx.coroutines.flow.combine
import org.json.*
import java.net.URI
import java.util.UUID

/** UI and tools share connection/model resolution and revision boundaries. */
class ModelConfigurationService(private val graph: EasylspApp) {
    private val dao get() = graph.store.workflows
    private val preferences = graph.getSharedPreferences("model-selection", Context.MODE_PRIVATE)
    private var migrated = preferences.getBoolean("connections-migrated-v13", false)
    @Synchronized fun migrateConnections() {
        if (migrated) return
        val representatives = mutableListOf<ProviderEntity>()
        graph.store.transaction {
            for (provider in dao.providers()) {
                val options = JSONObject(provider.options)
                val same = representatives.firstOrNull {
                    val other = JSONObject(it.options)
                    it.protocol == provider.protocol && normalized(it.baseUrl) == normalized(provider.baseUrl) &&
                        it.timeoutSeconds == provider.timeoutSeconds && other.optString("requestPath") == options.optString("requestPath") &&
                        other.optString("modelsPath") == options.optString("modelsPath") &&
                        graph.secrets.get(credential(it)) == graph.secrets.get(credential(provider))
                }
                if (same == null) representatives += provider else {
                    dao.models().filter { it.providerId == provider.id }.forEach { dao.saveModel(it.copy(providerId = same.id)) }
                    dao.allConversations().filter { it.providerId == provider.id }.forEach { dao.saveConversation(it.copy(providerId = same.id)) }
                    dao.deleteProvider(provider.id)
                }
            }
        }
        preferences.edit().putBoolean("connections-migrated-v13", true).commit()
        migrated = true
    }
    private fun normalized(value: String): String {
        val uri = URI(value.trim().trimEnd('/')).normalize()
        return URI(uri.scheme.lowercase(), uri.userInfo, uri.host?.lowercase(), uri.port, uri.path, uri.query, null).toString()
    }
    fun credential(provider: ProviderEntity) = provider.credentialRef.ifEmpty { "provider:${provider.id}" }
    fun resolve(modelId: String, requireEnabled: Boolean = true): ProviderEntity {
        migrateConnections()
        val model = requireNotNull(dao.model(modelId)) { "模型配置不存在" }
        val provider = requireNotNull(dao.provider(model.providerId)) { "供应商不存在" }
        require(!requireEnabled || (model.enabled && provider.enabled)) { "此模型或供应商已停用，请重新选择" }
        val options = ModelCapabilities.resolve(model.remoteModelId, provider.baseUrl, JSONObject(model.options))
        val connection = JSONObject(provider.options)
        listOf("requestPath", "modelsPath", "authMode").forEach { if (connection.has(it)) options.put(it, connection.get(it)) }
        return provider.copy(model = model.remoteModelId, options = options.toString(), maxSteps = model.maxSteps)
    }
    fun secret(modelId: String) = graph.secrets.get(credential(resolve(modelId)))
    fun selected(conversation: ConversationEntity) = conversation.modelConfigId.ifEmpty { conversation.providerId }
    fun clearProject(projectId: String) { preferences.edit().remove("project:$projectId").commit() }
    fun defaultModel(projectId: String?): String? {
        migrateConnections()
        val id = projectId?.let { preferences.getString("project:$it", null) } ?: preferences.getString("global", null)
        if (id != null) return id // A disabled explicit choice is reported; never silently change provider.
        return dao.models().firstOrNull { it.enabled && dao.provider(it.providerId)?.enabled == true }?.id
    }
    fun select(conversation: ConversationEntity, modelId: String, scope: String = "conversation"): JSONObject {
        val provider = resolve(modelId)
        when (scope) {
            "conversation" -> { preferences.edit().remove("reasoning:" + conversation.id).apply(); dao.saveConversation(conversation.copy(providerId = provider.id, modelConfigId = modelId, model = provider.model)) }
            "project" -> preferences.edit().putString("project:${requireNotNull(conversation.projectId) { "当前会话没有项目" }}", modelId).apply()
            "global" -> preferences.edit().putString("global", modelId).apply()
            else -> error("scope 必须为 conversation/project/global")
        }
        return JSONObject().put("modelConfigId", modelId).put("scope", scope).put("effectiveFrom", "next_turn")
    }
    fun configureProvider(id: String?, patch: JSONObject): ProviderEntity {
        val old = id?.let { requireNotNull(dao.provider(it)) } ?: ProviderEntity(UUID.randomUUID().toString(), "供应商", "openai", "", "")
        val options = JSONObject(old.options)
        listOf("requestPath", "modelsPath", "authMode").forEach { if (patch.has(it)) options.put(it, patch.getString(it)) }
        val value = old.copy(name = patch.optString("name", old.name), protocol = patch.optString("protocol", old.protocol),
            baseUrl = patch.optString("baseUrl", old.baseUrl).trimEnd('/'), enabled = patch.optBoolean("enabled", old.enabled),
            timeoutSeconds = patch.optInt("timeoutSeconds", old.timeoutSeconds), credentialRef = patch.optString("credentialRef", credential(old)), options = options.toString(), revision = old.revision + 1)
        require(value.protocol in setOf("openai", "anthropic", "gemini") && URI(value.baseUrl).scheme in setOf("http", "https")) { "请填写有效协议和 HTTP(S) 地址" }
        require(value.timeoutSeconds > 0)
        dao.saveProvider(value); return value
    }
    fun configureModel(id: String?, providerId: String, remoteId: String, patch: JSONObject, replaceOptions: Boolean = false): ProviderModelEntity {
        requireNotNull(dao.provider(providerId)) { "供应商不存在" }; require(remoteId.isNotBlank())
        val old = id?.let { requireNotNull(dao.model(it)) } ?: ProviderModelEntity(UUID.randomUUID().toString(), providerId, remoteId, remoteId)
        val options = if (replaceOptions) JSONObject() else JSONObject(old.options)
        listOf("capabilitySources", "capabilitiesUpdatedAt", "reasoningEfforts", "protocols", "maxInputTokens", "modelMaxOutputTokens", "contextWindow", "contextBudget", "maxOutputTokens", "supportsImages", "reasoningEffort", "thinkingBudget", "outputTokenField", "summaryMaxTokens", "maxParallelToolCalls").forEach {
            if (patch.has(it)) { if (patch.isNull(it)) options.remove(it) else options.put(it, patch.get(it)) }
        }
        val sources = options.optJSONObject("capabilitySources") ?: JSONObject()
        val previous = JSONObject(old.options)
        ModelCapabilities.fields.filter { patch.has(it) }.forEach { key ->
            if (patch.isNull(key)) sources.remove(key)
            else if (patch.optJSONObject("capabilitySources")?.has(key) != true && patch.opt(key).toString() != previous.opt(key)?.toString()) {
                sources.put(key, "user"); options.put("capabilitiesUpdatedAt", System.currentTimeMillis())
            }
        }
        options.put("capabilitySources", sources)
        val resolved = ModelCapabilities.resolve(remoteId, requireNotNull(dao.provider(providerId)).baseUrl, options)
        resolved.keys().forEach { options.put(it, resolved.get(it)) }
        if (patch.optBoolean("enabled", old.enabled)) {
            ModelCapabilities.validate(options)
            val provider = requireNotNull(dao.provider(providerId))
            val connection = JSONObject(provider.options)
            options.optJSONArray("protocols")?.let { protocols ->
                val api = when(provider.protocol) { "anthropic" -> "anthropic-messages"; "gemini" -> "gemini"; else -> if (connection.optString("requestPath").endsWith("/responses")) "openai-responses" else "openai-completions" }
                require((0 until protocols.length()).any { protocols.getString(it) == api }) { "模型不支持当前协议，请选择 " + protocols }
            }
        }
        val window = options.optInt("contextWindow")
        require(window >= 0 && (!options.has("contextBudget") || options.getInt("contextBudget") in 1..window)) { "上下文预算必须在真实容量内" }
        val value = old.copy(providerId = providerId, remoteModelId = remoteId, displayName = patch.optString("displayName", old.displayName), enabled = patch.optBoolean("enabled", old.enabled), options = options.toString(), maxSteps = patch.optInt("maxSteps", old.maxSteps), revision = old.revision + 1)
        require(value.maxSteps >= 0)
        dao.saveModel(value); return value
    }
    fun reasoning(conversationId: String): String = preferences.getString("reasoning:" + conversationId, "")!!
    fun setReasoning(conversation: ConversationEntity, effort: String) {
        val options = JSONObject(resolve(selected(conversation)).options)
        val supported = options.optJSONArray("reasoningEfforts") ?: JSONArray()
        require(effort.isEmpty() || (0 until supported.length()).any { supported.getString(it) == effort }) { "模型不支持此推理档位" }
        preferences.edit().putString("reasoning:" + conversation.id, effort).commit()
    }
    fun forConversation(conversation: ConversationEntity, requireEnabled: Boolean = true): ProviderEntity {
        val provider = resolve(selected(conversation), requireEnabled)
        val options = JSONObject(provider.options)
        val effort = reasoning(conversation.id)
        if (effort.isNotEmpty()) {
            val supported = options.optJSONArray("reasoningEfforts") ?: JSONArray()
            require((0 until supported.length()).any { supported.getString(it) == effort }) { "当前模型不支持会话推理档位，请选择自动" }
            options.put("reasoningEffort", effort)
        }
        return provider.copy(options = options.toString())
    }
    fun available() = combine(dao.observeProviders(), dao.observeModels()) { providers, models ->
        models.filter { it.enabled }.mapNotNull { model -> providers.firstOrNull { it.id == model.providerId && it.enabled }?.let {
            // Model choices keep their own stable ID. Resolve again at execution time.
            it.copy(id = model.id, credentialRef = credential(it), model = model.remoteModelId, options = ModelCapabilities.resolve(model.remoteModelId, it.baseUrl, JSONObject(model.options)).toString(), maxSteps = model.maxSteps).apply { connectionId = it.id; modelDisplayName = model.displayName }
        } }
    }
}
