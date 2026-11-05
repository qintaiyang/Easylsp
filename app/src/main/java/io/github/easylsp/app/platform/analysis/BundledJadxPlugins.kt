package io.github.easylsp.app.platform.analysis
import jadx.api.plugins.JadxPlugin
import jadx.api.plugins.loader.JadxPluginLoader
import jadx.plugins.input.dex.DexInputPlugin
/** Android service workers do not inherit the APK ClassLoader used by Java ServiceLoader. */
class BundledJadxPlugins : JadxPluginLoader {
    override fun load(): List<JadxPlugin> = listOf(DexInputPlugin())
    override fun close() {}
}
