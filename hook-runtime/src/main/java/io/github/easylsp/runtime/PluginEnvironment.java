package io.github.easylsp.runtime;
import io.github.easylsp.api.PluginLog;
public final class PluginEnvironment {
    public final String id;
    public final long revision;
    public final String instanceId;
    private final PluginLog output;
    public PluginEnvironment(String id, long revision, String instanceId, PluginLog output) {
        this.id = id; this.revision = revision; this.instanceId = instanceId; this.output = output;
    }
    public void log(Object message) { output.write("INFO", String.valueOf(message), null); }
}
