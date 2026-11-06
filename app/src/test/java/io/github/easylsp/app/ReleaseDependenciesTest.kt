package io.github.easylsp.app

import io.github.easylsp.app.data.*
import io.github.easylsp.app.platform.market.MarketSources
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64

class ReleaseDependenciesTest {
    private fun manifest(id: String, release: String = "one") = JSONObject().put("publisherId","author").put("catalogPluginId",id).put("releaseId",release)
    private fun pin(id: String, release: String = "one", source: String = "official") = manifest(id,release).put("sourceId",source)
    private fun row(id: String, manifest: JSONObject, runtime: String = "NATIVE", enabled: Boolean = false, source: String = "official") = PluginEntity(id,"project",id,"1","author","",runtime=runtime,source="MARKET",catalogPluginId=manifest.getString("catalogPluginId"),publisherId="author",releaseId=manifest.getString("releaseId"),releaseManifest=manifest.toString(),desiredEnabled=enabled,marketSourceId=source)
    @Test fun topologySourcesCyclesAndConflictingPins() {
        val child=manifest("child")
        val root=manifest("root").put("dependencies",JSONArray().put(pin("child",source="third")))
        val order=ReleaseDependencies.resolve("official" to root) { assertEquals("third",it.identity.sourceId); child }
        assertEquals(listOf("third","official"),order.map { it.first })
        child.put("dependencies",JSONArray().put(pin("root")))
        assertThrows(IllegalArgumentException::class.java) { ReleaseDependencies.resolve("official" to root) { if (it.identity.catalogPluginId=="child") child else root } }
        child.remove("dependencies")
        val middle=manifest("middle").put("dependencies",JSONArray().put(pin("child","two", "third")))
        root.getJSONArray("dependencies").put(pin("middle"))
        assertThrows(IllegalArgumentException::class.java) { ReleaseDependencies.resolve("official" to root) { if (it.identity.catalogPluginId=="middle") middle else manifest("child",it.releaseId) } }
    }
    @Test fun jointComponentsAndActiveDependentsMustStopTogether() {
        val joint=manifest("joint")
        val native=row("native",joint,enabled=true)
        val java=row("java",joint,"BEANSHELL",true)
        val dependent=row("dependent",manifest("dependent").put("dependencies",JSONArray().put(pin("joint"))),enabled=true)
        assertEquals(2,ReleaseDependencies.components(native,listOf(native,java)).size)
        assertThrows(IllegalArgumentException::class.java) { ReleaseDependencies.requireCanDisable(native,listOf(native,java)) }
        ReleaseDependencies.requireCanDisableSet(listOf(native,java),listOf(native,java))
        assertThrows(IllegalArgumentException::class.java) { ReleaseDependencies.requireCanDisableSet(listOf(native,java),listOf(native,java,dependent)) }
        ReleaseDependencies.requireCanDisableSet(listOf(native,java),listOf(native,java,dependent.copy(desiredEnabled=false)))
        ReleaseDependencies.requireCanDisable(native.copy(marketSourceId="third"),listOf(native.copy(marketSourceId="third"),dependent))
    }
    @Test fun sourceTrustIsExplicitHttpsAndEd25519() {
        val key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded
        val config=JSONObject().put("catalogUrl","https://example.org/catalog.json").put("keys",JSONObject().put("test",Base64.getEncoder().encodeToString(key)))
        assertArrayEquals(key,MarketSources.read("third",config).keys.getValue("test"))
        assertThrows(IllegalArgumentException::class.java) { MarketSources.requireHttps("http://example.org/catalog.json") }
        assertThrows(IllegalArgumentException::class.java) { MarketSources.requireHttps("https://user:secret@example.org/catalog.json") }
        assertThrows(IllegalArgumentException::class.java) { MarketSources.read("third",JSONObject(config.toString()).put("keys",JSONObject())) }
    }
}
