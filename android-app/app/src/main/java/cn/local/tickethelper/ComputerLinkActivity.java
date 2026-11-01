package cn.local.tickethelper;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.widget.*;
import android.view.View;

/** Small phone-side entry for a user-initiated, same-Wi-Fi desktop development session. */
public final class ComputerLinkActivity extends Activity {
    private final Handler handler=new Handler(android.os.Looper.getMainLooper());
    private ComputerLinkStore store;private TextView status;private Button begin,end;
    private final Runnable refresh=new Runnable(){public void run(){update();handler.postDelayed(this,1000);}};
    @Override public void onCreate(Bundle state){super.onCreate(state);store=new ComputerLinkStore(this);
        getWindow().setStatusBarColor(Color.rgb(247,248,252));getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setDecorFitsSystemWindows(false);
        ScrollView scroll=new ScrollView(this);scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24),dp(16),dp(24),dp(30));page.setBackgroundColor(Color.rgb(247,248,252));scroll.addView(page);setContentView(scroll);
        button(page,"‹ 返回",()->finish());text(page,"电脑协作",30,true);
        text(page,"在同一 Wi-Fi 下，让电脑上的 AI 帮你检查和改进。",15,false);
        status=text(page,"",19,true);
        begin=button(page,"允许电脑协作 15 分钟",()->{
            try{MonitorService m=MonitorService.instance;if(m!=null)m.pause("电脑协作中，手机监控暂停。");new AppStore(this).setArmed(false);store.begin();update();}
            catch(Exception e){toast("未能开启，请重试。");}
        });
        end=button(page,"结束电脑协作",()->{store.end();update();});
        text(page,"协作期间手机自动监控暂停。每次只接手一台电脑；结束后在手机重新开始。",13,false);
        text(page,"1  手机打开无线调试",20,true);
        button(page,"打开无线调试设置",()->{
            Intent i=new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS");
            if(i.resolveActivity(getPackageManager())==null)i=new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            try{startActivity(i);}catch(Exception e){toast("请在系统设置搜索“无线调试”。");}
        });
        text(page,"打开“使用配对码配对设备”。配对地址用于配对；详情页的 IP 地址和端口用于连接。换电脑需重新配对。",14,false);
        text(page,"2  电脑运行连接工具",20,true);
        text(page,"从项目或电脑交接包运行：\npython3 handoff/computer_link.py gui\nWindows 可用 py -3。电脑需先安装官方 Android Platform Tools。",14,false);
        button(page,"复制电脑启动说明",()->copy(ComputerLinkGuide.bootstrap()));
        button(page,"导出电脑交接包",()->startActivity(new Intent(this,TransferActivity.class)));
        text(page,"3  交给电脑上的 AI",20,true);
        button(page,"复制给电脑 AI 的说明",()->copy(ComputerLinkGuide.aiPrompt()));
        text(page,"电脑工具可检查状态、取得本次协作权、读取脱敏诊断和暂停。模型分析和代码改进在电脑上完成；截图、实名和原始页面不自动上传模型。",14,false);
        button(page,"查看抢票记录",()->startActivity(new Intent(this,HistoryActivity.class)));
        text(page,"本版支持同一 Wi-Fi。手机解锁、无线调试和电脑配对仍由系统确认；本页不会替你开启系统权限。",13,false);
    }
    private void update(){if(store==null)return;long left=store.remaining();cn.local.tickethelper.computer.ComputerLease l=store.read();
        status.setText(left==0?"电脑协作未开启":(l!=null&&!l.owner.isEmpty()?"电脑已接手":"等待电脑接手")+" · 剩余 "+((left+59_999)/60_000)+" 分钟");
        begin.setEnabled(left==0);end.setEnabled(left>0);
    }
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    private void copy(String value){getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("TicketPilot 电脑协作",value));toast("已复制");}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(LinearLayout p,String s,int size,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(Color.rgb(24,33,58));if(bold)v.setTypeface(null,Typeface.BOLD);LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(-1,-2);a.topMargin=dp(18);p.addView(v,a);return v;}
    private Button button(LinearLayout p,String s,Runnable action){Button v=new Button(this);v.setText(s);v.setAllCaps(false);v.setTextSize(16);v.setOnClickListener(x->action.run());LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(-1,-2);a.topMargin=dp(12);p.addView(v,a);return v;}
}
