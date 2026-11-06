package io.github.easylsp.app.platform.tools

import io.github.easylsp.app.EasylspApp
import io.github.easylsp.app.platform.market.GitHubAuthoringService
import io.github.easylsp.app.platform.market.GitHubClient
import org.json.JSONObject

class GitHubAuthorTools(private val graph: EasylspApp) {
    fun definitions(binding: RunBinding): List<ToolDefinition> {
        val author = GitHubAuthoringService(graph.github.client, graph.github::profile)
        return listOf(
            ToolDefinition("get_github_account", "Read the saved GitHub author's public identity and whether an account is configured. Uses the same saved account as repository, source and release tools; does not perform a live token check. Never returns a token.",
                BuiltinToolSchemas.create("get_github_account", emptyMap(), emptyList()),
                invoke = { graph.github.profile().let { it.put("configured", it.has("id")) } }, groupId = "release.github"),
            ToolDefinition("configure_github_account", "Use a GitHub token the user has explicitly authorized. With no arguments, reads the latest user-supplied GitHub token in this conversation, including ask_user answers. Alternatively supply sourceMessageId of their message or an existing credentialRef. Internally verifies and saves the token for all author tools without repeating it in arguments/results. A token supplied in chat is usable. Do not ask for it again when already configured.",
                BuiltinToolSchemas.create("configure_github_account", mapOf("sourceMessageId" to "integer", "credentialRef" to "string"), emptyList()), writes = true,
                preview = { "配置 GitHub 作者账号 · 使用指定用户消息或已有凭据，不显示令牌" }, invoke = { args ->
                    require(!(args.has("sourceMessageId") && args.has("credentialRef"))) { "sourceMessageId 和 credentialRef 不能同时指定" }
                    val token = if (args.has("sourceMessageId")) {
                        val message = requireNotNull(graph.store.workflows.message(args.getLong("sourceMessageId"))) { "用户消息不存在" }
                        require(message.conversationId == binding.conversationId && message.role == "user") { "凭据只能来自当前会话的用户消息" }
                        GitHubClient.tokenFromMessage(message.content)
                    } else if (args.has("credentialRef")) graph.secrets.get(args.getString("credentialRef")) else {
                        val messages = graph.store.workflows.history(binding.conversationId).filter { it.role == "user" }.map { it.createdAt to it.content }
                        val answers = graph.store.workflows.interactionHistory(binding.conversationId)
                            .filter { it.kind == "QUESTION" && it.status == "ANSWERED" }
                            .map { it.createdAt to JSONObject(it.response).optString("text") }
                        GitHubClient.tokenFromUserHistory(messages + answers)
                    }
                    graph.github.tokenLogin(token)
                }, groupId = "release.github"),
            ToolDefinition("create_github_repository", "Create a GitHub repository under the configured author account. Use for an authorized request to create/publish a project; no browser, accessibility service or manual repository creation is needed. private defaults to false for public market releases. Returns the real repository URL; does not upload files.",
                BuiltinToolSchemas.create("create_github_repository", mapOf("name" to "string", "description" to "string", "private" to "boolean"), listOf("name")), writes = true,
                preview = { args -> "创建${if (args.optBoolean("private")) "私有" else "公开"} GitHub 仓库 · ${args.getString("name")}" },
                invoke = { args -> author.createRepository(args.getString("name"), args.optString("description"), args.optBoolean("private")) }, groupId = "release.github"),
            ToolDefinition("commit_github_source", "Commit explicitly selected workspace files to the configured author's GitHub repository using the Git API. Supports empty repositories and existing branches, preserves other remote files and never force pushes. paths lists individual relative files; include the actual source, dependencies and license needed by the project, exclude development notes, conversations and credentials. Returns a real sourceCommit/sourceUrl for prepare_plugin_release and publish_plugin_release. No local git setup or manual source submission is required.",
                BuiltinToolSchemas.create("commit_github_source", mapOf("repository" to "string", "paths" to "array", "message" to "string", "branch" to "string"), listOf("repository", "paths", "message")), writes = true,
                preview = { args -> "提交源码 · ${args.getString("repository")} · ${args.optString("branch", "默认分支")}\n${args.getJSONArray("paths")}\n${args.getString("message")}" }, invoke = { args ->
                    val paths = args.getJSONArray("paths")
                    author.commitSource(graph.executions.directory(binding.workspace.id), args.getString("repository"),
                        (0 until paths.length()).map { paths.getString(it) }, args.getString("message"), args.optString("branch").ifEmpty { null })
                }, groupId = "release.github")
        )
    }
}
