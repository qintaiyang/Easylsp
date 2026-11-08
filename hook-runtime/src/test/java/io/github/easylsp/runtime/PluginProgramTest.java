package io.github.easylsp.runtime;

import io.github.easylsp.api.*;
import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;

public class PluginProgramTest {
    private final List<String> logs = new ArrayList<>();
    private final PluginEngine engine = new PluginEngine((member, mode, callback) -> () -> {}, getClass().getClassLoader(), new Object(), new PluginEngine.Events() {
        public void state(String id, long revision, String instance, String state, String error) {}
        public void log(String id, long revision, String instance, String level, String message, Throwable error) { logs.add(message); }
    });
    @After public void close() { engine.close(); }
    private PluginProgram program(String manifest, String main, String... files) throws Exception {
        Map<String, String> values = new LinkedHashMap<>(); values.put("main.java", main); values.put("plugin.json", manifest);
        for (int i = 0; i < files.length; i += 2) values.put(files[i], files[i + 1]);
        return new PluginProgram(values);
    }
    private void load(String id, PluginProgram program) throws Throwable { engine.load(id, 1, program, getClass().getClassLoader()); }
    private PluginProgram provider() throws Exception {
        return program("{\"schema\":1,\"services\":{\"provides\":{\"example.echo\":1}}}",
            "void onLoad() { PluginService handler = new PluginService() { public String call(String operation, String request) { return request; } }; plugin.services.provide(\"example.echo\", handler); }");
    }
    private PluginProgram consumer() throws Exception {
        return program("{\"schema\":1,\"services\":{\"requires\":{\"example.echo\":1}}}",
            "void onLoad() { service=plugin.services.require(\"example.echo\"); plugin.log(service.call(\"echo\", \"{}\")); }");
    }
    @Test public void componentsHaveLocalStateAndReverseCleanup() throws Throwable {
        PluginProgram p = program("{\"schema\":1,\"components\":[{\"id\":\"ui\",\"path\":\"src/ui.bsh\",\"requires\":[\"config\"]},{\"id\":\"config\",\"path\":\"src/config.bsh\"}]}",
            "int count=900; void onLoad(){ plugin.log(plugin.component(\"ui\").value()); } void onUnload(){plugin.log(\"entry-stop\");}",
            "src/config.bsh", "int count=10; value(){ return count; } void onLoad(){plugin.log(\"config-start\");} void onUnload(){plugin.log(\"config-stop\");} return this;",
            "src/ui.bsh", "int count=100; config=component.require(\"config\"); value(){return config.value()+count;} void onLoad(){component.resources.onClose(new Runnable(){public void run(){plugin.log(\"ui-resource\");}});} void onUnload(){plugin.log(\"ui-stop\");} return this;");
        load("components", p); assertEquals(List.of("config-start", "110"), logs);
        engine.unload("components"); assertEquals(List.of("config-start", "110", "entry-stop", "ui-stop", "ui-resource", "config-stop"), logs);
    }
    @Test public void componentHooksContributeToPluginMetrics() throws Throwable {
        load("metrics", program("{\"schema\": 1, \"components\": [{\"id\": \"sample\", \"path\": \"src/sample.bsh\"}]}", "", "src/sample.bsh", "void onLoad(){ HookCallback cb=new HookCallback(){public void call(HookParam p){}}; hook.before(\"java.lang.String\",\"length\",new String[0],cb); } return this;"));
        org.json.JSONObject metrics = new org.json.JSONObject(engine.metrics("metrics", true));
        assertEquals(1, metrics.getInt("installed")); assertEquals(1, metrics.getInt("active"));
        assertTrue(metrics.getBoolean("enabled")); engine.unload("metrics");
    }
    @Test public void bundleRoundTripRetainsTextAndFailureNames() throws Exception {
        PluginProgram p = program("{\"schema\":1,\"components\":[{\"id\":\"broken\",\"path\":\"src/broken.bsh\"}]}", "", "src/broken.bsh", "void bad( {", "assets/test.js", "hello 世界");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); p.write(bytes);
        PluginProgram copy = PluginProgram.read(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(p.files, copy.files);
        assertTrue(assertThrows(IllegalArgumentException.class, copy::validate).getMessage().contains("src/broken.bsh"));
    }
    @Test public void syntaxFailurePreservesExistingInstance() throws Throwable {
        load("reload", program("{\"schema\":1}", "void onUnload(){plugin.log(\"unloaded\");}"));
        PluginProgram invalid = program("{\"schema\":1,\"components\":[{\"id\":\"bad\",\"path\":\"src/bad.bsh\"}]}", "", "src/bad.bsh", "void bad( {");
        assertThrows(IllegalArgumentException.class, () -> engine.reload("reload", 2, invalid, getClass().getClassLoader()));
        assertTrue(engine.alreadyLoaded("reload", 1)); assertTrue(logs.isEmpty());
    }
    @Test public void failedInitializationCleansRegisteredResources() throws Throwable {
        PluginProgram p = program("{\"schema\":1,\"components\":[{\"id\":\"bad\",\"path\":\"src/bad.bsh\"}]}", "",
            "src/bad.bsh", "void onLoad(){ component.resources.onClose(new Runnable(){public void run(){plugin.log(\"closed\");}}); throw new RuntimeException(); } return this;");
        assertThrows(Throwable.class, () -> load("failure", p)); assertTrue(logs.contains("closed"));
        load("failure", program("{\"schema\":1}", ""));
    }
    @Test public void servicesOrderAndDependentUnload() throws Throwable {
        Map<String, PluginProgram> programs = new LinkedHashMap<>(); programs.put("consumer", consumer()); programs.put("provider", provider());
        assertEquals(List.of("provider", "consumer"), PluginProgram.order(programs));
        assertThrows(IllegalStateException.class, () -> load("consumer", consumer()));
        load("provider", provider()); load("consumer", consumer()); assertTrue(logs.contains("{}"));
        assertThrows(IllegalStateException.class, () -> engine.unload("provider"));
        assertThrows(IllegalStateException.class, () -> engine.reload("provider", 2, provider(), getClass().getClassLoader()));
        engine.unload("consumer"); engine.unload("provider");
    }
    @Test public void serviceHandlesExpireAndVersionsAreExact() throws Throwable {
        PluginServices services = new PluginServices();
        PluginServices.Client p = services.client("p", provider());
        p.provide("example.echo", (method, request) -> request); p.ready();
        PluginServices.Client c = services.client("c", consumer());
        PluginService handle = c.require("example.echo"); assertEquals("{}", handle.call("echo", "{}"));
        assertThrows(IllegalArgumentException.class, () -> handle.call("echo", "plain text"));
        c.close(); assertThrows(IllegalStateException.class, () -> handle.call("echo", "{}"));
        PluginServices.Client c2 = services.client("c2", consumer()); PluginService old = c2.require("example.echo");
        p.close(); assertThrows(IllegalStateException.class, () -> old.call("echo", "{}"));
        PluginProgram wrong = program("{\"schema\":1,\"services\":{\"requires\":{\"example.echo\":2}}}", "");
        assertThrows(IllegalArgumentException.class, () -> PluginProgram.order(Map.of("p", provider(), "c", wrong)));
    }
    @Test public void callsInFlightBlockBothClientsAndReleaseAfterCompletion() throws Throwable {
        PluginServices registry = new PluginServices();
        PluginServices.Client p = registry.client("p", provider());
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        p.provide("example.echo", (method, request) -> { entered.countDown(); release.await(); return request; }); p.ready();
        PluginServices.Client c = registry.client("c", consumer());
        PluginService handle = c.require("example.echo");
        java.util.concurrent.FutureTask<String> call = new java.util.concurrent.FutureTask<>(() -> {
            try { return handle.call("echo", "{}"); } catch (Throwable error) { throw new RuntimeException(error); }
        });
        Thread thread = new Thread(call); thread.start();
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, c::close);
            assertThrows(IllegalStateException.class, p::close);
        } finally { release.countDown(); thread.join(5000); }
        assertEquals("{}", call.get(5, java.util.concurrent.TimeUnit.SECONDS)); c.close(); p.close();
    }
    @Test public void releaseAndServiceDependenciesShareOneGraph() throws Exception {
        Map<String, PluginProgram> programs = Map.of("provider", provider(), "consumer", consumer());
        assertThrows(IllegalArgumentException.class, () -> PluginProgram.order(programs, Map.of("provider", List.of("consumer"))));
    }
    @Test public void dependencyCyclesAndDuplicateProvidersAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> program("{\"schema\":1,\"components\":[{\"id\":\"a\",\"path\":\"src/a.bsh\",\"requires\":[\"a\"]}]}", "", "src/a.bsh", "return this;"));
        assertThrows(IllegalArgumentException.class, () -> PluginProgram.order(Map.of("p1", provider(), "p2", provider())));
        PluginProgram a = program("{\"schema\":1,\"services\":{\"provides\":{\"a\":1},\"requires\":{\"b\":1}}}", "");
        PluginProgram b = program("{\"schema\":1,\"services\":{\"provides\":{\"b\":1},\"requires\":{\"a\":1}}}", "");
        assertThrows(IllegalArgumentException.class, () -> PluginProgram.order(Map.of("a", a, "b", b)));
    }
}
