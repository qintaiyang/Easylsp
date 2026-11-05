package io.github.easylsp.app.platform.terminal

/** GLOBAL uses the conversation UUID; PROJECT retains the existing project workspace. */
data class Workspace(val id: String, val scope: String) {
    init { require(scope in setOf("GLOBAL", "PROJECT")); java.util.UUID.fromString(id) }
    val projectId: String? get() = id.takeIf { scope == "PROJECT" }
}
