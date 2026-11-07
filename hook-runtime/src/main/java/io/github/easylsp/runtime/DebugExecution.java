package io.github.easylsp.runtime;

import bsh.Interpreter;
import io.github.easylsp.api.HookDriver;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** One-shot scripts own managed handles; timing out closes registration even if user code ignores interruption. */
final class DebugExecution {
    private final android.app.Application app;
    private final HookDriver driver;
    private final List<PluginHooks> retained = new ArrayList<>();
    DebugExecution(android.app.Application app, HookDriver driver) { this.app = app; this.driver = driver; }
    String execute(String source, int timeoutMs) throws Exception {
        if (timeoutMs < 1 || timeoutMs > 5000) throw new IllegalArgumentException("timeoutMs: 1..5000");
        String parsed = PluginEngine.readAndParse(new StringReader(source));
        PluginHooks hooks = new PluginHooks(driver, app.getClassLoader(), (level, message, error) -> android.util.Log.e("EasylspDebug", message, error));
        synchronized (retained) { retained.add(hooks); }
        FutureTask<String> task = new FutureTask<>(() -> {
            Interpreter interpreter = new Interpreter();
            interpreter.setClassLoader(app.getClassLoader()); interpreter.set("context", app);
            interpreter.set("classLoader", app.getClassLoader()); interpreter.set("hook", hooks);
            interpreter.set("reflect", new PluginReflection(app.getClassLoader()));
            Object value = interpreter.eval(parsed);
            return new org.json.JSONObject().put("result", DebugValue.encode(value)).put("thread", Thread.currentThread().getName())
                .put("timeoutMs", timeoutMs).put("resources", "managed Hook handles closed after execution").toString();
        });
        Thread thread = new Thread(task, "Easylsp-debug-once"); thread.setDaemon(true); thread.start();
        try { return task.get(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (TimeoutException error) { task.cancel(true); throw new io.github.easylsp.api.internal.OperationException("SCRIPT_TIMEOUT", "一次性脚本超时，已停止接收新 Hook；忽略中断的用户代码不能被安全强杀"); }
        finally { hooks.close(); synchronized (retained) { retained.remove(hooks); } }
    }
    void close() {
        synchronized (retained) {
            for (var iterator = retained.iterator(); iterator.hasNext();) {
                try { iterator.next().close(); iterator.remove(); }
                catch (RuntimeException error) { android.util.Log.e("EasylspDebug", "残留 Hook 清理失败", error); }
            }
        }
    }
}
