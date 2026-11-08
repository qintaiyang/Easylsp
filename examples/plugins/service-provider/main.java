void onLoad() { PluginService handler = new PluginService() { public String call(String operation, String request) { return request; } }; plugin.services.provide("example.echo", handler); }
