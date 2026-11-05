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

class Phase5MigrationDeviceTest {
    @Test fun version11RetainsPermissionsMessagesAndAddsRecoverableProjectionFields() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "phase5-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("io.github.easylsp.app.data.EasylspDatabase/11.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            db.execSQL("INSERT INTO projects (id,packageName,name,version) VALUES ('p','fixture.package','fixture','1')")
            for ((id, allow) in listOf("allow" to 1, "confirm" to 0)) {
                db.execSQL("INSERT INTO conversations (id,projectId,providerId,model,title,sampleRevision,createdAt) VALUES ('$id','p','provider','fixture','old',1,1000)")
                db.execSQL("INSERT INTO harness_sessions (conversationId,sessionId,lastSeq,activeTurn,authorized) VALUES ('$id','engine-$id',50,1,$allow)")
                db.execSQL("INSERT INTO messages (conversationId,role,content,blocks,status,sampleRevision,createdAt,usage,engineMessageId) VALUES ('$id','user','saved-history','{}','COMPLETE',1,1000,'{}','message-$id')")
            }
            db.version = 11
        }
        val store = ProjectStore(context, name, File(context.cacheDir, name))
        try {
            assertEquals("ALLOW", store.workflows.conversation("allow")!!.authorizationMode)
            assertEquals("CONFIRM", store.workflows.conversation("confirm")!!.authorizationMode)
            assertTrue(store.workflows.harnessSession("allow")!!.authorized)
            assertFalse(store.workflows.harnessSession("confirm")!!.authorized)
            assertEquals("saved-history", store.workflows.history("allow").single().content)
            assertEquals(-1L, store.workflows.history("allow").single().eventSeq)
            assertEquals(0, store.workflows.harnessSession("allow")!!.projectionVersion)
        } finally { store.close(); context.deleteDatabase(name); File(context.cacheDir, name).deleteRecursively() }
    }
}
