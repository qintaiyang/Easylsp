package io.github.easylsp.runtime;

import android.app.*;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import io.github.easylsp.api.*;
import org.json.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** All View access and frame callbacks run on the target main thread. */
final class RuntimeInspector implements Application.ActivityLifecycleCallbacks {
    private final Application app;
    private final HookDriver driver;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<WeakReference<Activity>> activities = new ArrayList<>();
    private final WeakHashMap<View, String> stacks = new WeakHashMap<>();
    private final List<HookHandle> stackHooks = new ArrayList<>();
    private long startWall, startCpu, firstFrame, lastFrame, frames;
    private boolean sampling;
    private final List<Long> frameTimes = new ArrayList<>();
    private InspectorOverlay overlay;
    private TextView performancePanel;
    private final Runnable panelRefresh = new Runnable() {
        @Override public void run() {
            if (performancePanel == null || !sampling) return;
            try {
                JSONObject data = performance(new JSONObject());
                performancePanel.setText(String.format(Locale.ROOT, "Easylsp · 拖动面板\nFPS %.1f · 1%% low %.1f\nCPU %.1f%% · PSS %.1f MiB", data.optDouble("fps"), data.optDouble("fpsOnePercentLow"), data.optDouble("cpuPercentOneCore"), data.optDouble("totalPssKb") / 1024));
            } catch (JSONException error) { throw new IllegalStateException(error); }
            main.postDelayed(this, 1000);
        }
    };
    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long time) {
            if (!sampling) return;
            if (firstFrame == 0) firstFrame = time;
            if (lastFrame != 0) { frameTimes.add(time - lastFrame); if (frameTimes.size() > 7200) frameTimes.remove(0); }
            lastFrame = time; frames++;
            Choreographer.getInstance().postFrameCallback(this);
        }
    };
    RuntimeInspector(Application app, HookDriver driver) {
        this.app = app; this.driver = driver; app.registerActivityLifecycleCallbacks(this);
    }
    String execute(String operation, Bundle args) throws Exception {
        FutureTask<String> task = new FutureTask<>(() -> inspect(operation, args).toString());
        main.post(task);
        return task.get(5, TimeUnit.SECONDS);
    }
    private JSONObject inspect(String operation, Bundle args) throws Exception {
        JSONObject out = new JSONObject().put("processName", Application.getProcessName()).put("pid", android.os.Process.myPid()).put("timestamp", System.currentTimeMillis());
        switch (operation) {
            case "viewTree" -> {
                String query = args.getString("query", ""); int offset = Math.max(0, args.getInt("offset", 0));
                int limit = Math.min(100, Math.max(1, args.getInt("limit", 50)));
                JSONArray rows = new JSONArray(); int[] count = {0};
                activities.removeIf(ref -> ref.get() == null);
                int index = 0;
                for (var ref : activities) {
                    Activity activity = ref.get();
                    if (activity != null && !activity.isDestroyed()) walk(activity.getWindow().getDecorView(), "activity" + index++, activity.getClass().getName(), query, offset, limit, count, rows);
                }
                out.put("items", rows).put("offset", offset).put("total", count[0]).put("nextOffset", offset + rows.length() < count[0] ? offset + rows.length() : JSONObject.NULL)
                    .put("scope", "Android View tree; Compose semantics and WebView DOM are not included");
            }
            case "creationStacks" -> {
                boolean enabled = args.getBoolean("enabled");
                if (enabled && stackHooks.isEmpty()) {
                    try {
                        for (var constructor : View.class.getDeclaredConstructors()) stackHooks.add(driver.install(constructor, HookDriver.Mode.AFTER, p -> {
                            synchronized (stacks) { if (!stacks.containsKey((View) p.thisObject)) stacks.put((View) p.thisObject, android.util.Log.getStackTraceString(new Throwable("View created after collection enabled"))); }
                        }));
                    } catch (Throwable error) { for (var handle : stackHooks) handle.unhook(); stackHooks.clear(); throw new IllegalStateException("创建堆栈 Hook 失败", error); }
                } else if (!enabled) { for (var handle : stackHooks) handle.unhook(); stackHooks.clear(); synchronized (stacks) { stacks.clear(); } }
                out.put("enabled", enabled).put("note", "Only Views created after enabling have stacks");
            }
            case "inspectOverlay" -> {
                boolean enabled = args.getBoolean("enabled", true);
                if (!enabled && overlay != null) { ((ViewGroup) overlay.getParent()).removeView(overlay); overlay = null; }
                if (enabled) {
                    Activity activity = currentActivity();
                    ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                    if (overlay != null && overlay.getParent() != decor) { ((ViewGroup) overlay.getParent()).removeView(overlay); overlay = null; }
                    if (overlay == null) { overlay = new InspectorOverlay(activity, decor); decor.addView(overlay, new ViewGroup.LayoutParams(-1, -1)); }
                    if (args.containsKey("x")) overlay.select(args.getInt("x"), args.getInt("y"));
                    out.put("selected", overlay.selection());
                }
                out.put("enabled", enabled).put("note", "Activity 内部覆盖层，不需要悬浮窗权限；点击选择 View，连续选择可测量间距");
            }
            case "performanceOverlay" -> {
                if (performancePanel != null) { ((ViewGroup) performancePanel.getParent()).removeView(performancePanel); performancePanel = null; main.removeCallbacks(panelRefresh); }
                if (args.getBoolean("enabled", true)) {
                    if (!sampling) throw new IllegalStateException("请先开始性能采样");
                    Activity activity = currentActivity();
                    performancePanel = new TextView(activity); performancePanel.setTextColor(0xffffffff); performancePanel.setBackgroundColor(0xdd12312e); performancePanel.setPadding(20, 12, 20, 12); performancePanel.setTextSize(12);
                    performancePanel.setOnTouchListener(new View.OnTouchListener() {
                        float dx, dy;
                        @Override public boolean onTouch(View view, android.view.MotionEvent event) {
                            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) { dx = view.getX() - event.getRawX(); dy = view.getY() - event.getRawY(); }
                            else if (event.getAction() == android.view.MotionEvent.ACTION_MOVE) { view.setX(event.getRawX() + dx); view.setY(event.getRawY() + dy); }
                            return true;
                        }
                    });
                    ((ViewGroup) activity.getWindow().getDecorView()).addView(performancePanel, new ViewGroup.LayoutParams(-2, -2)); main.post(panelRefresh);
                }
                out.put("enabled", performancePanel != null);
            }
            case "performanceRead" -> { if (!sampling) throw new IllegalStateException("请先开始采样"); performance(out); }
            case "performanceStart" -> {
                if (sampling) throw new IllegalStateException("正在采样，请先停止");
                startWall = SystemClock.elapsedRealtime(); startCpu = android.os.Process.getElapsedCpuTime();
                frameTimes.clear(); frames = 0; firstFrame = 0; lastFrame = 0; sampling = true;
                Choreographer.getInstance().postFrameCallback(frame);
                out.put("sampling", true);
            }
            case "performanceStop" -> {
                if (!sampling) throw new IllegalStateException("请先开始采样");
                sampling = false; Choreographer.getInstance().removeFrameCallback(frame);
                performance(out);
                if (performancePanel != null) { ((ViewGroup) performancePanel.getParent()).removeView(performancePanel); performancePanel = null; main.removeCallbacks(panelRefresh); }
            }
            case "memory" -> memory(out);
            default -> throw new IllegalArgumentException("未知检视操作：" + operation);
        }
        return out;
    }
    private Activity currentActivity() {
        for (int i = activities.size() - 1; i >= 0; i--) { Activity a = activities.get(i).get(); if (a != null && !a.isDestroyed()) return a; }
        throw new IllegalStateException("目标暂无 Activity");
    }
    private JSONObject performance(JSONObject out) throws JSONException {
        long elapsed = SystemClock.elapsedRealtime() - startWall;
        long cpu = android.os.Process.getElapsedCpuTime() - startCpu;
        List<Long> slow = new ArrayList<>(frameTimes); slow.sort(Collections.reverseOrder());
        int lowCount = Math.max(1, (int) Math.ceil(slow.size() * .01));
        double slowMean = slow.stream().limit(lowCount).mapToLong(Long::longValue).average().orElse(0);
        out.put("elapsedMs", elapsed).put("cpuTimeMs", cpu).put("cpuPercentOneCore", elapsed == 0 ? 0 : cpu * 100.0 / elapsed)
            .put("frames", frames).put("fps", frames > 1 ? (frames - 1) * 1e9 / (lastFrame - firstFrame) : 0)
            .put("fpsOnePercentLow", slowMean == 0 ? 0 : 1e9 / slowMean).put("frameIntervalCount", slow.size())
            .put("source", "Cumulative Choreographer cadence; 1% low = inverse mean slowest 1% of last 7200 frame intervals; CPU single-core 100%");
        memory(out);
        android.content.Intent battery = app.registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        if (battery != null && battery.hasExtra(android.os.BatteryManager.EXTRA_LEVEL)) out.put("batteryPercent", battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 0) * 100.0 / battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100));
        if (battery != null && battery.hasExtra(android.os.BatteryManager.EXTRA_TEMPERATURE)) out.put("batteryTemperatureC", battery.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
        return out;
    }
    private void memory(JSONObject out) throws JSONException {
        Debug.MemoryInfo info = new Debug.MemoryInfo(); Debug.getMemoryInfo(info);
        out.put("totalPssKb", info.getTotalPss()).put("dalvikPssKb", info.dalvikPss).put("nativePssKb", info.nativePss).put("memorySource", "android.os.Debug.getMemoryInfo, kB");
    }
    private void walk(View view, String path, String activity, String query, int offset, int limit, int[] count, JSONArray rows) throws JSONException {
        if (view == overlay || view == performancePanel) return;
        String text = view instanceof TextView ? ((TextView) view).getText().toString() : "";
        String id = "";
        if (view.getId() != View.NO_ID) { try { id = view.getResources().getResourceName(view.getId()); } catch (android.content.res.Resources.NotFoundException ignored) { id = String.valueOf(view.getId()); } }
        String desc = String.valueOf(view.getContentDescription());
        if (query.isEmpty() || (text + " " + desc + " " + id + " " + view.getClass().getName()).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
            int index = count[0]++;
            if (index >= offset && rows.length() < limit) {
                int[] location = new int[2]; view.getLocationOnScreen(location);
                JSONObject row = new JSONObject().put("path", path).put("activity", activity).put("class", view.getClass().getName()).put("id", id)
                    .put("text", text.substring(0, Math.min(text.length(), 2000))).put("description", desc).put("x", location[0]).put("y", location[1])
                    .put("width", view.getWidth()).put("height", view.getHeight()).put("widthDp", view.getWidth() / view.getResources().getDisplayMetrics().density).put("heightDp", view.getHeight() / view.getResources().getDisplayMetrics().density).put("density", view.getResources().getDisplayMetrics().density).put("visibility", view.getVisibility()).put("enabled", view.isEnabled()).put("clickable", view.isClickable()).put("alpha", view.getAlpha());
                synchronized (stacks) { String stack = stacks.get(view); if (stack != null) row.put("creationStack", stack.substring(0, Math.min(stack.length(), 4000))); }
                rows.put(row);
            }
        }
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), path + "/" + i, activity, query, offset, limit, count, rows);
    }
    @Override public void onActivityCreated(Activity activity, Bundle saved) { activities.add(new WeakReference<>(activity)); }
    @Override public void onActivityDestroyed(Activity activity) { activities.removeIf(ref -> ref.get() == null || ref.get() == activity); }
    @Override public void onActivityStarted(Activity a) { }
    @Override public void onActivityResumed(Activity a) { }
    @Override public void onActivityPaused(Activity a) { }
    @Override public void onActivityStopped(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
}
