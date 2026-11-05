package io.github.easylsp.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.util.UUID

class UiWorkflowDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun textBundleRoundTripAndConversationDeletionStayInProject() = runBlocking(Dispatchers.IO) {
        val name = "ui-workflow-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, name)
        val store = ProjectStore(context, name, root)
        try {
            val project = store.select("demo.one", "one", "1")
            val other = store.select("demo.two", "two", "1")
            val plugin = store.create(project, "bundle", "original")
            val files = mapOf("main.java" to "changed", "notes/" to "", "notes/说明.json" to "{\"ok\":true}", "smali/Test.smali" to ".class public LTest;")
            store.save(plugin.id, plugin.name, "author", "description", "2", "changed", files = files)
            assertEquals("original", store.sourceFile(plugin).readText())
            val library = InstrumentationRegistry.getInstrumentation().context.assets.open("library-fixture.dex").use { it.readBytes() }
            val withLibrary = store.addLibrary(plugin.id, "fixture.dex", library)
            store.dao.updatePlugin(withLibrary.copy(processScope = "children"))
            val bytes = ByteArrayOutputStream(); store.exportPlugin(plugin.id, bytes)
            val imported = store.importPlugin(project, ByteArrayInputStream(bytes.toByteArray()))
            assertEquals(files.getValue("notes/说明.json"), store.readFiles(imported).getValue("notes/说明.json"))
            assertEquals("changed", store.read(imported.id)); assertFalse(imported.desiredEnabled)
            assertEquals("children", imported.processScope); assertEquals("fixture.dex", store.libraries(imported).single().name)
            val dao = store.workflows
            val c1 = ConversationEntity("c1", project.id, "provider", "model", "one", 1, 1)
            val c2 = c1.copy(id = "c2", projectId = other.id)
            dao.insertConversation(c1); dao.insertConversation(c2)
            listOf("c1", "c2").forEach { id -> dao.insertMessage(MessageEntity(conversationId = id, role = "user", content = id, sampleRevision = 1, sessionId = null)); dao.saveToolRun(ToolRunEntity(id, id, id, "read", "{}", status = "COMPLETE")); dao.savePlanItem(PlanItemEntity(id, id, 0, "plan", "pending")) }
            store.transaction { dao.deleteMessages("c1"); dao.deleteToolRuns("c1"); dao.clearPlan("c1"); dao.deleteConversation(project.id, "c1") }
            assertNull(dao.conversation("c1")); assertTrue(dao.history("c1").isEmpty()); assertNull(dao.toolRun("c1")); assertTrue(dao.planItems("c1").first().isEmpty())
            assertEquals(1, dao.history("c2").size); assertNotNull(dao.toolRun("c2")); assertEquals(1, dao.planItems("c2").first().size)
        } finally { store.close(); context.deleteDatabase(name); root.deleteRecursively() }
    }
    @Test fun schemaThreeUpgradePreservesExistingWorkflowData() = runBlocking(Dispatchers.IO) {
        val name = "ui-migration-${UUID.randomUUID()}.db"
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("io.github.easylsp.app.data.EasylspDatabase/3.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        context.getDatabasePath(name).parentFile!!.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) { val entity = entities.getJSONObject(i); val table = entity.getString("tableName"); db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table)); val indices = entity.getJSONArray("indices"); for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
            db.execSQL("INSERT INTO projects VALUES ('p', 'demo.one', 'existing', '1', 'INSTALLED', 2)")
            db.execSQL("INSERT INTO plugins VALUES ('plugin', 'p', 'old', '1', 'author', 'description', 1, 4, '')")
            db.execSQL("INSERT INTO providers VALUES ('provider', 'old model', 'openai', 'http://localhost/v1', 'model', 8, 90)")
            db.execSQL("INSERT INTO conversations VALUES ('c', 'p', 'provider', 'model', 'existing chat', 2, 1234)")
            db.execSQL("INSERT INTO messages VALUES (1, 'c', 'user', 'old message', '{}', 'COMPLETE', 2, NULL, 1234, '{}')")
            db.execSQL("INSERT INTO samples VALUES ('p', 2, '[]', '1', 1234)")
            db.version = 3
        }
        val root = File(context.cacheDir, name)
        val store = ProjectStore(context, name, root)
        try {
            assertEquals(0L, store.dao.project("p")!!.lastOpenedAt); store.dao.visit("p", 5678); assertEquals(5678L, store.dao.project("p")!!.lastOpenedAt)
            assertEquals(1234L, store.workflows.conversation("c")!!.updatedAt); assertEquals("old message", store.workflows.history("c").single().content)
            assertEquals(4L, store.dao.plugin("plugin")!!.revision); assertTrue(store.dao.plugin("plugin")!!.desiredEnabled)
            assertEquals("model", store.workflows.provider("provider")!!.model); assertNotNull(store.dao.sample("p", 2))
            assertEquals("exact", store.dao.plugin("plugin")!!.processScope)
            assertEquals("", store.dao.project("p")!!.groupName)
            assertEquals("{}", store.workflows.provider("provider")!!.options)
            assertEquals("normal", store.workflows.conversation("c")!!.mode)
            store.workflows.saveDraft(ChatDraftEntity("c", "中文草稿", "[]"))
            store.workflows.insertInput(ChatInputEntity("z-first", "c", "补充", "[]", null, true, createdAt = 1000))
            store.workflows.insertInput(ChatInputEntity("a-second", "c", "随后补充", "[]", null, false, createdAt = 1000))
            store.workflows.saveInteraction(ChatInteractionEntity("question", "c", "turn", "QUESTION", "{\"question\":\"继续吗\"}"))
            store.workflows.saveReading(ChatReadingEntity("c", "message:1", 45, 160))
            store.workflows.saveHarnessSession(HarnessSessionEntity("c", "engine-session", targetSessionId = "target-process", authorized = true))
            store.workflows.saveTurn(ChatTurnEntity("engine-turn", "c", status = "RUNNING"))
            store.workflows.insertConversation(store.workflows.conversation("c")!!.copy(id = "legacy"))
            store.workflows.saveTurn(ChatTurnEntity("legacy-turn", "legacy", status = "RUNNING"))
            val reopened = ProjectStore(context, name, root)
            try {
                assertEquals("中文草稿", reopened.workflows.draft("c")!!.text)
                assertTrue(reopened.workflows.nextInput("c")!!.authorized)
                assertEquals("z-first", reopened.workflows.nextInput("c")!!.id)
                assertEquals("PENDING", reopened.workflows.interaction("question")!!.status)
                assertEquals(45, reopened.workflows.reading("c")!!.offset)
                assertEquals("target-process", reopened.workflows.harnessSession("c")!!.targetSessionId)
                assertTrue(reopened.workflows.harnessSession("c")!!.authorized)
                reopened.workflows.interruptTurns(5678)
                assertEquals("RUNNING", reopened.workflows.turn("engine-turn")!!.status)
                assertEquals("INTERRUPTED", reopened.workflows.turn("legacy-turn")!!.status)
            } finally { reopened.close() }
        } finally { store.close(); context.deleteDatabase(name); root.deleteRecursively() }
    }
}
