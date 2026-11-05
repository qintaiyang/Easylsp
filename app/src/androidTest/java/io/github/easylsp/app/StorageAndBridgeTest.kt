package io.github.easylsp.app

import android.os.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.easylsp.api.internal.Protocol
import io.github.easylsp.api.internal.ipc.IHostCallback
import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.bridge.BridgeHub
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.util.*
import java.util.zip.*

class StorageAndBridgeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val suffix = UUID.randomUUID().toString()
    private val root = File(context.cacheDir, suffix)
    private val store = ProjectStore(context, "test-$suffix.db", root)
    @After fun close() { store.close(); context.deleteDatabase("test-$suffix.db"); root.deleteRecursively() }
    @Test fun revisionsRemainImmutableAndImportDefaultsDisabled() = runBlocking(Dispatchers.IO) {
        val project = store.select(context.packageName, "self", "1")
        val plugin = store.create(project, "test", "void onLoad() {}")
        store.save(plugin.id, "changed", "", "", "2", "void onLoad() { plugin.log(42); }")
        assertEquals("void onLoad() {}", store.sourceFile(plugin).readText())
        assertEquals(2L, store.dao.plugin(plugin.id)!!.revision)
        val bytes = ByteArrayOutputStream(); store.exportPlugin(plugin.id, bytes)
        val imported = store.importPlugin(project, ByteArrayInputStream(bytes.toByteArray()))
        assertNotEquals(plugin.id, imported.id); assertFalse(imported.desiredEnabled)
        assertEquals("void onLoad() { plugin.log(42); }", store.read(imported.id))
    }
    @Test fun nativeSourcesRoundTripAndNeverEnterJavaBridge() = runBlocking(Dispatchers.IO) {
        val project = store.select(context.packageName, "self", "1")
        val plugin = store.create(project, "native", "int old;", runtime = "NATIVE")
        store.save(plugin.id, "native", "", "", "1", "int next;", files = mapOf("src/helper.c" to "int helper;", "include/helper.h" to "int helper(void);"))
        val next = requireNotNull(store.dao.plugin(plugin.id))
        assertEquals("int old;", store.sourceFile(plugin).readText())
        assertEquals("int next;", store.readFiles(next).getValue("src/main.c"))
        val bytes = ByteArrayOutputStream(); store.exportPlugin(plugin.id, bytes)
        val imported = store.importPlugin(project, ByteArrayInputStream(bytes.toByteArray()))
        assertEquals("NATIVE", imported.runtime); assertFalse(imported.desiredEnabled)
        assertEquals(store.readFiles(next), store.readFiles(imported))
        val hub = BridgeHub(context, store, LogStore(context))
        val callback = object : IHostCallback.Stub() { override fun onRequest(id: String, session: String, operation: String, args: Bundle, script: ParcelFileDescriptor?) {} }
        val session = hub.binder.registerHost(Protocol.VERSION, context.packageName, context.packageName, android.os.Process.myPid(), callback)
        assertTrue(hub.binder.listPlugins(session).isEmpty())
        assertThrows(SecurityException::class.java) { hub.binder.openPlugin(session, plugin.id, 1) }
        Unit
    }

    @Test fun importRejectsDirectoryTraversal() = runBlocking(Dispatchers.IO) {
        val project = store.select(context.packageName, "self", "1")
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry("../outside.java")); it.write("bad".toByteArray()); it.closeEntry() }
        try { store.importPlugin(project, ByteArrayInputStream(bytes.toByteArray())); fail("must reject unrecognized path") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("路径")) }
        catch (expected: ZipException) { assertTrue(expected.message!!.contains("../outside.java")) }
        assertFalse(File(root.parentFile, "outside.java").exists())
        Unit
    }
    @Test fun bridgeChecksUidProjectAndRequestedRevision() = runBlocking(Dispatchers.IO) {
        val hub = BridgeHub(context, store, LogStore(context))
        val callback = object : IHostCallback.Stub() { override fun onRequest(id: String, session: String, operation: String, args: Bundle, script: ParcelFileDescriptor?) {} }
        try { hub.binder.registerHost(Protocol.VERSION, "android", "android", android.os.Process.myPid(), callback); fail("must reject claimed package") }
        catch (expected: SecurityException) { assertTrue(expected.message!!.contains("UID")) }
        try { hub.binder.registerHost(Protocol.VERSION, context.packageName, context.packageName, android.os.Process.myPid(), callback); fail("must reject unconfigured target") }
        catch (expected: SecurityException) { assertTrue(expected.message!!.contains("选择")) }
        val project = store.select(context.packageName, "self", "1")
        val other = store.select("other.project", "other", "1")
        val ownPlugin = store.create(project, "own", "revision 1")
        val otherPlugin = store.create(other, "other", "private")
        store.save(ownPlugin.id, "own", "", "", "1", "revision 2")
        val session = hub.binder.registerHost(Protocol.VERSION, context.packageName, context.packageName, android.os.Process.myPid(), callback)
        assertEquals(1, hub.binder.listPlugins(session).size)
        ParcelFileDescriptor.AutoCloseInputStream(hub.binder.openPlugin(session, ownPlugin.id, 1)).use { assertEquals("revision 1", it.bufferedReader().readText()) }
        try { hub.binder.openPlugin(session, otherPlugin.id, 1); fail("must reject other project's source") }
        catch (expected: SecurityException) { assertTrue(expected.message!!.contains("其他项目")) }
        val next = hub.binder.registerHost(Protocol.VERSION, context.packageName, context.packageName, android.os.Process.myPid(), callback)
        assertNotEquals(session, next)
        try { hub.binder.listPlugins(session); fail("must reject stale session") }
        catch (expected: SecurityException) { assertTrue(expected.message!!.contains("结束")) }
        Unit
    }
    @Test fun processBindingsAndStaleSessionNeverRouteToOtherProcess() = runBlocking(Dispatchers.IO) {
        val hub = BridgeHub(context, store, LogStore(context))
        val callback = object : IHostCallback.Stub() { override fun onRequest(id: String, session: String, operation: String, args: Bundle, script: ParcelFileDescriptor?) {} }
        val project = store.select(context.packageName, "self", "1")
        val mainPlugin = store.create(project, "main", "main source")
        val workerPlugin = store.create(project, "worker", "worker source", processName = "${context.packageName}:worker")
        val main = hub.binder.registerHost(Protocol.VERSION, context.packageName, context.packageName, android.os.Process.myPid(), callback)
        val worker = hub.binder.registerHost(Protocol.VERSION, context.packageName, "${context.packageName}:worker", android.os.Process.myPid(), callback)
        assertEquals(2, hub.sessions.value.size)
        assertEquals(mainPlugin.id, hub.binder.listPlugins(main).single().getString("pluginId"))
        assertEquals(workerPlugin.id, hub.binder.listPlugins(worker).single().getString("pluginId"))
        try { hub.binder.openPlugin(main, workerPlugin.id, 1); fail("cross process source") } catch (expected: SecurityException) { }
        val next = hub.binder.registerHost(Protocol.VERSION, context.packageName, "${context.packageName}:worker", android.os.Process.myPid(), callback)
        assertNotEquals(worker, next); assertNotNull(hub.sessions.value[main])
        try { hub.commandSession(worker, "loadPlugin", workerPlugin.id); fail("stale session") } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("断开")) }
        Unit
    }

    @Test fun migrationPreservesExistingProjectPluginAndAddsWorkflowTables() = runBlocking(Dispatchers.IO) {
        val name = "migration-$suffix.db"
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val schema = org.json.JSONObject(instrumentation.context.assets.open("io.github.easylsp.app.data.EasylspDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            db.execSQL("INSERT INTO projects VALUES ('old-project', 'test.package', 'existing', '1')")
            db.execSQL("INSERT INTO plugins (id, projectId, name, version, author, description, desiredEnabled, revision) VALUES ('old-plugin', 'old-project', 'existing', '1', '', '', 1, 4)")
            db.version = 1
        }
        val migrated = ProjectStore(context, name, File(root, "migration"))
        try {
            assertEquals("INSTALLED", migrated.dao.project("old-project")!!.kind)
            assertEquals(0L, migrated.dao.project("old-project")!!.sampleRevision)
            assertEquals(4L, migrated.dao.plugin("old-plugin")!!.revision)
            assertEquals("BEANSHELL", migrated.dao.plugin("old-plugin")!!.runtime)
            assertEquals("main.java", migrated.dao.plugin("old-plugin")!!.entry)
            assertTrue(migrated.dao.plugin("old-plugin")!!.desiredEnabled)
            assertTrue(migrated.nativePlugins.builds("old-plugin").isEmpty())
            assertEquals("", migrated.dao.plugin("old-plugin")!!.processName)
            migrated.workflows.saveProvider(ProviderEntity("p", "provider", "openai", "https://example.invalid/v1", "model"))
            assertEquals("model", migrated.workflows.provider("p")!!.model)
        } finally { migrated.close(); context.deleteDatabase(name) }
        Unit
    }

}
