package io.github.easylsp.runtime;

/** Explicit internal dependencies and an owned resource scope. */
public final class PluginComponent {
    public final String id;
    public final PluginResources resources = new PluginResources();
    private final PluginProgram.Component definition;
    private final PluginEnvironment environment;
    PluginComponent(PluginProgram.Component definition, PluginEnvironment environment) {
        this.id = definition.id; this.definition = definition; this.environment = environment;
    }
    public Object require(String id) {
        if (!definition.requires.contains(id)) throw new IllegalArgumentException("Undeclared component dependency: " + id);
        return environment.component(id);
    }
}
