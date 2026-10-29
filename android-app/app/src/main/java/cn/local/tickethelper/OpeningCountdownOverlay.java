package cn.local.tickethelper;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import cn.local.tickethelper.core.CountdownText;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Display-only. The monitor owns the clock and lifecycle; this class never schedules an input. */
final class OpeningCountdownOverlay implements AutoCloseable {
    private final AccessibilityService service;
    private final WindowManager windows;
    private TextView view;
    private long renderedAt;
    private String openingLabel;
    OpeningCountdownOverlay(AccessibilityService service) {
        this.service=service; windows=service.getSystemService(WindowManager.class);
    }
    void update(Instant opening, long remainingMs) {
        if (opening==null || remainingMs<=0) { close(); return; }
        long now=SystemClock.elapsedRealtime();
        if (view!=null && now-renderedAt<50) return;
        try {
            if(view==null) {
                openingLabel=DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
                        .withZone(ZoneId.of("Asia/Shanghai")).format(opening);
                view=new TextView(service); view.setTextColor(Color.WHITE); view.setTextSize(16);
                view.setTypeface(Typeface.MONOSPACE);view.setGravity(Gravity.CENTER);
                view.setPadding(dp(14),dp(8),dp(14),dp(8));
                view.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                GradientDrawable bg=new GradientDrawable();bg.setColor(0xED18213A);bg.setCornerRadius(dp(12));view.setBackground(bg);
                WindowManager.LayoutParams p=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.y=dp(64);windows.addView(view,p);
            }
            boolean auto=Settings.Global.getInt(service.getContentResolver(),Settings.Global.AUTO_TIME,0)==1;
            view.setText("距开票  "+CountdownText.format(remainingMs)+"\n"+openingLabel+" 北京时间\n"
                    +(auto?"跟随系统时间":"系统自动时间未开启")+" · 通知栏可暂停");
            renderedAt=now;
        } catch(RuntimeException unavailable) { close(); }
    }
    @Override public void close() {
        if(view!=null) { try { windows.removeViewImmediate(view); } catch(RuntimeException ignored) { }
            view=null; }
        renderedAt=0;
    }
    private int dp(int n) { return Math.round(n*service.getResources().getDisplayMetrics().density); }
}
