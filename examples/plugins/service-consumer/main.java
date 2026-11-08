void onLoad() { PluginService echo = plugin.services.require("example.echo"); plugin.log(echo.call("echo", "{}")); }
