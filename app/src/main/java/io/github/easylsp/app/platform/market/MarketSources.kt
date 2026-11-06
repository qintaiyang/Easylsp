package io.github.easylsp.app.platform.market

import android.content.Context
import org.json.JSONObject
import java.net.URI
import java.util.Base64

/** Trust is chosen by the user before fetching a catalog; catalogs cannot add keys. */
class MarketSources(private val context: Context) {
    private val preferences = context.getSharedPreferences("market-sources", 0)
    data class Source(val id: String, val name: String, val catalogUrl: String, val keys: Map<String, ByteArray>)
    fun list(): List<Source> = listOf(read("official", JSONObject(context.assets.open("market-source.json").bufferedReader().use { it.readText() }))) +
        preferences.all.filterKeys { it != "selected" }.map { (id, value) -> read(id, JSONObject(value as String)) }.sortedBy { it.name }
    fun get(id: String): Source = requireNotNull(list().find { it.id == id }) { "市场来源不存在：$id" }
    fun selected(): String = preferences.getString("selected", "official")!!
    fun select(id: String) { get(id); check(preferences.edit().putString("selected", id).commit()) }
    fun save(id: String, config: JSONObject) {
        require(id !in setOf("official", "selected") && id.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "来源ID无效或为保留ID" }
        read(id, config)
        check(preferences.edit().putString(id, config.toString()).commit())
    }
    fun remove(id: String) {
        require(id != "official") { "官方来源不能删除" }
        val edit = preferences.edit().remove(id)
        if (selected() == id) edit.putString("selected", "official")
        check(edit.commit())
    }
    companion object {
        fun requireHttps(url: String) {
            val uri = URI(url)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "市场链接必须为无凭据的HTTPS地址" }
        }
        fun read(id: String, config: JSONObject): Source {
            val url = config.getString("catalogUrl"); requireHttps(url)
            val values = config.getJSONObject("keys")
            require(values.length() in 1..16) { "来源须提供1至16个Ed25519公钥" }
            val keys = values.keys().asSequence().associateWith { key ->
                require(key.matches(Regex("[a-zA-Z0-9._:-]{1,128}"))) { "无效公钥ID" }
                Base64.getDecoder().decode(values.getString(key)).also {
                    require(org.bouncycastle.crypto.util.PublicKeyFactory.createKey(it) is org.bouncycastle.crypto.params.Ed25519PublicKeyParameters) { "来源公钥必须为Ed25519 SPKI" }
                }
            }
            return Source(id, config.optString("name", if (id == "official") "官方市场" else id), url, keys)
        }
    }
}
