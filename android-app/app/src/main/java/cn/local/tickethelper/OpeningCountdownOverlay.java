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
import android.widget.LinearLayout;
import android.widget.TextView;
import cn.local.tickethelper.core.CountdownText;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Monitor-owned countdown. Touches only move/fold this window; no ticket input is scheduled here. */
final class OpeningCountdownOverlay implements AutoCloseable {
    private final AccessibilityService service;
    private final WindowManager windows;
    private LinearLayout view;
    private TextView clock;
    private long renderedAt,lastRemaining;
    private Instant lastOpening;
    private boolean folded;
    private int fullX=-1,fullY=-1,smallY=-1;
    OpeningCountdownOverlay(AccessibilityService service) {this.service=service;windows=service.getSystemService(WindowManager.class);}
    void update(Instant opening,long remainingMs) {
        if(opening==null || remainingMs<=0){close();return;}
        lastOpening=opening;lastRemaining=remainingMs;
        long now=SystemClock.elapsedRealtime();if(view!=null && now-renderedAt<50)return;
        try {
            if(view==null)create();
            clock.setText((folded?"开售\n":"距开售  ")+CountdownText.format(remainingMs));
            renderedAt=now;
        } catch(RuntimeException unavailable){close();}
    }
    private TextView text(String value,int size) {
        TextView t=new TextView(service);t.setText(value);t.setTextSize(size);t.setTextColor(Color.WHITE);
        t.setPadding(dp(4),dp(5),dp(4),dp(5));return t;
    }
    private void create() {
        LinearLayout panel=new LinearLayout(service);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(10),dp(6),dp(10),dp(6));
        GradientDrawable bg=new GradientDrawable();bg.setColor(0xED18213A);bg.setCornerRadius(dp(12));panel.setBackground(bg);
        TextView handle=text(folded?"倒计时 ↗":"⠿ 开票倒计时",12);handle.setContentDescription("拖动倒计时，轻点展开");
        handle.setOnClickListener(v->{if(folded){folded=false;rebuild();}});
        if(folded)panel.addView(handle);
        else {
            LinearLayout heading=new LinearLayout(service);heading.setGravity(Gravity.CENTER_VERTICAL);
            heading.addView(handle,new LinearLayout.LayoutParams(0,-2,1));
            TextView fold=text("靠右收起",12);fold.setContentDescription("将倒计时收至右侧");
            fold.setOnClickListener(v->{folded=true;rebuild();});heading.addView(fold);panel.addView(heading);
        }
        clock=text("",folded?11:17);clock.setTypeface(Typeface.MONOSPACE);panel.addView(clock);
        if(!folded) {
            String label=DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.of("Asia/Shanghai")).format(lastOpening);
            boolean auto=Settings.Global.getInt(service.getContentResolver(),Settings.Global.AUTO_TIME,0)==1;
            panel.addView(text(label+" 北京时间\n"+(auto?"跟随系统时间":"系统自动时间未开启")+" · 通知栏可暂停",11));
        }
        int screen=windows.getCurrentWindowMetrics().getBounds().width(),width=dp(folded?124:300);
        width=Math.min(width,screen-dp(16));
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.x=folded?screen-width:fullX<0?(screen-width)/2:Math.min(fullX,screen-width);
        p.y=folded?(smallY<0?dp(120):smallY):(fullY<0?dp(64):fullY);p.setTitle("TicketPilot · 开售倒计时");
        windows.addView(panel,p);view=panel;OverlayDrag.keepOnScreen(panel,windows);
        OverlayDrag.bind(handle,panel,windows,(x,y)->{if(folded)smallY=y;else{fullX=x;fullY=y;}});
    }
    private void rebuild(){detach();renderedAt=0;update(lastOpening,lastRemaining);}
    private void detach(){if(view!=null){try{windows.removeViewImmediate(view);}catch(RuntimeException ignored){}view=null;}}
    @Override public void close(){detach();renderedAt=0;}
    private int dp(int n){return Math.round(n*service.getResources().getDisplayMetrics().density);}
}
