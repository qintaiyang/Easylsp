package io.github.easylsp.runtime;
import android.app.Application;
import io.github.easylsp.api.HookDriver;
final class RuntimeBootstrap {
    private static HostConnection connection;
    static synchronized void start(Application app, HookDriver driver) {
        start(app, driver, StartupDiagnostic.NONE);
    }
    static synchronized void start(Application app, HookDriver driver, StartupDiagnostic diagnostic) {
        if (connection != null) return;
        connection = new HostConnection(app, driver, diagnostic);
        connection.start();
    }
}
