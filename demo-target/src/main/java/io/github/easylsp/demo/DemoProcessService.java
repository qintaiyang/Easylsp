package io.github.easylsp.demo;
import android.app.Service;
import android.content.Intent;
import android.os.*;
public final class DemoProcessService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        int result = new Calculator().add(2, 3);
        android.util.Log.i("EasylspDemo", ApplicationName() + " add(2,3)=" + result);
        sendBroadcast(new Intent("io.github.easylsp.demo.WORKER_RESULT").setPackage(getPackageName()).putExtra("result", result).putExtra("pid", android.os.Process.myPid()));
        return START_NOT_STICKY;
    }
    private String ApplicationName() { return android.app.Application.getProcessName(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
