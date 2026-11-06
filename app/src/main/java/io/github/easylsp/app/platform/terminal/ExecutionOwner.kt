package io.github.easylsp.app.platform.terminal

/** A tool's execution remains owned by its conversation even in a shared project workspace. */
object ExecutionOwner {
    val conversation = ThreadLocal<String?>()
}
