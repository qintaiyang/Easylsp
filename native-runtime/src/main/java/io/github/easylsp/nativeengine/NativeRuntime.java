package io.github.easylsp.nativeengine;

public final class NativeRuntime {
    private NativeRuntime() {}
    private static boolean librariesLoaded;
    private static synchronized void loadLibraries() {
        if (librariesLoaded) return;
        System.loadLibrary("shadowhook");
        System.loadLibrary("easylsp_native");
        librariesLoaded = true;
    }
    public static synchronized int initialize() { loadLibraries(); return initializeEngine(); }
    /** Exit probes are opt-in and independent of normal plugin engine initialization. */
    public static synchronized int start(String socketName) { loadLibraries(); return startObservation(socketName); }
    /** The caller owns the FD and closes it after this synchronous call returns. No dlclose at runtime. */
    public static synchronized int loadPlugin(int fd, String pluginId, String buildId, String instanceId,
            String packageName, String processName, String generation, long sourceRevision, String config) {
        loadLibraries();
        return loadPluginFd(fd, pluginId, buildId, instanceId, packageName, processName, generation, sourceRevision, config);
    }
    public static synchronized String[] bufferedEvents() { return librariesLoaded ? readEvents() : new String[0]; }
    public static native String[] readEvents();
    private static native int initializeEngine();
    private static native int startObservation(String socketName);
    private static native int loadPluginFd(int fd, String pluginId, String buildId, String instanceId,
            String packageName, String processName, String generation, long sourceRevision, String config);
}
