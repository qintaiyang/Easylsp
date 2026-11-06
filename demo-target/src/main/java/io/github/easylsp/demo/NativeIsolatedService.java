package io.github.easylsp.demo;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

/** Controlled isolated and app-zygote child fixture. No manager Binder bridge. */
public class NativeIsolatedService extends Service {
    private int result;
    @Override public void onCreate() {
        super.onCreate();
        System.loadLibrary("demo_early_hook");
        result = DemoApplication.earlyConstructorResult();
        android.util.Log.i("EasylspDemoM7", "process=" + android.app.Application.getProcessName() + " uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid() + " result=" + result);
    }
    @Override public IBinder onBind(Intent intent) {
        return new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code != FIRST_CALL_TRANSACTION) return false;
                reply.writeNoException(); reply.writeInt(result); reply.writeInt(android.os.Process.myUid()); reply.writeInt(android.os.Process.myPid()); return true;
            }
        };
    }
}
