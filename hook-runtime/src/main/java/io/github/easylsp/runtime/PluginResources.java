package io.github.easylsp.runtime;

import java.util.*;
import java.util.concurrent.*;
import android.os.Handler;
import android.os.Looper;

/** Owned cleanup callbacks. Successfully closed resources are not closed twice. */
public final class PluginResources implements AutoCloseable {
    private final List<AutoCloseable> resources = new ArrayList<>();
    private boolean closed;
    public synchronized void add(AutoCloseable resource) {
        if (closed) throw new IllegalStateException("Resource scope is closed");
        resources.add(Objects.requireNonNull(resource));
    }
    public void onClose(Runnable action) { add(action::run); }
    public void onUiClose(Runnable action) {
        add(new AutoCloseable() {
            private FutureTask<Void> task;
            public void close() throws Exception {
                if (task == null) {
                    task = new FutureTask<>(action, null);
                    if (Looper.myLooper() == Looper.getMainLooper()) task.run();
                    else new Handler(Looper.getMainLooper()).post(task);
                }
                try { task.get(5, TimeUnit.SECONDS); }
                catch (ExecutionException error) { task = null; throw error; }
            }
        });
    }
    @Override public void close() throws Exception {
        List<AutoCloseable> copy;
        synchronized (this) { closed = true; copy = new ArrayList<>(resources); }
        Exception failure = null;
        Collections.reverse(copy);
        for (AutoCloseable resource : copy) try {
            resource.close(); synchronized (this) { resources.remove(resource); }
        } catch (Exception e) { if (failure == null) failure = new Exception("Resource cleanup failed"); failure.addSuppressed(e); }
        if (failure != null) throw failure;
    }
}
