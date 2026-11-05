package io.github.easylsp.api.internal.ipc;
import android.os.ParcelFileDescriptor;
oneway interface ITaskCallback {
    void onProgress(String requestId, String message);
    void onResult(String requestId, boolean success, String error, in ParcelFileDescriptor result);
}
