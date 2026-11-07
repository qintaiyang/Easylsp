package io.github.easylsp.app.platform.tools

import org.json.JSONArray
import org.json.JSONObject

/** Conversation selections are independent of tool enablement and execution authorization. */
class ToolCapabilities(
    registry: List<ToolDefinition>,
    private val unavailable: Map<String, String> = emptyMap(),
    saved: JSONObject = JSONObject(),
    private val persist: (JSONObject) -> Unit = {},
    private val scope: String = "PROJECT",
    private val loadSkill: ((String) -> SkillGuidance)? = null,
    private val groups: Map<String, List<String>> = emptyMap(),
    private val skillRevision: ((String) -> Long?)? = null,
    private val selectionChanged: (Set<String>) -> Unit = {},
    private val decorate: (ToolDefinition) -> ToolDefinition = { it }
) {
    private val registry = registry.toMutableList()
    private val dynamic = linkedMapOf<String, ToolDefinition>()
    @Synchronized fun contains(name: String) = name in names
    @Synchronized fun additionalTools() = dynamic.values.toList()
    @Synchronized fun register(tools: List<ToolDefinition>) {
        for (tool in tools) { registry.removeAll { it.name == tool.name }; registry.add(tool); dynamic[tool.name] = decorate(tool) }; version++; save()
    }
    @Synchronized fun unregister(names: Set<String>) {
        registry.removeAll { it.name in names }; names.forEach(dynamic::remove); version++; save()
    }
    private val skillSelections = saved.optJSONObject("skills") ?: JSONObject()
    private val discovery = setOf("search_tools", "select_tools") + if (loadSkill == null) emptySet() else setOf("activate_skill", "release_skill")
    private val defaults = registry.filter { it.defaultAvailable }.map { it.name }.toSet() + discovery
    private val selected = saved.optJSONArray("selectedTools").strings().toMutableSet()
    private var version = saved.optLong("toolSetVersion", 0)
    private var active = saved.optJSONArray("activeTools").strings().toSet()
    private var activeVersion = saved.optLong("activeVersion", -1)
    private val names get() = registry.map { it.name }.toSet() + discovery
    private val catalogState get() = JSONArray(registry.sortedBy { it.name }.map {
        JSONArray(listOf(it.name, it.defaultAvailable, unavailable[it.name] ?: ""))
    }).toString()

    init {
        if (saved.has("catalogState") && saved.getString("catalogState") != catalogState) { version++; save() }
    }

    fun definitions(): List<ToolDefinition> {
        val searchSchema = BuiltinToolSchemas.create("search_tools", mapOf("query" to "string", "cursor" to "integer", "limit" to "integer"), listOf("query"))
        val selectSchema = BuiltinToolSchemas.create("select_tools", mapOf("names" to "array", "mode" to "string", "includeState" to "boolean"), listOf("names"))
        selectSchema.getJSONObject("properties").getJSONObject("names").put("items", JSONObject().put("type", "string"))
        selectSchema.getJSONObject("properties").getJSONObject("mode").put("enum", JSONArray(listOf("add", "remove", "replace")))
        return listOf(
            ToolDefinition("search_tools", "Search the actual tool registry by name, purpose, group or source. Continue with nextCursor; no Skill is required. Unavailable tools include the actual reason.", searchSchema, invoke = { search(it) }, groupId = "capabilities", defaultAvailable = true),
            ToolDefinition("select_tools", "Select known tools for the next step. Default mode add; remove/replace affect only explicit selections, not defaults or Skills. Returns only effective additions/removals and unavailable names. To inspect full state, pass names=[] and includeState=true. Call selected tools after their definitions appear next step.", selectSchema, invoke = { select(it) }, groupId = "capabilities", defaultAvailable = true)
        ) + if (loadSkill == null) emptyList() else listOf(
            ToolDefinition("activate_skill", "Read Skill guidance and add suggested tool groups for the next step. Missing tools do not prevent reading. Optional groups may be combined. Repeated use of the same revision does not append instructions again.", BuiltinToolSchemas.create("activate_skill", mapOf("skillId" to "string", "optionalGroups" to "array"), listOf("skillId")), invoke = { activate(it.getString("skillId"), it.optJSONArray("optionalGroups").strings()) }, groupId = "capabilities", defaultAvailable = true),
            ToolDefinition("release_skill", "Release this Skill's tool references. Explicitly selected or default tools remain available. User-pinned Skills must be released by the user.", BuiltinToolSchemas.create("release_skill", mapOf("skillId" to "string"), listOf("skillId")), invoke = { release(it.getString("skillId")) }, groupId = "capabilities", defaultAvailable = true)
        )
    }

    @Synchronized fun search(args: JSONObject): JSONObject {
        val query = args.getString("query").trim()
        val offset = args.optInt("cursor", 0)
        val limit = args.optInt("limit", 5)
        require(offset >= 0 && limit > 0) { "cursor 必须 >= 0；limit 必须 > 0" }
        val terms = query.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val matches = (registry + definitions()).filter { tool ->
            val text = "${tool.name} ${tool.description} ${tool.groupId} ${tool.source}"
            terms.all { text.contains(it, ignoreCase = true) }
        }.sortedBy { it.name }
        val page = matches.drop(offset).take(limit)
        return JSONObject().put("tools", JSONArray(page.map { tool ->
            JSONObject().put("name", tool.name).put("description", tool.description).put("source", tool.source).put("groupId", tool.groupId)
                .put("availability", availability(tool.name)).put("selected", tool.name in effective())
        })).put("nextCursor", if (page.size < matches.size - offset) offset + page.size else JSONObject.NULL)
    }

    @Synchronized fun select(args: JSONObject): JSONObject {
        val requested = args.getJSONArray("names").strings().toSet()
        val mode = args.optString("mode", "add")
        require(mode in setOf("add", "remove", "replace")) { "mode 必须为 add、remove 或 replace" }
        val accepted = requested.filter { it in names && it !in unavailable }.toSet()
        val previous = selected.toSet()
        val before = effective()
        when (mode) {
            "replace" -> { selected.clear(); selected.addAll(accepted) }
            "remove" -> selected.removeAll(requested)
            else -> selected.addAll(accepted)
        }
        if (selected != previous) { version++; save() }
        val result = JSONObject().put("toolSetVersion", version).put("effectiveFrom", "next_step")
            .put("added", JSONArray((effective() - before).sorted())).put("removed", JSONArray((before - effective()).sorted()))
            .put("unavailableTools", JSONArray((requested - accepted).map {
            JSONObject().put("name", it).put("availability", availability(it))
        }))
        if (args.optBoolean("includeState")) result.put("state", snapshot())
        return result
    }

    /** Called once by SDK tool resolution; the entire tool batch uses this same admission. */
    @Synchronized fun resolve(): JSONObject {
        val next = effective()
        if (active != next || activeVersion != version) {
            active = next; activeVersion = version; save(); selectionChanged(next)
        }
        return snapshot()
    }

    @Synchronized fun permits(name: String) = name in active && name !in unavailable && name in names

    @Synchronized fun snapshot(): JSONObject = JSONObject()
        .put("toolSetVersion", version).put("selectedTools", JSONArray(effective().sorted()))
        .put("skills", JSONObject(skillSelections.toString())).put("activeVersion", activeVersion).put("activeTools", JSONArray(active.sorted()))
        .put("selections", JSONArray((defaults + selected + skillTools()).sorted().map { name ->
            JSONObject().put("name", name).put("sources", JSONArray(buildList {
                if (name in defaults) add("default")
                if (name in selected) add("model")
                skillSelections.keys().forEach { id -> if (name in skillSelections.getJSONObject(id).getJSONArray("tools").strings()) add("skill:$id") }
            })).put("availability", availability(name))
        }))

    @Synchronized fun activate(id: String, optionalGroups: List<String> = emptyList(), pinned: Boolean = false): JSONObject {
        val skill = requireNotNull(loadSkill)(id)
        val optional = optionalGroups.filter { it in skill.optionalGroups }
        val suggested = (skill.groups + optional).distinct()
        val requested = if (scope in skill.scopes) suggested.flatMap { groups[it] ?: registry.filter { tool -> tool.groupId == it }.map { tool -> tool.name } }.toSet() else emptySet()
        val previous = skillSelections.optJSONObject(id)
        val alreadyRead = previous?.optLong("revision") == skill.revision
        val activation = JSONObject().put("activationId", previous?.optString("activationId") ?: java.util.UUID.randomUUID().toString())
            .put("revision", skill.revision).put("tools", JSONArray(requested.sorted())).put("groups", JSONArray(suggested)).put("pinned", pinned || previous?.optBoolean("pinned") == true)
        skillSelections.put(id, activation); version++; save()
        return snapshot().put("activationId", activation.getString("activationId")).put("revision", skill.revision).put("alreadyActive", alreadyRead)
            .put("instructions", if (alreadyRead) JSONObject.NULL else skill.instructions).put("effectiveFrom", "next_step")
            .put("unavailableTools", JSONArray(requested.filter { it !in names || it in unavailable }.map { JSONObject().put("name", it).put("availability", availability(it)) }))
            .put("unavailableGroups", JSONArray(suggested.filter { group -> groups[group].isNullOrEmpty() && registry.none { it.groupId == group } }))
            .put("scopeAvailable", scope in skill.scopes)
    }
    @Synchronized fun release(id: String, user: Boolean = false): JSONObject {
        val selection = skillSelections.optJSONObject(id)
        if (selection?.optBoolean("pinned") == true && !user) return snapshot().put("released", false).put("pinned", true)
        if (skillSelections.remove(id) != null) { version++; save() }
        return snapshot().put("released", true).put("effectiveFrom", "next_step")
    }
    private fun skillTools(): Set<String> = skillSelections.keys().asSequence().filter { skillRevision == null || skillRevision.invoke(it) == skillSelections.getJSONObject(it).optLong("revision") }.flatMap { skillSelections.getJSONObject(it).getJSONArray("tools").strings().asSequence() }.toSet()

    private fun effective() = (defaults + selected + skillTools()).filter { it in names && it !in unavailable }.toSet()
    private fun availability(name: String): JSONObject = when {
        name !in names -> JSONObject().put("available", false).put("reason", "工具未注册")
        name in unavailable -> JSONObject().put("available", false).put("reason", unavailable.getValue(name))
        else -> JSONObject().put("available", true)
    }
    private fun save() = persist(JSONObject().put("selectedTools", JSONArray(selected.sorted())).put("toolSetVersion", version)
        .put("activeTools", JSONArray(active.sorted())).put("activeVersion", activeVersion).put("catalogState", catalogState).put("skills", skillSelections))

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
}

data class BoundToolCatalog(val tools: List<ToolDefinition>, val capabilities: ToolCapabilities)

data class SkillGuidance(val id: String, val revision: Long, val instructions: String, val groups: List<String>, val optionalGroups: List<String>, val scopes: List<String>)
