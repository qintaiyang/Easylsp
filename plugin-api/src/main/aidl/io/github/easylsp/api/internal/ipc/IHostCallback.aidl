package io.github.easylsp.api.internal.ipc;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
oneway interface IHostCallback {
    void onRequest(String requestId, String sessionId, String operation, in Bundle arguments, in ParcelFileDescriptor script);
}
