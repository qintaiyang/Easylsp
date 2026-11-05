package io.github.easylsp.standalone;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
public final class ModuleActivity extends Activity {
    public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this); text.setPadding(32, 32, 32, 32); text.setTextIsSelectable(true);
        try { var config = StandaloneRuntime.config(getApplicationInfo().sourceDir); text.setText(config.getString("name") + "\n\n目标：" + config.getString("packageName") + "\n进程：" + config.getString("processName") + "\n源码版本：r" + config.getLong("revision") + "\n\n在 Xposed/LSPosed 中启用本模块并选择目标应用，然后重启目标进程。无需运行 Easylsp 管理端。日志标签：EasylspStandalone。"); }
        catch (Exception error) { text.setText(error.toString()); }
        setContentView(text);
    }
}
