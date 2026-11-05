package io.github.easylsp.runtime;

import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** A run-scoped snapshot; saved plugin enablement is never changed. */
final class StartupDiagnostic {
    static final String PREFERENCES = "startup-diagnostics";
    static final StartupDiagnostic NONE = new StartupDiagnostic("", "EXTERNAL_ONLY");
    final String runId;
    final String mode;
    String nativeSocket;
    StartupDiagnostic(String runId, String mode) { this.runId = runId; this.mode = mode; }

    static StartupDiagnostic read(SharedPreferences preferences, String processName) throws Exception {
        String packageName = processName.split(":", 2)[0];
        String value = preferences.getString(Process.myUid() + ":" + packageName, null);
        if (value == null) return NONE;
        JSONObject config = new JSONObject(value);
        long elapsed = SystemClock.elapsedRealtime();
        if (elapsed < config.getLong("startedElapsed") || elapsed > config.getLong("expiresElapsed") || System.currentTimeMillis() > config.getLong("expiresAt")) return NONE;
        String process = config.getString("processName");
        if (!process.equals("*") && !process.equals(processName)) return NONE;
        String mode = config.getString("mode");
        if (!mode.equals("ENTRY_ONLY") && !mode.equals("JAVA_NO_PLUGINS") && !mode.equals("NORMAL") && !mode.equals("NATIVE_OBSERVE")) throw new IllegalArgumentException("Unknown diagnostic mode " + mode);
        StartupDiagnostic result = new StartupDiagnostic(config.getString("diagnosticRunId"), mode);
        if (mode.equals("NATIVE_OBSERVE")) result.nativeSocket = config.getString("nativeSocket");
        return result;
    }
    boolean entryOnly() { return mode.equals("ENTRY_ONLY") || mode.equals("NATIVE_OBSERVE"); }
    boolean suppressAutoLoad() { return mode.equals("JAVA_NO_PLUGINS"); }
    void event(String phase) { event(phase, ""); }
    void event(String phase, String detail) {
        if (runId.isEmpty()) return;
        try {
            Log.i("EasylspDiag", new JSONObject().put("diagnosticRunId", runId).put("phase", phase).put("mode", mode)
                .put("detail", detail).put("pid", Process.myPid()).put("uid", Process.myUid()).put("wallTime", System.currentTimeMillis())
                .put("elapsedRealtime", SystemClock.elapsedRealtime()).toString());
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }
}
