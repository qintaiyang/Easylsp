import io.github.easylsp.app.platform.tools.ToolFunction;
import org.json.JSONObject;
toolPlugin.addTool("echo", "Return the supplied text", "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}", new ToolFunction() {
    execute(input) { return input.getString("text"); }
});
