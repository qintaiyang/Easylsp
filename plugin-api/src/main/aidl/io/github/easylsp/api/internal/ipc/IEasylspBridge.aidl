package io.github.easylsp.api.internal.ipc;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import io.github.easylsp.api.internal.ipc.IHostCallback;
interface IEasylspBridge {
    String registerHost(int protocolVersion, String packageName, String processName, int pid, IHostCallback callback);
    List<Bundle> listPlugins(String sessionId);
    ParcelFileDescriptor openPlugin(String sessionId, String pluginId, long revision);
    List<String> listPluginLibraries(String sessionId, String pluginId, long revision);
    ParcelFileDescriptor openPluginLibrary(String sessionId, String pluginId, long revision, String name);
    oneway void publish(String sessionId, in Bundle event);
}
