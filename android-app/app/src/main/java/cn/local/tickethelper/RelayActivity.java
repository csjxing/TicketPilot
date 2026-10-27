package cn.local.tickethelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import cn.local.tickethelper.relay.RelayClient;
import cn.local.tickethelper.relay.RelayDiagnostics;
import cn.local.tickethelper.relay.RelaySecretsStore;
import cn.local.tickethelper.relay.RelayFailureMessage;
import cn.local.tickethelper.sync.DiagnosticReport;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-initiated, previewed sharing; no inbound commands or automatic phone control. */
public final class RelayActivity extends Activity {
    private static final int INK=Color.rgb(24,33,58), MUTED=Color.rgb(102,113,138), BLUE=Color.rgb(61,90,254);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private RelayDiagnostics relay;
    private RelaySecretsStore.Connection connection;
    private RelayDiagnostics.Pending pending;
    private RelayDiagnostics.Receipt receipt;
    private RelayClient.Cancellation cancellation;
    private boolean busy,destroyed;
    private String notice="";
    private LinearLayout body;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        relay=new RelayDiagnostics(this);
        execute("正在读取中转站连接…",()->{refresh();return "";});
    }
    @Override public void onDestroy() {
        destroyed=true; if(cancellation!=null)cancellation.cancel();
        main.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy();
    }
    private void refresh() throws Exception { connection=relay.connection();pending=relay.pending();receipt=relay.lastReceipt(); }
    private void render() {
        ScrollView scroll=new ScrollView(this);body=column();body.setPadding(dp(24),dp(24),dp(24),dp(32));scroll.addView(body);
        scroll.setBackgroundColor(Color.rgb(247,248,252));setContentView(scroll);
        getWindow().getDecorView().setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets b=insets.getInsets(android.view.WindowInsets.Type.systemBars());scroll.setPadding(b.left,b.top,b.right,b.bottom);return insets;
        });getWindow().getDecorView().requestApplyInsets();
        button(body,"‹ 返回",false,this::finish);
        text(body,"远程诊断",30,INK,true);
        text(body,"手机遇到问题，发送一份诊断。电脑从同一个文件中转站接着排查。",15,MUTED,false);
        if(!notice.isEmpty())text(body,notice,15,BLUE,true);
        if(busy) {
            Button cancel=button(body,"停止本次传输",false,()->{if(cancellation!=null)cancellation.cancel();});cancel.setEnabled(true);
        }
        LinearLayout connect=card();text(connect,"1  选择发送方式",20,INK,true);
        text(connect,"手机已经登录“远见中转”？直接交给它发送，无需在这里重复配置 Key。",15,MUTED,false);
        button(connect,"用远见中转 App 发送",true,this::prepareAppShare);
        text(connect,connection==null?"也可直接连接中转站，在票务助手内上传。": "直连已就绪 · "+connection.endpoint.origin,13,MUTED,false);
        if(connection==null)button(connect,"设置直连（选填）",false,this::connectDialog);
        else button(connect,"更换或断开连接",false,this::connectionActions);

        LinearLayout share=card();text(share,"2  把问题发给电脑",20,INK,true);
        text(share,"自动整理应用和大麦版本、权限状态、最近 63 条状态与错误记录。先查看，再发送。",15,MUTED,false);
        text(share,"不含页面截图、实名、账号、密钥、订单正文或原始日志。",13,MUTED,false);
        Button prepare=button(share,pending!=null&&!pending.complete?"查看上次未完成的诊断":"查看并发送诊断",true,this::prepare);
        prepare.setEnabled(!busy&&connection!=null);
        if(pending!=null&&!pending.complete) {
            text(share,"上次发送尚未确认，将继续同一份记录。",13,MUTED,false);
            button(share,"处理这份未完成诊断",false,this::discardDialog);
        }
        if(receipt!=null) {
            text(share,"中转站已确认接收",16,BLUE,true);
            text(share,"记录 "+receipt.itemId+"\n"+receipt.completedAt,12,MUTED,false);
        }

        LinearLayout next=card();text(next,"3  在中转站接着用",20,INK,true);
        text(next,"手机到“文件中转站”下载安装包；电脑搜索“TicketPilot 诊断”读取报告。每次更新仍由手机系统确认。",15,MUTED,false);
        button(next,"打开文件中转站 ↗",false,()->open(connection==null?"https://foresight.weisee.com/relay":connection.endpoint.relayPageUrl()));
        button(next,"在哪里获取 Key？",false,()->open("https://foresight.weisee.com/relay/android"));
        text(body,"这条链路用于发包和诊断。手机解锁、系统授权和验证码仍由你处理；上传不会启动抢票。",12,MUTED,false);
    }
    private void connectDialog() {
        LinearLayout form=column();form.setPadding(dp(22),dp(8),dp(22),0);
        EditText address=input("网站地址",false);address.setText(connection==null?"https://foresight.weisee.com":connection.endpoint.origin);form.addView(address);
        EditText key=input("文件中转站管理 Key",true);form.addView(key);
        text(form,"只需授予中转站管理权限。Key 加密保存在这台手机，不上传到 GitHub、诊断或 AI。",13,MUTED,false);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("连接你的中转站").setView(form)
                .setNegativeButton("取消",null).setPositiveButton("验证并连接",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String url=address.getText().toString().trim(), secret=key.getText().toString().trim();
            if(secret.isEmpty()){key.setError("请填写管理 Key");return;}
            key.setText("");dialog.dismiss();
            execute("正在验证中转站授权…",()->{relay.connect(url,secret,cancellation);refresh();return "已连接。现在可以查看并发送诊断。";});
        }));dialog.setOnDismissListener(d->key.setText(""));dialog.show();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void prepare() {
        execute("正在整理本机诊断…",()->{
            pending=relay.prepare(CloudDiagnostics.get(this).captureForSharing());return "";
        },this::showPreview);
    }
    private void prepareAppShare() {
        execute("正在整理本机诊断…",()->{
            DiagnosticReport report=CloudDiagnostics.get(this).captureForSharing();
            appShareJson=report.toJson().toString(2);return "";
        },()->new AlertDialog.Builder(this).setTitle("交给远见中转发送？")
                .setMessage("仅包含版本、权限和最近的状态/错误码。接下来会打开手机中的远见中转 App；请在那里点“发送”。电脑搜索“TicketPilot 诊断”即可读取。")
                .setNegativeButton("取消",null).setNeutralButton("查看内容",(d,w)->showAppSharePreview())
                .setPositiveButton("打开远见中转",(d,w)->sendViaRelayApp()).show());
    }
    private String appShareJson;
    private void showAppSharePreview() {
        TextView value=new TextView(this);value.setText(appShareJson);value.setTextIsSelectable(true);value.setTextSize(12);value.setPadding(dp(20),dp(12),dp(20),dp(12));
        ScrollView scroll=new ScrollView(this);scroll.addView(value);
        new AlertDialog.Builder(this).setTitle("实际分享内容").setView(scroll).setNegativeButton("取消",null)
                .setPositiveButton("交给远见中转",(d,w)->sendViaRelayApp()).show();
    }
    private void sendViaRelayApp() {
        if(appShareJson==null)return;
        Intent share=new Intent(Intent.ACTION_SEND).setType("text/plain").setPackage("com.weisee.foresight.relay")
                .putExtra(Intent.EXTRA_SUBJECT,"TicketPilot 诊断 · "+java.time.LocalDate.now())
                .putExtra(Intent.EXTRA_TEXT,appShareJson);
        try {startActivity(share);notice="已交给远见中转；请在那里点发送。尚未取得服务器回执。";}
        catch(android.content.ActivityNotFoundException missing){notice="尚未安装远见中转。可从中转站网页下载安装，或在这里设置直连。";}
        render();
    }
    private void showPreview() {
        if(pending==null)return;
        final RelayDiagnostics.Pending shown=pending;
        String detail="接收方："+shown.origin+"\n文件："+shown.filename+"\n大小："+shown.size+" 字节\n\n内容仅包含版本、权限、运行状态和固定错误码。电脑可以从中转站下载同一份 JSON。";
        new AlertDialog.Builder(this).setTitle("发送这份诊断？").setMessage(detail)
                .setNegativeButton("稍后",null).setNeutralButton("查看完整内容",(d,w)->showFullPreview(shown))
                .setPositiveButton(shown.complete?"已发送，查看记录":"发送到文件中转站",(d,w)->{
                    if(shown.complete){open(shown.origin+"/relay");return;} upload(shown);
                }).show();
    }
    private void showFullPreview(RelayDiagnostics.Pending shown) {
        TextView json=new TextView(this);json.setPadding(dp(20),dp(10),dp(20),dp(10));json.setTextSize(12);json.setTypeface(Typeface.MONOSPACE);json.setTextIsSelectable(true);
        try {json.setText(new org.json.JSONObject(shown.preview).toString(2));}catch(Exception invalid){return;}
        ScrollView scroll=new ScrollView(this);scroll.addView(json);
        new AlertDialog.Builder(this).setTitle("实际发送内容").setView(scroll).setNegativeButton("关闭",null)
                .setPositiveButton(shown.complete?"查看中转站":"发送这份诊断",(d,w)->{if(shown.complete)open(shown.origin+"/relay");else upload(shown);}).show();
    }
    private void upload(RelayDiagnostics.Pending shown) {
        execute("正在发送；断线后可继续同一份记录…",()->{
            receipt=relay.upload(shown.reportId,shown.sha256,cancellation);refresh();return "已发送。电脑搜索“TicketPilot 诊断”即可读取。";
        });
    }
    private void connectionActions() {
        new AlertDialog.Builder(this).setTitle("文件中转站连接").setItems(new String[]{"更换地址或 Key","断开本机连接"},(d,which)->{
            if(which==0)connectDialog();
            else execute("正在断开…",()->{relay.disconnect();refresh();return "本机已断开。中转站已有文件及购票资料未删除。";});
        }).show();
    }
    private void discardDialog() {
        new AlertDialog.Builder(this).setTitle("重新整理诊断？")
                .setMessage("先在中转站核查上次是否已收到。继续会清除本机这份诊断副本；云端文件、Key、购票配置和订单防重记录保留。")
                .setNegativeButton("保留并继续发送",(d,w)->showPreview())
                .setPositiveButton("清除本机诊断副本",(d,w)->execute("正在处理本机副本…",()->{relay.discardPending();refresh();return "可以重新整理当前诊断。";})).show();
    }
    private void execute(String status,Task task){execute(status,task,null);}
    private void execute(String status,Task task,Runnable after) {
        if(busy||destroyed)return;busy=true;notice=status;cancellation=new RelayClient.Cancellation();render();
        worker.execute(()->{
            String result;boolean succeeded=false;
            try {result=task.run();succeeded=true;}catch(Exception error){result=RelayFailureMessage.display(error,cancellation.isCancelled());}
            final String message=result;final boolean ok=succeeded;
            main.post(()->{if(destroyed)return;busy=false;notice=message;render();if(ok&&after!=null&&!cancellation.isCancelled())after.run();});
        });
    }
    private void open(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(RuntimeException noBrowser){notice="请在浏览器打开文件中转站。";render();}}
    private interface Task{String run()throws Exception;}
    private EditText input(String hint,boolean secret){EditText e=new EditText(this);e.setHint(hint);e.setSingleLine(true);e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);e.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);return e;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(18),dp(18),dp(18),dp(18));GradientDrawable b=new GradientDrawable();b.setColor(Color.WHITE);b.setCornerRadius(dp(18));c.setBackground(b);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(18);body.addView(c,p);return c;}
    private void text(LinearLayout target,String label,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(label);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,Typeface.BOLD);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);target.addView(t,p);}
    private Button button(LinearLayout target,String label,boolean primary,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(15);b.setTextColor(primary?Color.WHITE:BLUE);b.setMinHeight(dp(48));GradientDrawable bg=new GradientDrawable();bg.setColor(primary?BLUE:Color.rgb(237,240,255));bg.setCornerRadius(dp(12));b.setBackground(bg);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(12);target.addView(b,p);b.setEnabled(!busy);b.setOnClickListener(v->action.run());return b;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
