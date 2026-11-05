package io.github.easylsp.app

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.app.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class Phase6MigrationDeviceTest {
    @Test fun version12PreservesModelParametersHistoryAndHarnessForeignKey() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "phase6-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("io.github.easylsp.app.data.EasylspDatabase/12.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            db.execSQL("INSERT INTO projects (id,packageName,name,version) VALUES ('project','fixture.package','fixture','1')")
            for ((id, window) in listOf("one" to 128000, "two" to 200000)) {
                db.execSQL("INSERT INTO providers (id,name,protocol,baseUrl,model,maxSteps,timeoutSeconds,options) VALUES (?,?,'openai','https://example.com',?,0,90,?)", arrayOf(id, id, "model-$id", "{\"contextWindow\":$window,\"contextBudget\":64000,\"maxOutputTokens\":8192}"))
            }
            db.execSQL("INSERT INTO conversations (id,projectId,providerId,model,title,sampleRevision,createdAt) VALUES ('chat','project','two','model-two','saved',1,1000)")
            db.execSQL("INSERT INTO harness_sessions (conversationId,sessionId,lastSeq,activeTurn,authorized) VALUES ('chat','engine',42,1,1)")
            db.execSQL("INSERT INTO messages (conversationId,role,content,blocks,status,sampleRevision,createdAt,usage) VALUES ('chat','user','saved-history','{}','COMPLETE',1,1000,'{}')")
            db.version = 12
        }
        val store = ProjectStore(context, name, File(context.cacheDir, name))
        try {
            assertEquals(2, store.workflows.models().size)
            assertEquals(128000, JSONObject(store.workflows.model("one")!!.options).getInt("contextWindow"))
            assertEquals(200000, JSONObject(store.workflows.model("two")!!.options).getInt("contextWindow"))
            assertEquals("provider:two", store.workflows.provider("two")!!.credentialRef)
            assertEquals("two", store.workflows.conversation("chat")!!.modelConfigId)
            assertEquals("PROJECT", store.workflows.conversation("chat")!!.scope)
            assertEquals(42L, store.workflows.harnessSession("chat")!!.lastSeq)
            assertEquals("saved-history", store.workflows.history("chat").single().content)
            store.workflows.insertConversation(ConversationEntity("global", null, "one", "model-one", "quick", 0, 1000, scope = "GLOBAL", modelConfigId = "one"))
            assertNull(store.workflows.conversation("global")!!.projectId)
            store.workflows.saveMemory(MemoryEntity("global-memory", null, "pref", "Chinese"))
            store.workflows.saveMemory(MemoryEntity("project-memory", "project", "fact", "class", sampleRevision = 1))
            assertEquals(1, store.workflows.memories(null).size); assertEquals(2, store.workflows.memories("project").size)
        } finally { store.close(); context.deleteDatabase(name); File(context.cacheDir, name).deleteRecursively() }
    }
}
