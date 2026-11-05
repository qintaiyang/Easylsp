package io.github.easylsp.api.internal.ipc;
import android.os.Bundle;
import io.github.easylsp.api.internal.ipc.ITaskCallback;
interface ITaskService {
    oneway void execute(String requestId, in Bundle arguments, ITaskCallback callback);
    oneway void cancel(String requestId);
}
