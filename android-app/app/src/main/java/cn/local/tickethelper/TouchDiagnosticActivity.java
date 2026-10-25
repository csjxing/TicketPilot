package cn.local.tickethelper;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.AtomicFile;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Registered only in the debug manifest; no test starts until its visible button is pressed. */
public final class TouchDiagnosticActivity extends Activity implements TouchDiagnostics.Host {
    private Button start, probe;
    private TextView progress;
    private TouchDiagnostics diagnostics;
    private volatile boolean resumed, focused;
    private boolean counting;
    private int clicks, down, up;
    private JSONArray touches = new JSONArray();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(30), dp(24), dp(24));
        ScrollView scroll = new ScrollView(this); scroll.addView(content); setContentView(scroll);
        TextView title = new TextView(this); title.setText("票务助手 · 触控自检"); title.setTextSize(24); content.addView(title);
        TextView description = new TextView(this);
        description.setText("仅测试本页面的按钮：节点点击、70 毫秒手势、200 毫秒手势各一次。监控须暂停，测试时保持本页在前台。不会操作大麦或提交订单。");
        description.setTextSize(16); content.addView(description);
        start = new Button(this); start.setText("运行触控自检"); content.addView(start);
        probe = new Button(this); probe.setId(View.generateViewId());
        probe.setText("测试按钮 · 点击 0 次");
        content.addView(probe, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(110)));
        probe.setOnClickListener(v -> {
            if (counting) { clicks++; probe.setText("测试按钮 · 点击 " + clicks + " 次"); }
        });
        progress = new TextView(this); progress.setText("尚未运行。"); progress.setTextSize(16); content.addView(progress);
        start.setOnClickListener(v -> {
            MonitorService service = MonitorService.instance;
            if (service == null || service.isRunning()) {
                progress.setText("请先启用票务助手无障碍服务，并暂停票务监控。"); return;
            }
            start.setEnabled(false); progress.setText("正在测试，请勿触摸其他按钮或切换应用。\n");
            diagnostics = new TouchDiagnostics(service, this); diagnostics.start();
        });
    }

    @Override protected void onResume() { super.onResume(); resumed = true; }
    @Override protected void onPause() {
        resumed = false;
        if (diagnostics != null) diagnostics.cancel("activity_paused");
        super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); focused = hasFocus; }
    @Override public boolean isReady() { return resumed && focused && !isFinishing() && !isDestroyed(); }

    @Override public void prepareProbe(String marker) {
        clicks = 0; down = 0; up = 0; touches = new JSONArray(); counting = true;
        probe.setText("测试按钮 · 点击 0 次"); probe.setContentDescription(marker);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (counting && (event.getActionMasked() == MotionEvent.ACTION_DOWN || event.getActionMasked() == MotionEvent.ACTION_UP)) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) down++; else up++;
            if (touches.length() < 8) {
                JSONObject touch = new JSONObject();
                TouchDiagnostics.put(touch, "action", event.getActionMasked() == MotionEvent.ACTION_DOWN ? "down" : "up");
                TouchDiagnostics.put(touch, "raw_x", event.getRawX());
                TouchDiagnostics.put(touch, "raw_y", event.getRawY());
                int[] location = new int[2]; probe.getLocationOnScreen(location);
                Rect bounds = new Rect(location[0], location[1], location[0] + probe.getWidth(), location[1] + probe.getHeight());
                TouchDiagnostics.put(touch, "inside_probe", bounds.contains((int) event.getRawX(), (int) event.getRawY()));
                touches.put(touch);
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override public JSONObject probeCounts() {
        JSONObject result = new JSONObject();
        TouchDiagnostics.put(result, "clicks", clicks);
        TouchDiagnostics.put(result, "down", down);
        TouchDiagnostics.put(result, "up", up);
        TouchDiagnostics.put(result, "touches", touches);
        return result;
    }

    @Override public void stageFinished(JSONObject result) {
        counting = false;
        JSONObject counts = result.optJSONObject("counts");
        progress.append(result.optString("stage") + "：" + (result.optBoolean("passed") ? "通过" : "未通过")
                + "，点击 " + counts.optInt("clicks") + "，按下/抬起 " + counts.optInt("down") + "/" + counts.optInt("up") + "\n");
    }

    @Override public void finished(JSONObject result) {
        counting = false; start.setEnabled(true); probe.setContentDescription("touch_probe_idle");
        String saved = saveReport(result);
        progress.append("结束：" + result.optString("status") + "。" + saved
                + "\n本页通过也不能证明大麦自动选票成功。");
    }

    private String saveReport(JSONObject result) {
        AtomicFile output = new AtomicFile(new File(getFilesDir(), "touch-diagnostic.json"));
        FileOutputStream stream = null;
        try {
            stream = output.startWrite();
            stream.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
            output.finishWrite(stream);
            return "诊断记录已保存在本应用内。";
        } catch (Exception failure) {
            if (stream != null) output.failWrite(stream);
            return "诊断记录保存失败：" + failure.getClass().getSimpleName();
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
