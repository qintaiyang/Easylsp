package io.github.easylsp.runtime;

import io.github.easylsp.api.PluginService;
import java.util.*;
import org.json.*;

/** One target process; handles expire with either instance. */
public final class PluginServices {
    private static final class Entry {
        final String owner; final int version; final PluginService service;
        volatile boolean active;
        int calls;
        Entry(String owner, int version, PluginService service) { this.owner = owner; this.version = version; this.service = service; }
    }
    private final Map<String, Entry> entries = new HashMap<>();
    public final class Client {
        private final String owner;
        private final PluginProgram program;
        private volatile boolean active = true;
        private boolean ready;
        private int calls;
        private Client(String owner, PluginProgram program) { this.owner = owner; this.program = program; }
        public void provide(String name, PluginService service) {
            synchronized (PluginServices.this) {
                if (!active || ready || !program.provides.containsKey(name)) throw new IllegalStateException("Service must be declared and registered during load: " + name);
                if (entries.containsKey(name)) throw new IllegalStateException("Service already provided: " + name);
                entries.put(name, new Entry(owner, program.provides.get(name), Objects.requireNonNull(service)));
            }
        }
        public PluginService require(String name) {
            final Entry entry;
            synchronized (PluginServices.this) {
                if (!active || !program.requires.containsKey(name)) throw new IllegalStateException("Undeclared or inactive dependency: " + name);
                entry = entries.get(name);
                if (entry == null || !entry.active || entry.version != program.requires.get(name)) throw new IllegalStateException("Service unavailable: " + name);
            }
            return (method, request) -> {
                json(request);
                synchronized (PluginServices.this) {
                    if (!active || !entry.active) throw new IllegalStateException("Service handle expired: " + name);
                    calls++; entry.calls++;
                }
                try {
                    String result = entry.service.call(method, request);
                    json(result); return result;
                } finally { synchronized (PluginServices.this) { calls--; entry.calls--; } }
            };
        }
        void ready() {
            synchronized (PluginServices.this) {
                for (String name : program.provides.keySet()) {
                    Entry entry = entries.get(name);
                    if (entry == null || !entry.owner.equals(owner)) throw new IllegalStateException("Declared service was not registered: " + name);
                }
                for (Entry entry : entries.values()) if (entry.owner.equals(owner)) entry.active = true;
                ready = true;
            }
        }
        void close() {
            synchronized (PluginServices.this) {
                if (calls != 0 || entries.values().stream().anyMatch(entry -> entry.owner.equals(owner) && entry.calls != 0))
                    throw new IllegalStateException("Service calls are still running; unload after completion: " + owner);
                active = false;
                entries.values().removeIf(entry -> { if (!entry.owner.equals(owner)) return false; entry.active = false; return true; });
            }
        }
    }
    Client client(String owner, PluginProgram program) {
        synchronized (this) {
            for (Map.Entry<String, Integer> need : program.requires.entrySet()) {
                Entry entry = entries.get(need.getKey());
                if (entry == null || !entry.active || entry.version != need.getValue()) throw new IllegalStateException("Service unavailable: " + need);
            }
            for (String name : program.provides.keySet()) if (entries.containsKey(name)) throw new IllegalStateException("Duplicate service provider: " + name);
        }
        return new Client(owner, program);
    }
    private static void json(String value) throws JSONException {
        if (value == null) throw new IllegalArgumentException("Service payload must be JSON text");
        String text = value.trim();
        if (text.startsWith("{")) new JSONObject(text);
        else if (text.startsWith("[")) new JSONArray(text);
        else throw new IllegalArgumentException("Service payload must be a JSON object or array");
    }
}
