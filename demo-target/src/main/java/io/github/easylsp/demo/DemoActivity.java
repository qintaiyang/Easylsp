package io.github.easylsp.demo;
import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.widget.*;

public final class DemoActivity extends Activity {
    private BroadcastReceiver workerReceiver;
    @Override protected void onDestroy() { unregisterReceiver(workerReceiver); super.onDestroy(); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(32, 40, 32, 24);
        TextView title = new TextView(this); title.setText("Easylsp Demo\nEASYLSP_DEMO_ADDITION"); title.setTextSize(22); layout.addView(title);
        TextView result = new TextView(this); result.setId(1001); result.setText("等待调用 add(2,3)"); result.setTextSize(24); layout.addView(result);
        Button add = new Button(this); add.setId(1002); add.setText("调用 add(2,3)"); add.setOnClickListener(v -> result.setText("add(2,3) = " + new Calculator().add(2, 3))); layout.addView(add);
        Button overload = new Button(this); overload.setText("字符串重载"); overload.setOnClickListener(v -> result.setText(new Calculator().add("hello", " hook"))); layout.addView(overload);
        Button fail = new Button(this); fail.setText("调用异常方法"); fail.setOnClickListener(v -> { try { result.setText("fail() = " + new Calculator().fail()); } catch (IllegalStateException e) { result.setText(e.getMessage()); } }); layout.addView(fail);
        Button reconnect = new Button(this); reconnect.setText("重新连接管理端"); reconnect.setOnClickListener(v -> sendBroadcast(new Intent("io.github.easylsp.RECONNECT").setPackage(getPackageName()))); layout.addView(reconnect);
        workerReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { result.setText(":worker pid=" + i.getIntExtra("pid", 0) + " add(2,3)=" + i.getIntExtra("result", 0)); }
        };
        registerReceiver(workerReceiver, new IntentFilter("io.github.easylsp.demo.WORKER_RESULT"), Context.RECEIVER_NOT_EXPORTED);
        Button worker = new Button(this); worker.setText("子进程调用 add(2,3)"); worker.setOnClickListener(v -> startService(new Intent(this, DemoProcessService.class))); layout.addView(worker);
        Button dynamic = new Button(this); dynamic.setText("创建新 View（堆栈采集）"); dynamic.setOnClickListener(v -> { TextView text = new TextView(this); text.setText("New View " + System.currentTimeMillis()); layout.addView(text); }); layout.addView(dynamic);
        setContentView(layout);
    }
}
