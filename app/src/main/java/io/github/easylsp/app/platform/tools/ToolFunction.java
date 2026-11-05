package io.github.easylsp.app.platform.tools;
import org.json.JSONObject;
public interface ToolFunction { Object execute(JSONObject arguments) throws Exception; }
