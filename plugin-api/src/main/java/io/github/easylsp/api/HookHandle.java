package io.github.easylsp.api;
public interface HookHandle extends AutoCloseable {
    void unhook();
    @Override default void close() { unhook(); }
}
