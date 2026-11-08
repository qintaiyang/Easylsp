package io.github.easylsp.runtime;
import io.github.easylsp.api.PluginLog;
public final class PluginEnvironment {
    public final String id;
    public final long revision;
    public final String instanceId;
    private final PluginLog output;
    public final PluginResources resources = new PluginResources();
    public final PluginServices.Client services;
    private final PluginProgram program;
    final java.util.Map<String, Object> components = new java.util.LinkedHashMap<>();
    public PluginEnvironment(String id, long revision, String instanceId, PluginLog output) {
        this(id, revision, instanceId, output, null, null);
    }
    PluginEnvironment(String id, long revision, String instanceId, PluginLog output, PluginProgram program, PluginServices.Client services) {
        this.id = id; this.revision = revision; this.instanceId = instanceId; this.output = output;
        this.program = program; this.services = services;
    }
    public void log(Object message) { output.write("INFO", String.valueOf(message), null); }
    public Object component(String id) {
        if (!components.containsKey(id)) throw new IllegalArgumentException("Component not loaded: " + id);
        return components.get(id);
    }
    public String readText(String path) { return program.text(path); }
}
