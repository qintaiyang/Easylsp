package io.github.easylsp.runtime;
import android.app.Application;
import io.github.easylsp.api.HookDriver;
final class RuntimeBootstrap {
    private static HostConnection connection;
    static synchronized void start(Application app, HookDriver driver) {
        start(app, driver, StartupDiagnostic.NONE);
    }
    static synchronized void start(Application app, HookDriver driver, StartupDiagnostic diagnostic) {
        start(app, driver, diagnostic, null);
    }
    static synchronized void start(Application app, HookDriver driver, StartupDiagnostic diagnostic, JavaStartupSnapshot snapshot) {
        if (connection != null) return;
        connection = new HostConnection(app, driver, diagnostic);
        if (snapshot != null && !diagnostic.suppressAutoLoad()) connection.loadPublished(snapshot);
        connection.start(snapshot == null || !snapshot.published() || snapshot.debugRequested);
    }
}
