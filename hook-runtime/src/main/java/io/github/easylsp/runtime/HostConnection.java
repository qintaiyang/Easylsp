package io.github.easylsp.runtime;
import android.app.Application;
import android.content.*;
import android.os.*;
import android.os.Process;
import android.util.Log;
import io.github.easylsp.api.HookDriver;
import io.github.easylsp.api.internal.Protocol;
import io.github.easylsp.api.internal.ipc.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class HostConnection implements ServiceConnection, PluginEngine.Events {
    private final Application app;
    private final Handler worker;
    private final PluginEngine engine;
    private final RuntimeInspector inspector;
    private final DebugExecution debug;
    private volatile IEasylspBridge bridge;
    private volatile String session;
    private boolean bound;
    private int managerUid = -1;
    private IBinder managerBinder;
    private IBinder.DeathRecipient managerDeath;
    private final StartupDiagnostic diagnostic;
    public HostConnection(Application app, HookDriver driver) {
        this(app, driver, StartupDiagnostic.NONE);
    }
    HostConnection(Application app, HookDriver driver, StartupDiagnostic diagnostic) {
        this.diagnostic = diagnostic;
        this.app = app;
        inspector = new RuntimeInspector(app, driver);
        debug = new DebugExecution(app, driver);
        HandlerThread thread = new HandlerThread("Easylsp-runtime"); thread.start();
        worker = new Handler(thread.getLooper());
        engine = new PluginEngine(driver, app.getClassLoader(), app, this);
    }
    void loadPublished(JavaStartupSnapshot snapshot) {
        try { snapshot.load(app, engine); }
        catch (Exception error) { Log.e("Easylsp", "Java snapshot validation failed", error); }
    }
    public void start() { start(true); }
    void start(boolean connect) {
        BroadcastReceiver reconnect = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                Bundle extras = intent.getExtras();
                IBinder endpoint = extras == null ? null : extras.getBinder(Protocol.BRIDGE_BINDER);
                disconnect();
                if (endpoint == null) bind();
                else {
                    // This receiver requires the manager's signature permission.
                    managerUid = extras.getInt(Protocol.MANAGER_UID);
                    connect(endpoint);
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(reconnect, new IntentFilter(Protocol.RECONNECT_ACTION), Protocol.RECONNECT_PERMISSION, worker, Context.RECEIVER_EXPORTED);
        } else {
            app.registerReceiver(reconnect, new IntentFilter(Protocol.RECONNECT_ACTION), Protocol.RECONNECT_PERMISSION, worker);
        }
        if (connect || !diagnostic.runId.isEmpty()) worker.post(this::bind);
    }
    private void bind() {
        Intent intent = new Intent().setComponent(new ComponentName(Protocol.MANAGER_PACKAGE, Protocol.BRIDGE_SERVICE));
        try { bound = app.bindService(intent, this, Context.BIND_AUTO_CREATE); }
        catch (RuntimeException error) { diagnostic.event("bridge_bind_exception", managerState() + "; " + error); Log.e("Easylsp", "桥接绑定失败；" + managerState() + "；已加载业务插件保留", error); return; }
        if (bound) diagnostic.event("bridge_bind_accepted");
        else {
            String state = managerState();
            diagnostic.event("bridge_bind_rejected", state);
            Log.w("Easylsp", "bindService=false: " + intent.getComponent() + "; " + state + "; 已加载插件继续运行");
        }
    }
    private String managerState() {
        try {
            android.content.pm.ApplicationInfo info = app.getPackageManager().getApplicationInfo(Protocol.MANAGER_PACKAGE, 0);
            return "managerInstalled=true, enabled=" + info.enabled + ", stopped=" + ((info.flags & android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            return "managerPackageNotFoundOrNotVisible";
        }
    }
    private void disconnect() {
        inspector.disconnect(); debug.close();
        if (managerBinder != null && managerDeath != null) managerBinder.unlinkToDeath(managerDeath, 0);
        managerBinder = null; managerDeath = null; managerUid = -1;
        bridge = null; session = null;
        if (bound) { app.unbindService(this); bound = false; }
    }
    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        worker.post(() -> {
            try { managerUid = app.getPackageManager().getApplicationInfo(Protocol.MANAGER_PACKAGE, 0).uid; }
            catch (android.content.pm.PackageManager.NameNotFoundException error) { Log.e("Easylsp", "无法确认管理端 UID", error); disconnect(); return; }
            connect(binder);
        });
    }
    private void connect(IBinder binder) {
            bridge = IEasylspBridge.Stub.asInterface(binder);
            try {
                managerBinder = binder;
                managerDeath = () -> worker.post(() -> { if (managerBinder == binder) disconnect(); });
                binder.linkToDeath(managerDeath, 0);
                session = bridge.registerHost(Protocol.VERSION, app.getPackageName(), Application.getProcessName(), Process.myPid(), callback);
                if (!diagnostic.runId.isEmpty()) {
                    Bundle event = new Bundle(); event.putString("type", "diagnostic"); event.putString("diagnosticRunId", diagnostic.runId);
                    event.putString("phase", "session_connected"); event.putString("mode", diagnostic.mode); publish(event);
                }
                Bundle identity = new Bundle(); identity.putString("type", "identity");
                String stat = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/self/stat")), StandardCharsets.UTF_8);
                identity.putLong("startTicks", Long.parseLong(stat.substring(stat.lastIndexOf(") ") + 2).split(" ")[19])); publish(identity);
                publishNativeEvents();
                if (diagnostic.suppressAutoLoad()) diagnostic.event("plugin_auto_load_suppressed");
                else sync();
            } catch (Exception error) { diagnostic.event("bridge_registration_rejected: " + error); Log.e("Easylsp", "连接被拒绝", error); disconnect(); }
    }
    @Override public void onServiceDisconnected(ComponentName name) { worker.post(() -> { Log.w("Easylsp", "bridge_service_disconnected; published business hooks retained"); diagnostic.event("bridge_service_disconnected"); disconnect(); }); }
    @Override public void onBindingDied(ComponentName name) { worker.post(() -> { Log.w("Easylsp", "bridge_binding_died"); diagnostic.event("bridge_binding_died"); disconnect(); }); }
    @Override public void onNullBinding(ComponentName name) { worker.post(() -> { Log.w("Easylsp", "bridge_null_binding"); diagnostic.event("bridge_null_binding"); disconnect(); }); }
    private final IHostCallback callback = new IHostCallback.Stub() {
        @Override public void onRequest(String requestId, String sessionId, String operation, Bundle args, ParcelFileDescriptor script) {
            final int callerUid = Binder.getCallingUid();
            worker.post(() -> {
                try (ParcelFileDescriptor descriptor = script) {
                    if (callerUid != managerUid) throw new SecurityException("命令来源不是管理端");
                    if (!sessionId.equals(session)) return;
                    String pluginId = args.getString("pluginId", "");
                    switch (operation) {
                        case "memorySnapshot", "viewNode", "viewClick", "viewScroll", "viewTree", "creationStacks", "performanceStart", "performanceRead", "performanceStop", "performanceOverlay", "inspectOverlay", "memory" -> { complete(requestId, true, inspector.execute(operation, args)); return; }
                        case "debugOnce" -> {
                            complete(requestId, true, debug.execute(args.getString("source", ""), args.getInt("timeoutMs", 3000))); return;
                        }
                        case "ping" -> { }
                        case "disconnectDebug" -> {
                            complete(requestId, true, "调试已结束；业务插件保留");
                            Bundle ended = new Bundle(); ended.putString("type", "debug_disconnected"); publish(ended);
                            disconnect(); return;
                        }
                        case "readNativeEvents" -> publishNativeEvents();
                        case "syncPlugins" -> sync();
                        case "hookMetrics" -> { complete(requestId, true, engine.metrics(pluginId, args.containsKey("enabled") ? args.getBoolean("enabled") : null)); return; }
                        case "getPluginState" -> engine.report(pluginId);
                        case "unloadPlugin" -> engine.unload(pluginId);
                        case "loadPlugin", "reloadPlugin" -> {
                            long revision = args.getLong("revision");
                            if (operation.equals("loadPlugin") && engine.alreadyLoaded(pluginId, revision)) break;
                            File cached = cache(pluginId, revision, descriptor);
                            try (Reader reader = new InputStreamReader(new FileInputStream(cached), StandardCharsets.UTF_8)) {
                                if (operation.equals("reloadPlugin")) engine.reload(pluginId, revision, reader, dependencyLoader(pluginId, revision)); else engine.load(pluginId, revision, reader, dependencyLoader(pluginId, revision));
                            }
                        }
                        default -> throw new IllegalArgumentException("未知操作: " + operation);
                    }
                    complete(requestId, true, operation.equals("ping") ? app.getPackageName() + " / " + Application.getProcessName() + " / pid=" + Process.myPid() : "已完成");
                } catch (Throwable error) {
                    while ((error instanceof java.util.concurrent.ExecutionException || error instanceof java.lang.reflect.InvocationTargetException) && error.getCause() != null) error = error.getCause();
                    Bundle event = new Bundle(); event.putString("type", "result"); event.putString("requestId", requestId); event.putBoolean("success", false);
                    event.putString("message", error.getMessage() == null ? error.toString() : error.getMessage());
                    event.putString("errorCode", error instanceof io.github.easylsp.api.internal.OperationException operationError ? operationError.code : "RUNTIME_ERROR");
                    publish(event);
                }
            });
        }
    };
    private void publishNativeEvents() {
        String[] lines = io.github.easylsp.nativeengine.NativeRuntime.bufferedEvents();
        for (int start = 0; start < lines.length; start += 32) {
            Bundle event = new Bundle(); event.putString("type", "native");
            event.putStringArray("nativeEvents", java.util.Arrays.copyOfRange(lines, start, Math.min(lines.length, start + 32))); publish(event);
        }
    }
    private void sync() throws Exception {
        List<Bundle> plugins = bridge.listPlugins(session);
        java.util.Set<String> enabled = new java.util.HashSet<>();
        for (Bundle item : plugins) if ("BEANSHELL".equals(item.getString("runtime", "BEANSHELL")) && item.getBoolean("desiredEnabled")) enabled.add(item.getString("pluginId"));
        engine.synchronizeEnabled(enabled);
        for (Bundle item : plugins) {
            if (!"BEANSHELL".equals(item.getString("runtime", "BEANSHELL"))) continue;
            String id = item.getString("pluginId");
            try {
                if (item.getBoolean("desiredEnabled")) {
                    long revision = item.getLong("revision");
                    if (engine.alreadyLoaded(id, revision)) continue;
                    try (ParcelFileDescriptor fd = bridge.openPlugin(session, id, revision)) {
                        File file = cache(id, revision, fd);
                        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) { engine.load(id, revision, reader, dependencyLoader(id, revision)); }
                    }
                } else engine.unload(id);
            } catch (Throwable error) { Log.e("Easylsp", "同步插件失败: " + id, error); }
        }
    }
    private ClassLoader dependencyLoader(String id, long revision) throws Exception {
        java.util.ArrayList<java.nio.ByteBuffer> buffers = new java.util.ArrayList<>();
        for (String name : bridge.listPluginLibraries(session, id, revision)) {
            try (ParcelFileDescriptor fd = bridge.openPluginLibrary(session, id, revision, name); InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                if (name.endsWith(".dex")) buffers.add(java.nio.ByteBuffer.wrap(io.github.easylsp.runtime.StreamIO.readAll(input)));
                else try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(input)) {
                    java.util.zip.ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) if (entry.getName().matches("classes(?:[0-9]+)?\\.dex")) buffers.add(java.nio.ByteBuffer.wrap(io.github.easylsp.runtime.StreamIO.readAll(zip)));
                }
            }
        }
        return buffers.isEmpty() ? app.getClassLoader() : new dalvik.system.InMemoryDexClassLoader(buffers.toArray(new java.nio.ByteBuffer[0]), app.getClassLoader());
    }
    private File cache(String id, long revision, ParcelFileDescriptor fd) throws IOException {
        // IDs originate from the manager, but cross the Binder boundary here.
        java.util.UUID.fromString(id);
        File dir = new File(app.getCacheDir(), "easylsp"); dir.mkdirs();
        File file = new File(dir, id + "-" + revision + ".java");
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(fd); OutputStream output = new FileOutputStream(file)) { StreamIO.copy(input, output); }
        return file;
    }
    private void complete(String id, boolean success, String message) {
        Bundle event = new Bundle(); event.putString("type", "result"); event.putString("requestId", id); event.putBoolean("success", success); event.putString("message", message); publish(event);
    }
    @Override public void state(String id, long revision, String instance, String state, String error) {
        Bundle event = base(id, revision, instance); event.putString("type", "state"); event.putString("state", state); event.putString("error", error); publish(event);
    }
    @Override public void log(String id, long revision, String instance, String level, String message, Throwable error) {
        Bundle event = base(id, revision, instance); event.putString("type", "log"); event.putString("level", level); event.putString("message", message + (error == null ? "" : "\n" + Log.getStackTraceString(error))); publish(event);
    }
    private Bundle base(String id, long revision, String instance) {
        Bundle event = new Bundle(); event.putString("pluginId", id); event.putLong("revision", revision); event.putString("instanceId", instance); event.putLong("timestamp", System.currentTimeMillis()); return event;
    }
    private void publish(Bundle event) {
        IEasylspBridge current = bridge; String currentSession = session;
        if (current == null || currentSession == null) return;
        String message = event.getString("message");
        if ("log".equals(event.getString("type")) && message != null && message.length() > 16000) event.putString("message", message.substring(0, 16000) + "\n[单条日志已截断]");
        try { current.publish(currentSession, event); }
        catch (RemoteException | SecurityException error) { Log.e("Easylsp", "事件上报失败", error); }
    }
}
