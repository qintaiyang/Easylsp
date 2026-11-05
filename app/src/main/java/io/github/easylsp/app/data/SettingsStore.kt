package io.github.easylsp.app.data
import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
private val Context.settings by preferencesDataStore("settings")
class SettingsStore(private val context: Context) {
    private val appearanceKey = stringPreferencesKey("appearance")
    val appearance = context.settings.data.map { it[appearanceKey] ?: "system" }
    suspend fun appearance(value: String) { context.settings.edit { it[appearanceKey] = value } }
    private val sortKey = stringPreferencesKey("project_sort")
    val projectSort = context.settings.data.map { it[sortKey] ?: "recent" }
    suspend fun projectSort(value: String) { context.settings.edit { it[sortKey] = value } }
    private val filterKey = stringPreferencesKey("project_filter")
    val projectFilter = context.settings.data.map { it[filterKey] ?: "all" }
    suspend fun projectFilter(value: String) { context.settings.edit { it[filterKey] = value } }
    val monet = context.settings.data.map { it[booleanPreferencesKey("monet")] ?: false }
    suspend fun monet(value: Boolean) { context.settings.edit { it[booleanPreferencesKey("monet")] = value } }
    val pageScale = context.settings.data.map { it[floatPreferencesKey("page_scale")] ?: 1f }
    suspend fun pageScale(value: Float) { context.settings.edit { it[floatPreferencesKey("page_scale")] = value } }
    val compactNavigation = context.settings.data.map { it[booleanPreferencesKey("compact_navigation")] ?: false }
    suspend fun compactNavigation(value: Boolean) { context.settings.edit { it[booleanPreferencesKey("compact_navigation")] = value } }
    private val showSystemKey = booleanPreferencesKey("show_system_apps")
    val showSystem = context.settings.data.map { it[showSystemKey] ?: false }
    suspend fun showSystem(value: Boolean) { context.settings.edit { it[showSystemKey] = value } }
}
