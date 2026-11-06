package io.github.easylsp.app.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects", indices = [Index(value = ["packageName", "kind", "userId"], unique = true)])
data class ProjectEntity(@PrimaryKey val id: String, val packageName: String, val name: String, val version: String, @ColumnInfo(defaultValue = "'INSTALLED'") val kind: String = "INSTALLED", @ColumnInfo(defaultValue = "0") val sampleRevision: Long = 0, @ColumnInfo(defaultValue = "0") val lastOpenedAt: Long = 0, @ColumnInfo(defaultValue = "''") val groupName: String = "", @ColumnInfo(defaultValue = "0") val archived: Boolean = false, @ColumnInfo(defaultValue = "'LIBXPOSED'") val nativeBackend: String = "LIBXPOSED", @ColumnInfo(defaultValue = "'NATIVE_AND_JAVA'") val nativePolicy: String = "NATIVE_AND_JAVA", @ColumnInfo(defaultValue = "0") val userId: Int = 0)
@Entity(tableName = "plugins", foreignKeys = [ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)], indices = [Index("projectId")])
data class PluginEntity(@PrimaryKey val id: String, val projectId: String, val name: String, val version: String = "1.0", val author: String = "", val description: String = "", val desiredEnabled: Boolean = false, val revision: Long = 1, @ColumnInfo(defaultValue = "''") val processName: String = "", @ColumnInfo(defaultValue = "'exact'") val processScope: String = "exact", @ColumnInfo(defaultValue = "'BEANSHELL'") val runtime: String = "BEANSHELL", @ColumnInfo(defaultValue = "'main.java'") val entry: String = "main.java", @ColumnInfo(defaultValue = "'LOCAL'") val source: String = "LOCAL",
    val catalogPluginId: String? = null, val publisherId: String? = null, val releaseId: String? = null, val marketVersionCode: Long? = null,
    @ColumnInfo(defaultValue = "'{}'") val configJson: String = "{}", @ColumnInfo(defaultValue = "'{}'") val configSchema: String = "{}",
    @ColumnInfo(defaultValue = "0") val localModified: Boolean = false, @ColumnInfo(defaultValue = "'{}'") val releaseManifest: String = "{}", @ColumnInfo(defaultValue = "'official'") val marketSourceId: String = "official", @ColumnInfo(defaultValue = "'app'") val nativeProcessType: String = "app")
@Entity(tableName = "native_builds", indices = [Index("pluginId"), Index("projectId")])
data class NativeBuildEntity(@PrimaryKey val buildId: String, val pluginId: String, val projectId: String,
    val sourceRevision: Long, val sampleRevision: Long, val sdkVersion: String, val toolchainVersion: String,
    val abi: String = "arm64-v8a", val minApi: Int = 31, val state: String = "BUILDING", val artifactPath: String = "",
    val executionId: String = "", val createdAt: Long = System.currentTimeMillis(), val error: String = "", val releaseId: String? = null, @ColumnInfo(defaultValue = "'LOCAL_BUILD'") val source: String = "LOCAL_BUILD")
@Entity(tableName = "native_deployments", indices = [Index("projectId")])
data class NativeDeploymentEntity(@PrimaryKey val generation: String, val projectId: String, val uid: Int,
    val packageName: String, val targetVersion: Long, val policy: String, val snapshot: String,
    val state: String, val failedStep: String = "", val error: String = "", val createdAt: Long = System.currentTimeMillis())
@Dao interface NativePluginDao {
    @Upsert fun saveBuild(build: NativeBuildEntity)
    @Query("SELECT * FROM native_builds WHERE buildId = :id") fun build(id: String): NativeBuildEntity?
    @Query("SELECT * FROM native_builds WHERE pluginId = :id ORDER BY createdAt DESC") fun builds(id: String): List<NativeBuildEntity>
    @Query("SELECT * FROM native_builds WHERE projectId = :projectId") fun projectBuilds(projectId: String): List<NativeBuildEntity>
    @Query("SELECT * FROM native_builds ORDER BY createdAt DESC") fun observeBuilds(): Flow<List<NativeBuildEntity>>
    @Upsert fun saveDeployment(deployment: NativeDeploymentEntity)
    @Query("SELECT * FROM native_deployments WHERE projectId = :projectId AND state = 'PUBLISHED' ORDER BY createdAt DESC LIMIT 1") fun published(projectId: String): NativeDeploymentEntity?
    @Query("SELECT * FROM native_deployments WHERE projectId = :projectId ORDER BY createdAt DESC") fun deployments(projectId: String): List<NativeDeploymentEntity>
    @Query("SELECT * FROM native_deployments ORDER BY createdAt DESC") fun observeDeployments(): Flow<List<NativeDeploymentEntity>>
    @Query("DELETE FROM native_builds WHERE projectId = :projectId") fun deleteProjectBuilds(projectId: String)
    @Query("DELETE FROM native_deployments WHERE projectId = :projectId") fun deleteProjectDeployments(projectId: String)
    @Query("DELETE FROM native_builds WHERE pluginId = :id") fun deleteBuilds(id: String)
}
@Entity(tableName = "samples", primaryKeys = ["projectId", "revision"])
data class SampleEntity(val projectId: String, val revision: Long, val filesJson: String, val version: String, val createdAt: Long)
@Entity(tableName = "providers")
data class ProviderEntity(@PrimaryKey val id: String, val name: String, val protocol: String, val baseUrl: String, val model: String, val maxSteps: Int = 0, val timeoutSeconds: Int = 90, @ColumnInfo(defaultValue = "'{}'") val options: String = "{}", @ColumnInfo(defaultValue = "1") val enabled: Boolean = true, @ColumnInfo(defaultValue = "''") val credentialRef: String = "", @ColumnInfo(defaultValue = "1") val revision: Long = 1) { @Ignore var connectionId: String = id; @Ignore var modelDisplayName: String = model }
@Entity(tableName = "conversations", indices = [Index("projectId")])
data class ConversationEntity(@PrimaryKey val id: String, val projectId: String?, val providerId: String, val model: String, val title: String, val sampleRevision: Long, val createdAt: Long, @ColumnInfo(defaultValue = "0") val updatedAt: Long = createdAt, @ColumnInfo(defaultValue = "'normal'") val mode: String = "normal", @ColumnInfo(defaultValue = "''") val promptId: String = "", @ColumnInfo(defaultValue = "''") val promptText: String = "", @ColumnInfo(defaultValue = "''") val authorizationMode: String = "", @ColumnInfo(defaultValue = "'PROJECT'") val scope: String = "PROJECT", @ColumnInfo(defaultValue = "''") val modelConfigId: String = "", val parentConversationId: String? = null, @ColumnInfo(defaultValue = "0") val archived: Boolean = false, @ColumnInfo(defaultValue = "''") val task: String = "", @ColumnInfo(defaultValue = "'scope'") val historyAccess: String = "scope")
@Entity(tableName = "messages", indices = [Index("conversationId"), Index(value = ["conversationId", "engineMessageId"], unique = true)])
data class MessageEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val conversationId: String, val role: String, val content: String, val blocks: String = "{}", val status: String = "COMPLETE", val sampleRevision: Long, val sessionId: String?, val createdAt: Long = System.currentTimeMillis(), val usage: String = "{}", val engineMessageId: String? = null, @ColumnInfo(defaultValue = "-1") val eventSeq: Long = -1, @ColumnInfo(defaultValue = "''") val turnId: String = "", @ColumnInfo(defaultValue = "0") val step: Int = 0, @ColumnInfo(defaultValue = "''") val displayKey: String = "")
@Entity(tableName = "tool_runs", indices = [Index("conversationId")])
data class ToolRunEntity(@PrimaryKey val id: String, val conversationId: String, val toolCallId: String, val name: String, val arguments: String, val result: String = "", val status: String, val startedAt: Long = System.currentTimeMillis(), val finishedAt: Long? = null)
@Entity(tableName = "chat_turns", indices = [Index("conversationId")])
data class ChatTurnEntity(@PrimaryKey val id: String, val conversationId: String, val userMessageId: Long = 0,
    val status: String = "PREPARING", val phase: String = "PREPARING", val errorSource: String = "", val errorType: String = "", val errorDetail: String = "",
    val requestCount: Int = 0, val startedAt: Long = System.currentTimeMillis(), val finishedAt: Long? = null, @ColumnInfo(defaultValue = "-1") val startSeq: Long = -1)
@Entity(tableName = "chat_drafts")
data class ChatDraftEntity(@PrimaryKey val conversationId: String, val text: String = "", val attachments: String = "[]", val editedMessageId: Long? = null, val branchId: String? = null)
@Entity(tableName = "chat_inputs", indices = [Index("conversationId")])
data class ChatInputEntity(@PrimaryKey val id: String, val conversationId: String, val text: String, val attachments: String,
    val sessionId: String?, val authorized: Boolean, val createdAt: Long = System.currentTimeMillis(), val status: String = "QUEUED", @ColumnInfo(defaultValue = "'NEW_TASK'") val intent: String = "NEW_TASK")
@Entity(tableName = "chat_interactions", indices = [Index("conversationId")])
data class ChatInteractionEntity(@PrimaryKey val id: String, val conversationId: String, val turnId: String, val kind: String,
    val request: String, val response: String = "", val status: String = "PENDING", val createdAt: Long = System.currentTimeMillis())
@Entity(tableName = "chat_reading")
data class ChatReadingEntity(@PrimaryKey val conversationId: String, val itemKey: String, val offset: Int, val messageLimit: Int)
@Entity(tableName = "harness_sessions", foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["sessionId"], unique = true)])
data class HarnessSessionEntity(@PrimaryKey val conversationId: String, val sessionId: String, val lastSeq: Long = -1,
    val activeTurn: Int = 0, val targetSessionId: String? = null, val authorized: Boolean = false, val sourceConversationId: String? = null, @ColumnInfo(defaultValue = "0") val projectionVersion: Int = 0)
@Entity(tableName = "chat_branches", indices = [Index("conversationId")])
data class ChatBranchEntity(@PrimaryKey val id: String, val conversationId: String, val sessionId: String,
    val editedMessageId: Long, val createdAt: Long = System.currentTimeMillis())
@Entity(tableName = "chat_usage", primaryKeys = ["sessionId", "eventSeq"], indices = [Index("conversationId")])
data class ChatUsageEntity(val sessionId: String, val eventSeq: Long, val conversationId: String, val originKey: String,
    val turnId: String, val step: Int, val usage: String)
@Entity(tableName = "mcp_servers")
data class McpServerEntity(@PrimaryKey val id: String, val name: String, val url: String, val enabled: Boolean = true, @ColumnInfo(defaultValue = "'http'") val transport: String = "http", @ColumnInfo(defaultValue = "'{}'") val config: String = "{}", @ColumnInfo(defaultValue = "1") val revision: Long = 1)
@Entity(tableName = "tool_plugins")
data class ToolPluginEntity(@PrimaryKey val id: String, val name: String, val enabled: Boolean = false, val revision: Long = 1)
@Entity(tableName = "skills")
data class SkillEntity(@PrimaryKey val id: String, val name: String, val text: String, val enabled: Boolean = true, @ColumnInfo(defaultValue = "'text'") val runtime: String = "text", @ColumnInfo(defaultValue = "''") val source: String = "", @ColumnInfo(defaultValue = "120") val timeoutSeconds: Int = 120, @ColumnInfo(defaultValue = "''") val packagePath: String = "", @ColumnInfo(defaultValue = "''") val sourceUrl: String = "", @ColumnInfo(defaultValue = "''") val description: String = "", val projectId: String? = null, @ColumnInfo(defaultValue = "1") val revision: Long = 1)
@Entity(tableName = "provider_models", indices = [Index("providerId")])
data class ProviderModelEntity(@PrimaryKey val id: String, val providerId: String, val remoteModelId: String,
    val displayName: String, val enabled: Boolean = true, val options: String = "{}", val maxSteps: Int = 0, val revision: Long = 1)
@Entity(tableName = "memories", indices = [Index("projectId")])
data class MemoryEntity(@PrimaryKey val id: String, val projectId: String?, val title: String, val text: String,
    val sources: String = "[]", val sampleRevision: Long? = null, val revision: Long = 1,
    val updatedAt: Long = System.currentTimeMillis(), val rule: Boolean = false)
@Entity(tableName = "prompts")
data class PromptEntity(@PrimaryKey val id: String, val name: String, val text: String)
@Entity(tableName = "plan_items", indices = [Index("conversationId")])
data class PlanItemEntity(@PrimaryKey val id: String, val conversationId: String, val position: Int, val description: String, val status: String)

@Dao interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY name") fun snapshotProjects(): List<ProjectEntity>
    @Query("SELECT * FROM projects ORDER BY name") fun observeProjects(): Flow<List<ProjectEntity>>
    @Query("SELECT * FROM plugins ORDER BY name") fun observePlugins(): Flow<List<PluginEntity>>
    @Query("SELECT * FROM projects WHERE packageName = :packageName AND kind = 'INSTALLED' AND userId = :userId") fun projectByPackage(packageName: String, userId: Int = 0): ProjectEntity?
    @Query("UPDATE projects SET lastOpenedAt = :time WHERE id = :id") fun visit(id: String, time: Long)
    @Query("SELECT * FROM projects WHERE id = :id") fun project(id: String): ProjectEntity?
    @Query("SELECT * FROM plugins WHERE id = :id") fun plugin(id: String): PluginEntity?
    @Query("SELECT * FROM plugins WHERE projectId = :projectId ORDER BY name") fun plugins(projectId: String): List<PluginEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertProject(project: ProjectEntity)
    @Update fun updateProject(project: ProjectEntity)
    @Insert fun insertPlugin(plugin: PluginEntity)
    @Update fun updatePlugin(plugin: PluginEntity)
    @Query("DELETE FROM projects WHERE id = :id") fun deleteProject(id: String)
    @Query("UPDATE projects SET archived = :archived WHERE id = :id") fun archiveProject(id: String, archived: Boolean)
    @Query("DELETE FROM plugins WHERE id = :id") fun deletePlugin(id: String)
    @Insert fun insertSample(sample: SampleEntity)
    @Query("SELECT * FROM samples WHERE projectId = :projectId AND revision = :revision") fun sample(projectId: String, revision: Long): SampleEntity?
    @Query("SELECT * FROM samples") fun allSamples(): List<SampleEntity>
    @Query("DELETE FROM samples WHERE projectId = :projectId AND revision = :revision") fun deleteSample(projectId: String, revision: Long)
    @Query("DELETE FROM samples WHERE projectId = :projectId") fun deleteSamples(projectId: String)
}
@Dao interface WorkflowDao {
    @Query("SELECT * FROM chat_interactions WHERE status = 'PENDING'") fun observePendingInteractions(): Flow<List<ChatInteractionEntity>>
    @Query("SELECT * FROM providers ORDER BY name") fun providers(): List<ProviderEntity>
    @Query("SELECT * FROM provider_models ORDER BY displayName") fun models(): List<ProviderModelEntity>
    @Query("SELECT * FROM provider_models ORDER BY displayName") fun observeModels(): Flow<List<ProviderModelEntity>>
    @Query("SELECT * FROM provider_models WHERE id = :id") fun model(id: String): ProviderModelEntity?
    @Upsert fun saveModel(model: ProviderModelEntity)
    @Upsert fun saveConversation(conversation: ConversationEntity)
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC") fun observeAllConversations(): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE archived = 0 ORDER BY updatedAt DESC") fun observeConversations(): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM messages WHERE conversationId = :id AND id > :after ORDER BY id LIMIT :limit") fun historyPage(id: String, after: Long, limit: Int): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE conversationId = :id AND content LIKE :query AND id > :after ORDER BY id LIMIT :limit") fun searchMessages(id: String, query: String, after: Long, limit: Int): List<MessageEntity>
    @Query("SELECT * FROM skills ORDER BY name") fun skills(): List<SkillEntity>
    @Query("SELECT * FROM mcp_servers ORDER BY name") fun servers(): List<McpServerEntity>
    @Query("SELECT * FROM mcp_servers WHERE id = :id") fun server(id: String): McpServerEntity?
    @Query("SELECT * FROM memories WHERE projectId IS NULL OR projectId = :projectId ORDER BY updatedAt DESC") fun memories(projectId: String?): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE projectId IS NULL OR projectId = :projectId ORDER BY updatedAt DESC") fun observeMemories(projectId: String?): Flow<List<MemoryEntity>>
    @Query("SELECT * FROM memories WHERE (projectId IS NULL OR projectId = :projectId) AND (title LIKE :query ESCAPE '\\' OR text LIKE :query ESCAPE '\\') ORDER BY updatedAt DESC") fun searchMemories(projectId: String?, query: String): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE id = :id") fun memory(id: String): MemoryEntity?
    @Upsert fun saveMemory(memory: MemoryEntity)
    @Query("DELETE FROM memories WHERE id = :id") fun deleteMemory(id: String)

    @Query("UPDATE conversations SET authorizationMode = :mode WHERE id = :id") fun authorizationMode(id: String, mode: String)
    @Upsert fun saveBranch(branch: ChatBranchEntity)
    @Query("SELECT * FROM chat_branches WHERE conversationId = :id ORDER BY createdAt DESC") fun branches(id: String): Flow<List<ChatBranchEntity>>
    @Query("SELECT * FROM chat_branches WHERE conversationId = :id") fun branchHistory(id: String): List<ChatBranchEntity>
    @Query("SELECT * FROM messages WHERE id = :id") fun message(id: Long): MessageEntity?
    @Query("SELECT * FROM messages WHERE engineMessageId = :engineId AND conversationId IN (SELECT id FROM chat_branches WHERE conversationId = :id) LIMIT 1") fun archivedMessage(id: String, engineId: String): MessageEntity?
    @Query("UPDATE messages SET conversationId = :archive WHERE conversationId = :id") fun archiveMessages(id: String, archive: String)
    @Query("UPDATE tool_runs SET conversationId = :archive WHERE conversationId = :id") fun archiveTools(id: String, archive: String)
    @Query("UPDATE chat_turns SET conversationId = :archive WHERE conversationId = :id") fun archiveTurns(id: String, archive: String)
    @Query("UPDATE chat_interactions SET conversationId = :archive WHERE conversationId = :id") fun archiveInteractions(id: String, archive: String)
    @Query("UPDATE plan_items SET conversationId = :archive WHERE conversationId = :id") fun archivePlan(id: String, archive: String)
    @Query("SELECT * FROM chat_usage WHERE sessionId = :sessionId AND eventSeq = :seq") fun usageEntry(sessionId: String, seq: Long): ChatUsageEntity?
    @Query("SELECT COUNT(*) FROM chat_usage WHERE sessionId = :sessionId AND turnId = :turnId AND step = :step AND eventSeq < :seq") fun precedingResponses(sessionId: String, turnId: String, step: Int, seq: Long): Int
    @Upsert fun saveUsage(row: ChatUsageEntity)
    @Query("SELECT * FROM chat_usage WHERE conversationId = :id") fun usageHistory(id: String): List<ChatUsageEntity>
    @Query("SELECT * FROM chat_usage WHERE conversationId = :id") fun observeUsage(id: String): Flow<List<ChatUsageEntity>>
    @Query("WITH RECURSIVE descendants(id) AS (SELECT id FROM conversations WHERE parentConversationId = :id UNION ALL SELECT c.id FROM conversations c JOIN descendants d ON c.parentConversationId = d.id) SELECT * FROM chat_usage WHERE conversationId IN (SELECT id FROM descendants)")
    fun observeDescendantUsage(id: String): Flow<List<ChatUsageEntity>>
    @Query("DELETE FROM chat_usage WHERE conversationId = :id") fun deleteUsage(id: String)
    @Query("DELETE FROM chat_branches WHERE conversationId = :id") fun deleteBranches(id: String)
    @Upsert fun saveHarnessSession(session: HarnessSessionEntity)
    @Query("SELECT * FROM harness_sessions WHERE conversationId = :id") fun harnessSession(id: String): HarnessSessionEntity?
    @Query("SELECT * FROM harness_sessions WHERE conversationId = :id") fun observeHarnessSession(id: String): Flow<HarnessSessionEntity?>
    @Query("UPDATE harness_sessions SET authorized = :authorized WHERE conversationId = :id") fun authorizeTask(id: String, authorized: Boolean)
    @Query("SELECT * FROM messages WHERE conversationId = :id AND engineMessageId = :engineId") fun engineMessage(id: String, engineId: String): MessageEntity?
    @Query("SELECT * FROM chat_turns WHERE id = :id") fun turn(id: String): ChatTurnEntity?
    @Query("SELECT * FROM conversations") fun allConversations(): List<ConversationEntity>
    @Query("SELECT * FROM prompts ORDER BY name") fun observePrompts(): Flow<List<PromptEntity>>
    @Query("SELECT * FROM prompts WHERE id = :id") fun prompt(id: String): PromptEntity?
    @Upsert fun savePrompt(prompt: PromptEntity)
    @Query("DELETE FROM prompts WHERE id = :id") fun deletePrompt(id: String)
    @Query("UPDATE conversations SET mode = :mode, promptId = :promptId, promptText = :text WHERE id = :id") fun configureConversation(id: String, mode: String, promptId: String, text: String)
    @Query("SELECT * FROM providers ORDER BY name") fun observeProviders(): Flow<List<ProviderEntity>>
    @Query("SELECT * FROM providers WHERE id = :id") fun provider(id: String): ProviderEntity?
    @Upsert fun saveProvider(provider: ProviderEntity)
    @Query("DELETE FROM providers WHERE id = :id") fun deleteProvider(id: String)
    @Query("SELECT * FROM conversations WHERE projectId = :projectId ORDER BY updatedAt DESC") fun conversations(projectId: String): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE id = :id") fun observeConversation(id: String): Flow<ConversationEntity?>
    @Query("SELECT * FROM conversations WHERE id = :id") fun conversation(id: String): ConversationEntity?
    @Query("UPDATE conversations SET providerId = :providerId, model = :model WHERE id = :id") fun selectModel(id: String, providerId: String, model: String)
    @Query("UPDATE conversations SET title = :title, updatedAt = :time WHERE id = :id") fun renameConversation(id: String, title: String, time: Long)
    @Query("UPDATE conversations SET updatedAt = :time WHERE id = :id") fun touchConversation(id: String, time: Long)
    @Query("UPDATE conversations SET archived = :archived WHERE id = :id") fun archiveConversation(id: String, archived: Boolean)
    @Query("UPDATE conversations SET parentConversationId = NULL WHERE parentConversationId = :id") fun detachForks(id: String)
    @Query("UPDATE harness_sessions SET sourceConversationId = NULL WHERE sourceConversationId = :id") fun detachSources(id: String)
    @Query("DELETE FROM conversations WHERE id = :id") fun removeConversation(id: String)
    @Query("DELETE FROM conversations WHERE id = :id AND projectId IS NULL") fun deleteGlobalConversation(id: String)
    @Query("DELETE FROM conversations WHERE id = :id AND projectId = :projectId") fun deleteConversation(projectId: String, id: String)
    @Query("DELETE FROM messages WHERE conversationId = :id") fun deleteMessages(id: String)
    @Query("DELETE FROM tool_runs WHERE conversationId = :id") fun deleteToolRuns(id: String)
    @Insert fun insertConversation(conversation: ConversationEntity)
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id") fun messages(conversationId: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM (SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id DESC LIMIT :limit) ORDER BY id") fun messageWindow(conversationId: String, limit: Int): Flow<List<MessageEntity>>
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId") fun messageCount(conversationId: String): Flow<Int>
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id") fun history(conversationId: String): List<MessageEntity>
    @Insert fun insertMessage(message: MessageEntity): Long
    @Update fun updateMessage(message: MessageEntity)
    @Query("SELECT * FROM tool_runs WHERE conversationId = :conversationId ORDER BY startedAt") fun toolRuns(conversationId: String): Flow<List<ToolRunEntity>>
    @Query("SELECT * FROM tool_runs WHERE conversationId = :conversationId AND startedAt >= COALESCE((SELECT MIN(createdAt) FROM (SELECT createdAt FROM messages WHERE conversationId = :conversationId ORDER BY id DESC LIMIT :limit)), 0) ORDER BY startedAt") fun toolRunWindow(conversationId: String, limit: Int): Flow<List<ToolRunEntity>>
    @Upsert fun saveToolRun(run: ToolRunEntity)
    @Query("SELECT * FROM tool_runs WHERE id = :id") fun toolRun(id: String): ToolRunEntity?
    @Query("UPDATE tool_runs SET status = 'UNKNOWN', result = '轮次已结束，工具结果未确认；请先查询实际状态', finishedAt = :time WHERE conversationId = :conversationId AND status = 'RUNNING'") fun unknownTools(conversationId: String, time: Long)
    @Query("UPDATE messages SET status = 'INTERRUPTED' WHERE status = 'STREAMING' AND conversationId NOT IN (SELECT conversationId FROM harness_sessions)") fun interruptMessages()
    @Query("UPDATE tool_runs SET status = 'INTERRUPTED' WHERE status IN ('RUNNING', 'AWAITING_APPROVAL') AND conversationId NOT IN (SELECT conversationId FROM harness_sessions)") fun interruptTools()
    @Upsert fun saveTurn(turn: ChatTurnEntity)
    @Query("SELECT * FROM chat_turns WHERE conversationId = :id ORDER BY startedAt") fun turns(id: String): Flow<List<ChatTurnEntity>>
    @Query("SELECT * FROM chat_turns WHERE conversationId = :id ORDER BY startedAt") fun turnHistory(id: String): List<ChatTurnEntity>
    @Query("UPDATE chat_turns SET status = 'INTERRUPTED', errorSource = 'HOST', errorType = 'ProcessExit', errorDetail = '应用进程退出，上一轮已中断；继续时会查询已有状态，不重放工具', finishedAt = :time WHERE finishedAt IS NULL AND conversationId NOT IN (SELECT conversationId FROM harness_sessions)") fun interruptTurns(time: Long)
    @Query("DELETE FROM chat_turns WHERE conversationId = :id") fun deleteTurns(id: String)
    @Upsert fun saveDraft(draft: ChatDraftEntity)
    @Query("SELECT * FROM chat_drafts WHERE conversationId = :id") fun draft(id: String): ChatDraftEntity?
    @Query("DELETE FROM chat_drafts WHERE conversationId = :id") fun deleteDraft(id: String)
    @Query("SELECT * FROM chat_inputs WHERE id = :id") fun input(id: String): ChatInputEntity?
    @Insert fun insertInput(input: ChatInputEntity)
    @Query("SELECT * FROM chat_inputs WHERE conversationId = :id ORDER BY rowid") fun inputs(id: String): Flow<List<ChatInputEntity>>
    @Query("SELECT * FROM chat_inputs WHERE conversationId = :id AND status IN ('SENDING', 'UNKNOWN', 'HOST_QUEUED') ORDER BY rowid") fun pendingInputs(id: String): List<ChatInputEntity>
    @Query("UPDATE chat_inputs SET status = :status WHERE id = :id") fun inputStatus(id: String, status: String)
    @Query("SELECT * FROM chat_inputs WHERE conversationId = :id AND status = 'QUEUED' ORDER BY rowid LIMIT 1") fun nextInput(id: String): ChatInputEntity?
    @Query("UPDATE chat_inputs SET status = 'SENDING' WHERE id = :id AND status = 'QUEUED'") fun claimInput(id: String): Int
    @Query("UPDATE chat_inputs SET status = 'QUEUED' WHERE status = 'SENDING' AND conversationId NOT IN (SELECT conversationId FROM harness_sessions)") fun releaseInputs()
    @Query("DELETE FROM chat_inputs WHERE id = :id AND status = 'QUEUED'") fun cancelInput(id: String)
    @Query("DELETE FROM chat_inputs WHERE id = :id") fun deleteInput(id: String)
    @Query("DELETE FROM chat_inputs WHERE conversationId = :id") fun deleteInputs(id: String)
    @Query("SELECT * FROM chat_interactions WHERE conversationId = :id ORDER BY createdAt") fun interactionHistory(id: String): List<ChatInteractionEntity>
    @Upsert fun saveInteraction(interaction: ChatInteractionEntity)
    @Query("SELECT * FROM chat_interactions WHERE conversationId = :id ORDER BY createdAt") fun interactions(id: String): Flow<List<ChatInteractionEntity>>
    @Query("SELECT * FROM chat_interactions WHERE conversationId = :id AND createdAt >= COALESCE((SELECT MIN(createdAt) FROM (SELECT createdAt FROM messages WHERE conversationId = :id ORDER BY id DESC LIMIT :limit)), 0) ORDER BY createdAt") fun interactionWindow(id: String, limit: Int): Flow<List<ChatInteractionEntity>>
    @Query("SELECT * FROM chat_interactions WHERE id = :id") fun interaction(id: String): ChatInteractionEntity?
    @Query("UPDATE chat_interactions SET status = 'INTERRUPTED' WHERE status = 'PENDING' AND conversationId NOT IN (SELECT conversationId FROM harness_sessions)") fun interruptInteractions()
    @Query("DELETE FROM chat_interactions WHERE conversationId = :id") fun deleteInteractions(id: String)
    @Upsert fun saveReading(reading: ChatReadingEntity)
    @Query("SELECT * FROM chat_reading WHERE conversationId = :id") fun reading(id: String): ChatReadingEntity?
    @Query("DELETE FROM chat_reading WHERE conversationId = :id") fun deleteReading(id: String)
    @Query("SELECT * FROM mcp_servers ORDER BY name") fun observeServers(): Flow<List<McpServerEntity>>
    @Query("SELECT * FROM mcp_servers WHERE enabled = 1 ORDER BY name") fun enabledServers(): List<McpServerEntity>
    @Upsert fun saveServer(server: McpServerEntity)
    @Query("DELETE FROM mcp_servers WHERE id = :id") fun deleteServer(id: String)
    @Query("SELECT * FROM tool_plugins ORDER BY name") fun observeToolPlugins(): Flow<List<ToolPluginEntity>>
    @Query("SELECT * FROM tool_plugins WHERE enabled = 1 ORDER BY name") fun enabledToolPlugins(): List<ToolPluginEntity>
    @Query("SELECT * FROM tool_plugins WHERE id = :id") fun toolPlugin(id: String): ToolPluginEntity?
    @Upsert fun saveToolPlugin(plugin: ToolPluginEntity)
    @Query("DELETE FROM tool_plugins WHERE id = :id") fun deleteToolPlugin(id: String)
    @Query("SELECT * FROM skills ORDER BY name") fun observeSkills(): Flow<List<SkillEntity>>
    @Query("SELECT * FROM skills WHERE enabled = 1 ORDER BY name") fun enabledSkills(): List<SkillEntity>
    @Query("SELECT * FROM skills WHERE id = :id") fun skill(id: String): SkillEntity?
    @Upsert fun saveSkill(skill: SkillEntity)
    @Query("DELETE FROM skills WHERE id = :id") fun deleteSkill(id: String)
    @Query("SELECT * FROM plan_items WHERE conversationId = :conversationId ORDER BY position") fun planItems(conversationId: String): Flow<List<PlanItemEntity>>
    @Upsert fun savePlanItem(item: PlanItemEntity)
    @Query("DELETE FROM plan_items WHERE conversationId = :conversationId") fun clearPlan(conversationId: String)
}
@Database(entities = [ProjectEntity::class, PluginEntity::class, SampleEntity::class, ProviderEntity::class, ProviderModelEntity::class, MemoryEntity::class, ConversationEntity::class, MessageEntity::class, ToolRunEntity::class, McpServerEntity::class, ToolPluginEntity::class, SkillEntity::class, PlanItemEntity::class, PromptEntity::class, ChatTurnEntity::class, ChatDraftEntity::class, ChatInputEntity::class, ChatInteractionEntity::class, ChatReadingEntity::class, HarnessSessionEntity::class, DiagnosticEntity::class, NativeBuildEntity::class, NativeDeploymentEntity::class, ChatBranchEntity::class, ChatUsageEntity::class], version = 17, exportSchema = true)
abstract class EasylspDatabase : RoomDatabase() {
    abstract fun nativePlugins(): NativePluginDao
    abstract fun diagnostics(): DiagnosticDao
    abstract fun projects(): ProjectDao
    abstract fun workflows(): WorkflowDao
    companion object {
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN userId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("DROP INDEX index_projects_packageName_kind")
                db.execSQL("CREATE UNIQUE INDEX index_projects_packageName_kind_userId ON projects(packageName,kind,userId)")
                db.execSQL("ALTER TABLE plugins ADD COLUMN marketSourceId TEXT NOT NULL DEFAULT 'official'")
                db.execSQL("ALTER TABLE plugins ADD COLUMN nativeProcessType TEXT NOT NULL DEFAULT 'app'")
            }
        }
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN nativeBackend TEXT NOT NULL DEFAULT 'LIBXPOSED'")
                db.execSQL("ALTER TABLE projects ADD COLUMN nativePolicy TEXT NOT NULL DEFAULT 'NATIVE_AND_JAVA'")
            }
        }
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE plugins ADD COLUMN source TEXT NOT NULL DEFAULT 'LOCAL'")
                db.execSQL("ALTER TABLE plugins ADD COLUMN catalogPluginId TEXT")
                db.execSQL("ALTER TABLE plugins ADD COLUMN publisherId TEXT")
                db.execSQL("ALTER TABLE plugins ADD COLUMN releaseId TEXT")
                db.execSQL("ALTER TABLE plugins ADD COLUMN marketVersionCode INTEGER")
                db.execSQL("ALTER TABLE plugins ADD COLUMN configJson TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE plugins ADD COLUMN configSchema TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE plugins ADD COLUMN localModified INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE plugins ADD COLUMN releaseManifest TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE native_builds ADD COLUMN releaseId TEXT")
                db.execSQL("ALTER TABLE native_builds ADD COLUMN source TEXT NOT NULL DEFAULT 'LOCAL_BUILD'")
            }
        }
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE providers ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE providers ADD COLUMN credentialRef TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE providers ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
                db.execSQL("UPDATE providers SET credentialRef = 'provider:' || id")
                db.execSQL("CREATE TABLE provider_models (id TEXT NOT NULL PRIMARY KEY, providerId TEXT NOT NULL, remoteModelId TEXT NOT NULL, displayName TEXT NOT NULL, enabled INTEGER NOT NULL, options TEXT NOT NULL, maxSteps INTEGER NOT NULL, revision INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX index_provider_models_providerId ON provider_models(providerId)")
                db.execSQL("INSERT INTO provider_models SELECT id,id,model,model,1,options,maxSteps,1 FROM providers")
                // Preserve child bindings while changing the parent project's nullability.
                db.execSQL("CREATE TEMP TABLE saved_harness AS SELECT * FROM harness_sessions")
                db.execSQL("CREATE TABLE conversations_new (id TEXT NOT NULL PRIMARY KEY, projectId TEXT, providerId TEXT NOT NULL, model TEXT NOT NULL, title TEXT NOT NULL, sampleRevision INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL DEFAULT 0, mode TEXT NOT NULL DEFAULT 'normal', promptId TEXT NOT NULL DEFAULT '', promptText TEXT NOT NULL DEFAULT '', authorizationMode TEXT NOT NULL DEFAULT '', scope TEXT NOT NULL DEFAULT 'PROJECT', modelConfigId TEXT NOT NULL DEFAULT '', parentConversationId TEXT, archived INTEGER NOT NULL DEFAULT 0, task TEXT NOT NULL DEFAULT '', historyAccess TEXT NOT NULL DEFAULT 'scope')")
                db.execSQL("INSERT INTO conversations_new SELECT id,projectId,providerId,model,title,sampleRevision,createdAt,updatedAt,mode,promptId,promptText,authorizationMode,'PROJECT',providerId,NULL,0,'','scope' FROM conversations")
                db.execSQL("DROP TABLE conversations")
                db.execSQL("ALTER TABLE conversations_new RENAME TO conversations")
                db.execSQL("CREATE INDEX index_conversations_projectId ON conversations(projectId)")
                db.execSQL("INSERT OR REPLACE INTO harness_sessions SELECT * FROM saved_harness")
                db.execSQL("DROP TABLE saved_harness")
                db.execSQL("ALTER TABLE skills ADD COLUMN description TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE skills ADD COLUMN projectId TEXT")
                db.execSQL("ALTER TABLE skills ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE mcp_servers ADD COLUMN config TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE mcp_servers ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE TABLE memories (id TEXT NOT NULL PRIMARY KEY, projectId TEXT, title TEXT NOT NULL, text TEXT NOT NULL, sources TEXT NOT NULL, sampleRevision INTEGER, revision INTEGER NOT NULL, updatedAt INTEGER NOT NULL, rule INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX index_memories_projectId ON memories(projectId)")
            }
        }
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN authorizationMode TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE conversations SET authorizationMode = CASE WHEN id IN (SELECT conversationId FROM harness_sessions WHERE authorized = 1) THEN 'ALLOW' ELSE 'CONFIRM' END")
                db.execSQL("ALTER TABLE harness_sessions ADD COLUMN projectionVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN eventSeq INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE messages ADD COLUMN turnId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN step INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN displayKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chat_turns ADD COLUMN startSeq INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE chat_drafts ADD COLUMN editedMessageId INTEGER")
                db.execSQL("ALTER TABLE chat_drafts ADD COLUMN branchId TEXT")
                db.execSQL("CREATE TABLE chat_branches (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, sessionId TEXT NOT NULL, editedMessageId INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX index_chat_branches_conversationId ON chat_branches(conversationId)")
                db.execSQL("CREATE TABLE chat_usage (sessionId TEXT NOT NULL, eventSeq INTEGER NOT NULL, conversationId TEXT NOT NULL, originKey TEXT NOT NULL, turnId TEXT NOT NULL, step INTEGER NOT NULL, usage TEXT NOT NULL, PRIMARY KEY(sessionId, eventSeq))")
                db.execSQL("CREATE INDEX index_chat_usage_conversationId ON chat_usage(conversationId)")
            }
        }
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE plugins ADD COLUMN runtime TEXT NOT NULL DEFAULT 'BEANSHELL'")
                db.execSQL("ALTER TABLE plugins ADD COLUMN entry TEXT NOT NULL DEFAULT 'main.java'")
                db.execSQL("CREATE TABLE native_builds (buildId TEXT NOT NULL PRIMARY KEY, pluginId TEXT NOT NULL, projectId TEXT NOT NULL, sourceRevision INTEGER NOT NULL, sampleRevision INTEGER NOT NULL, sdkVersion TEXT NOT NULL, toolchainVersion TEXT NOT NULL, abi TEXT NOT NULL, minApi INTEGER NOT NULL, state TEXT NOT NULL, artifactPath TEXT NOT NULL, executionId TEXT NOT NULL, createdAt INTEGER NOT NULL, error TEXT NOT NULL)")
                db.execSQL("CREATE INDEX index_native_builds_pluginId ON native_builds(pluginId)")
                db.execSQL("CREATE INDEX index_native_builds_projectId ON native_builds(projectId)")
                db.execSQL("CREATE TABLE native_deployments (generation TEXT NOT NULL PRIMARY KEY, projectId TEXT NOT NULL, uid INTEGER NOT NULL, packageName TEXT NOT NULL, targetVersion INTEGER NOT NULL, policy TEXT NOT NULL, snapshot TEXT NOT NULL, state TEXT NOT NULL, failedStep TEXT NOT NULL, error TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX index_native_deployments_projectId ON native_deployments(projectId)")
            }
        }
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE diagnostics (id TEXT NOT NULL PRIMARY KEY, projectId TEXT NOT NULL, sampleRevision INTEGER NOT NULL, packageName TEXT NOT NULL, mode TEXT NOT NULL, startedAt INTEGER NOT NULL, finishedAt INTEGER, state TEXT NOT NULL, executionId TEXT)")
                db.execSQL("CREATE INDEX index_diagnostics_projectId ON diagnostics(projectId)")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE chat_inputs ADD COLUMN intent TEXT NOT NULL DEFAULT 'NEW_TASK'") }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN engineMessageId TEXT")
                db.execSQL("CREATE UNIQUE INDEX index_messages_conversationId_engineMessageId ON messages(conversationId, engineMessageId)")
                db.execSQL("CREATE TABLE harness_sessions (conversationId TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, lastSeq INTEGER NOT NULL, activeTurn INTEGER NOT NULL, targetSessionId TEXT, authorized INTEGER NOT NULL, sourceConversationId TEXT, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX index_harness_sessions_sessionId ON harness_sessions(sessionId)")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE chat_inputs (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, text TEXT NOT NULL, attachments TEXT NOT NULL, sessionId TEXT, authorized INTEGER NOT NULL, createdAt INTEGER NOT NULL, status TEXT NOT NULL)")
                db.execSQL("CREATE INDEX index_chat_inputs_conversationId ON chat_inputs(conversationId)")
                db.execSQL("CREATE TABLE chat_interactions (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, turnId TEXT NOT NULL, kind TEXT NOT NULL, request TEXT NOT NULL, response TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX index_chat_interactions_conversationId ON chat_interactions(conversationId)")
                db.execSQL("CREATE TABLE chat_reading (conversationId TEXT NOT NULL PRIMARY KEY, itemKey TEXT NOT NULL, offset INTEGER NOT NULL, messageLimit INTEGER NOT NULL)")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE chat_turns (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, userMessageId INTEGER NOT NULL, status TEXT NOT NULL, phase TEXT NOT NULL, errorSource TEXT NOT NULL, errorType TEXT NOT NULL, errorDetail TEXT NOT NULL, requestCount INTEGER NOT NULL, startedAt INTEGER NOT NULL, finishedAt INTEGER)")
                db.execSQL("CREATE INDEX index_chat_turns_conversationId ON chat_turns(conversationId)")
                db.execSQL("CREATE TABLE chat_drafts (conversationId TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL, attachments TEXT NOT NULL)")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN groupName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE plugins ADD COLUMN processScope TEXT NOT NULL DEFAULT 'exact'")
                db.execSQL("ALTER TABLE providers ADD COLUMN options TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE conversations ADD COLUMN mode TEXT NOT NULL DEFAULT 'normal'")
                db.execSQL("ALTER TABLE conversations ADD COLUMN promptId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN promptText TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE mcp_servers ADD COLUMN transport TEXT NOT NULL DEFAULT 'http'")
                db.execSQL("ALTER TABLE skills ADD COLUMN packagePath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE skills ADD COLUMN sourceUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE prompts (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, text TEXT NOT NULL)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN lastOpenedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE conversations SET updatedAt = createdAt")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE skills ADD COLUMN runtime TEXT NOT NULL DEFAULT 'text'")
                db.execSQL("ALTER TABLE skills ADD COLUMN source TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE skills ADD COLUMN timeoutSeconds INTEGER NOT NULL DEFAULT 120")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN kind TEXT NOT NULL DEFAULT 'INSTALLED'")
                db.execSQL("ALTER TABLE projects ADD COLUMN sampleRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("DROP INDEX index_projects_packageName")
                db.execSQL("CREATE UNIQUE INDEX index_projects_packageName_kind ON projects(packageName, kind)")
                db.execSQL("ALTER TABLE plugins ADD COLUMN processName TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE samples (projectId TEXT NOT NULL, revision INTEGER NOT NULL, filesJson TEXT NOT NULL, version TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(projectId, revision))")
                db.execSQL("CREATE TABLE providers (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, protocol TEXT NOT NULL, baseUrl TEXT NOT NULL, model TEXT NOT NULL, maxSteps INTEGER NOT NULL, timeoutSeconds INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY, projectId TEXT NOT NULL, providerId TEXT NOT NULL, model TEXT NOT NULL, title TEXT NOT NULL, sampleRevision INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX index_conversations_projectId ON conversations(projectId)")
                db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conversationId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, blocks TEXT NOT NULL, status TEXT NOT NULL, sampleRevision INTEGER NOT NULL, sessionId TEXT, createdAt INTEGER NOT NULL, usage TEXT NOT NULL)")
                db.execSQL("CREATE INDEX index_messages_conversationId ON messages(conversationId)")
                db.execSQL("CREATE TABLE tool_runs (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, toolCallId TEXT NOT NULL, name TEXT NOT NULL, arguments TEXT NOT NULL, result TEXT NOT NULL, status TEXT NOT NULL, startedAt INTEGER NOT NULL, finishedAt INTEGER)")
                db.execSQL("CREATE INDEX index_tool_runs_conversationId ON tool_runs(conversationId)")
                db.execSQL("CREATE TABLE mcp_servers (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL, enabled INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE tool_plugins (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, enabled INTEGER NOT NULL, revision INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE skills (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, text TEXT NOT NULL, enabled INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE plan_items (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, position INTEGER NOT NULL, description TEXT NOT NULL, status TEXT NOT NULL)")
                db.execSQL("CREATE INDEX index_plan_items_conversationId ON plan_items(conversationId)")
            }
        }
    }
}
