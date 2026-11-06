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

/** Prepared for the device acceptance run; building this APK does not execute it. */
class NativeMarketplaceMigrationDeviceTest {
    @Test fun schema13PreservesProjectsGlobalChatsAndNativePublication() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "native-market-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(instrumentation.context.assets.open("io.github.easylsp.app.data.EasylspDatabase/13.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            db.execSQL("INSERT INTO projects (id,packageName,name,version) VALUES ('project','fixture.demo','kept','1')")
            db.execSQL("INSERT INTO plugins (id,projectId,name,version,author,description,desiredEnabled,revision,runtime,entry) VALUES ('plugin','project','native','1','author','kept',1,3,'NATIVE','src/main.c')")
            db.execSQL("INSERT INTO native_builds (buildId,pluginId,projectId,sourceRevision,sampleRevision,sdkVersion,toolchainVersion,abi,minApi,state,artifactPath,executionId,createdAt,error) VALUES ('build','plugin','project',3,0,'1','fixture','arm64-v8a',34,'SUCCEEDED','/fixed/plugin.so','',1000,'')")
            db.execSQL("INSERT INTO native_deployments (generation,projectId,uid,packageName,targetVersion,policy,snapshot,state,failedStep,error,createdAt) VALUES ('generation','project',10123,'fixture.demo',1,'NATIVE_ONLY','{}','PUBLISHED','','',1000)")
            db.execSQL("INSERT INTO conversations (id,projectId,providerId,model,title,sampleRevision,createdAt,scope) VALUES ('global',NULL,'provider','model','old global',0,1000,'GLOBAL')")
            db.version = 13
        }
        val store = ProjectStore(context, name, File(context.cacheDir, name))
        try {
            assertFalse(store.dao.project("project")!!.archived)
            assertEquals("LIBXPOSED", store.dao.project("project")!!.nativeBackend)
            assertEquals("NATIVE_AND_JAVA", store.dao.project("project")!!.nativePolicy)
            store.dao.archiveProject("project", true)
            assertTrue(store.dao.project("project")!!.archived)
            val restored = store.select("fixture.demo", "restored", "2")
            assertEquals("project", restored.id); assertFalse(restored.archived)
            val plugin = store.dao.plugin("plugin")!!
            assertEquals("LOCAL", plugin.source); assertEquals(3L, plugin.revision); assertTrue(plugin.desiredEnabled)
            assertEquals("{}", plugin.configJson); assertNull(plugin.releaseId)
            assertEquals("official", plugin.marketSourceId); assertEquals("app", plugin.nativeProcessType)
            assertEquals(0, restored.userId)
            val profile = store.select("fixture.demo", "work profile", "2", 10)
            assertNotEquals(restored.id, profile.id)
            assertEquals("ZYGISK", profile.nativeBackend); assertEquals("NATIVE_ONLY", profile.nativePolicy)
            assertEquals(restored.id, store.dao.projectByPackage("fixture.demo", 0)!!.id)
            assertEquals(profile.id, store.dao.projectByPackage("fixture.demo", 10)!!.id)
            assertEquals("LOCAL_BUILD", store.nativePlugins.build("build")!!.source)
            assertEquals("generation", store.nativePlugins.published("project")!!.generation)
            assertEquals("old global", store.workflows.conversation("global")!!.title)
            assertNull(store.workflows.conversation("global")!!.projectId)
        } finally { store.close(); context.deleteDatabase(name); File(context.cacheDir, name).deleteRecursively() }
    }
}
