package io.github.easylsp.app.platform.market

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64

/** Network trust boundary. Keys are supplied by the configured official source, never by a ZIP. */
object ReleaseSignature {
    fun input(metadata: JSONObject, payload: ByteArray): ByteArray {
        require(metadata.getInt("schema") == 1)
        val names = when (metadata.getString("kind")) {
            "release" -> listOf("kind", "keyId", "catalogPluginId", "publisherId", "releaseId", "versionCode")
            "catalog" -> listOf("kind", "keyId", "catalogRevision")
            else -> error("未知签名领域")
        }
        return ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { output ->
            output.write("Easylsp-Signature-v1\u0000".toByteArray(Charsets.US_ASCII))
            for (name in names) {
                val value = if (name == "versionCode") metadata.getLong(name).toString() else metadata.getString(name)
                val encoded = value.toByteArray(Charsets.UTF_8)
                output.writeInt(encoded.size); output.write(encoded)
            }
            output.writeLong(payload.size.toLong()); output.write(payload)
        } }.toByteArray()
    }
    fun verify(metadata: JSONObject, payload: ByteArray, keys: Map<String, ByteArray>) {
        val key = requireNotNull(keys[metadata.getString("keyId")]) { "未知市场发行公钥" }
        val publicKey = org.bouncycastle.crypto.util.PublicKeyFactory.createKey(key)
        require(publicKey is org.bouncycastle.crypto.params.Ed25519PublicKeyParameters) { "市场公钥必须为 Ed25519" }
        val signature = org.bouncycastle.crypto.signers.Ed25519Signer()
        signature.init(false, publicKey)
        val data = input(metadata, payload)
        signature.update(data, 0, data.size)
        require(signature.verifySignature(Base64.getDecoder().decode(metadata.getString("signature")))) { "市场签名不匹配" }
    }
    fun bindRelease(metadata: JSONObject, manifest: JSONObject) {
        require(metadata.getString("kind") == "release")
        for (key in listOf("catalogPluginId", "publisherId", "releaseId")) require(metadata.getString(key) == manifest.getString(key)) { "签名发行身份不一致：$key" }
        require(metadata.getLong("versionCode") == manifest.getLong("versionCode")) { "签名发行版本不一致" }
    }
}
