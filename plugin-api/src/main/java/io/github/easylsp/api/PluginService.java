package io.github.easylsp.api;

/** Same-process service contract; payloads are JSON text, never plugin-loader objects. */
public interface PluginService {
    String call(String method, String request) throws Throwable;
}
