package cn.local.tickethelper;

import android.app.Activity;
import android.app.AlertDialog;
import cn.local.tickethelper.core.OpeningTime;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import cn.local.tickethelper.core.MonitorConfig;
import cn.local.tickethelper.core.SaleTiming;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Explicit local timing preferences. Save pauses; it does not arm a monitor or order. */
public final class TimingActivity extends Activity {
    private static final int INK=Color.rgb(24,33,58),MUTED=Color.rgb(102,113,138),BLUE=Color.rgb(61,90,254);
    private static final ZoneId CHINA=ZoneId.of("Asia/Shanghai");
    private AppStore store;
    private MonitorConfig config;
    private long revision;
    private LinearLayout body,openingFields;
    private RadioGroup startModes;
    private RadioButton nowMode,scheduledMode;
    private EditText minutes;
    private CheckBox fast;
    private TextView preview,error;
    private Button dateButton,timeButton;
    private LocalDateTime saleDateTime;
    private boolean hasOpeningTime;
    private long saleReadId;
    private final android.os.Handler uiHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private AlertDialog readDialog;
    private final Runnable readUpdate=new Runnable(){@Override public void run(){showReadResult();if(saleReadId!=0)uiHandler.postDelayed(this,400);}};
    private TextView readStatus;
    private Button cancelRead;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);store=new AppStore(this);
        pause("正在设置开售与持续监控时间，监控保持暂停。");
        config=store.loadConfig();revision=store.getConfigRevision();hasOpeningTime=config.saleAt!=null;
        saleDateTime=(config.saleAt==null?Instant.now():config.saleAt).atZone(CHINA).toLocalDateTime();
        if(saved!=null && saved.getLong("revision",-1)==revision) {
            saleReadId=saved.getLong("saleReadId",0);hasOpeningTime=saved.getBoolean("hasOpeningTime",hasOpeningTime);
            saleDateTime=Instant.ofEpochMilli(saved.getLong("draftTime",saleDateTime.atZone(CHINA).toInstant().toEpochMilli())).atZone(CHINA).toLocalDateTime();
        }
        ScrollView scroll=new ScrollView(this);body=column();body.setPadding(dp(24),dp(24),dp(24),dp(32));scroll.addView(body);
        scroll.setBackgroundColor(Color.rgb(247,248,252));setContentView(scroll);
        getWindow().getDecorView().setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets b=insets.getInsets(android.view.WindowInsets.Type.systemBars());scroll.setPadding(b.left,b.top,b.right,b.bottom);return insets;
        });getWindow().getDecorView().requestApplyInsets();
        button(body,"‹ 返回",false,this::finish);
        text(body,"开售与回流票",30,INK,true);
        text(body,"开售前准备好，开售后持续关注。有票后的动作仍按当前模式执行。",15,MUTED,false);

        LinearLayout when=card();text(when,"什么时候开始核查？",20,INK,true);
        button(when,"从大麦读取开售时间",true,this::readOpening);
        readStatus=text(when,hasOpeningTime?"开售 "+saleDateTime.format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")):"打开大麦显示开售时间的预约场次页，读取后只需确认。",13,MUTED,false);
        cancelRead=button(when,"停止本次读取",false,()->{SaleTimeCapture.cancel(saleReadId);saleReadId=0;cancelRead.setVisibility(View.GONE);readStatus.setText("已停止读取，原计划未更改。");});
        cancelRead.setVisibility(View.GONE);
        startModes=new RadioGroup(this);nowMode=choice("现在开始，立即核查");scheduledMode=choice("等待官方开售时间");
        startModes.addView(nowMode);startModes.addView(scheduledMode);when.addView(startModes);
        openingFields=column();when.addView(openingFields);
        LinearLayout manual=column();
        button(openingFields,"手动调整（备用）",false,()->manual.setVisibility(manual.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        openingFields.addView(manual);manual.setVisibility(View.GONE);
        dateButton=button(manual,"",false,this::pickDate);timeButton=button(manual,"",false,this::pickTime);
        text(openingFields,"启动后，大麦上会显示开票倒计时。跟随手机系统时间；建议开启系统自动日期和时间。毫秒显示不代表服务器校时或下单保证。",12,MUTED,false);

        LinearLayout duration=card();text(duration,"持续关注多久？",20,INK,true);
        minutes=new EditText(this);minutes.setInputType(InputType.TYPE_CLASS_NUMBER);minutes.setSingleLine(true);
        minutes.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});minutes.setHint("分钟，例如 30");
        minutes.setText(Integer.toString(config.followUpMinutes==0?30:config.followUpMinutes));duration.addView(minutes);
        LinearLayout presets=new LinearLayout(this);presets.setOrientation(LinearLayout.HORIZONTAL);duration.addView(presets);
        compact(presets,"30分钟",30);compact(presets,"60分钟",60);compact(presets,"沿用停止时间",0);
        text(duration,"等待开售时，从开售开始计时；已经开售或现在开始时，从本次启动计时。最迟在演出开始或原停止时间结束。",13,MUTED,false);

        LinearLayout rhythm=card();text(rhythm,"缺票时多久再查一次",20,INK,true);
        text(rhythm,"当前缺票刷新间隔 "+seconds(config.minIntervalMs)+"–"+seconds(config.maxIntervalMs)+" 秒",14,MUTED,false);
        fast=new CheckBox(this);fast.setText("使用快速刷新：0.5–1.5 秒");fast.setTextSize(15);rhythm.addView(fast);
        text(rhythm,"这段等待只用于缺票重查。有票后的选场次、选票档、数量核对连续推进，不在每一步再等一轮。页面加载耗时另计；本地读取不等于服务器刷新。",13,MUTED,false);

        LinearLayout result=card();text(result,"保存后会怎样？",20,INK,true);
        preview=text(result,"",15,INK,false);
        text(result,config.mode==MonitorConfig.Mode.ALERT?"当前：发现票提醒你。":config.mode==MonitorConfig.Mode.PREPARE?
                "当前：选票到确认购买页暂停。":"当前：在已核实授权内下单，等待你付款。",14,MUTED,false);
        text(result,"保存会让旧任务保持暂停。回计划点“开始”后才执行，不会因为保存而创建订单。",13,MUTED,false);
        error=text(body,"",14,Color.rgb(177,54,54),false);
        button(body,"保存时间设置",true,this::save);
        startModes.setOnCheckedChangeListener((group,id)->{openingFields.setVisibility(id==scheduledMode.getId()?View.VISIBLE:View.GONE);refreshPreview();});
        minutes.addTextChangedListener(new android.text.TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){refreshPreview();}
            @Override public void afterTextChanged(android.text.Editable value){}
        });
        startModes.check(config.saleAt==null?nowMode.getId():scheduledMode.getId());refreshDateButtons();refreshPreview();
        if(saved!=null && saved.getLong("revision",-1)==revision) {
            startModes.check(saved.getBoolean("scheduled")?scheduledMode.getId():nowMode.getId());
            minutes.setText(saved.getString("minutes",minutes.getText().toString()));fast.setChecked(saved.getBoolean("fast"));
        }
    }
    private void save() {
        try {
            if(scheduledMode.isChecked() && !hasOpeningTime) throw new IllegalArgumentException("请先从大麦读取并确认开售时间，或使用备用调整。");
            int follow=readMinutes();Instant sale=scheduledMode.isChecked()?saleDateTime.atZone(CHINA).toInstant():null;
            pause("时间设置已保存，请重新开始监控。");
            store.saveTiming(sale,follow,fast.isChecked(),revision);
            store.setStatus("开售与持续监控时间已保存，回计划点开始后执行。");
            Toast.makeText(this,"已保存，尚未开始",Toast.LENGTH_SHORT).show();finish();
        } catch (IllegalArgumentException invalid) {error.setText(invalid.getMessage());}
    }
    private int readMinutes() {
        try {
            String raw=minutes.getText().toString().trim();
            if (!raw.matches("[0-9]{1,4}"))throw new NumberFormatException();
            int value=Integer.parseInt(raw);
            if(value<0||value>MonitorConfig.MAX_FOLLOW_UP_MINUTES)throw new NumberFormatException();return value;
        } catch(NumberFormatException invalid){throw new IllegalArgumentException("请设置 1 至 1440 分钟；填 0 表示沿用原停止时间。");}
    }
    private void refreshPreview() {
        if(preview==null)return;
        try {
            int follow=readMinutes();Instant sale=scheduledMode.isChecked()?saleDateTime.atZone(CHINA).toInstant():null;
            String start=scheduledMode.isChecked() && !hasOpeningTime?"先从大麦读取开售时间":sale==null?"现在开始核查":sale.isAfter(Instant.now())?"等待 "+saleDateTime.format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS"))+" 开售":"设定开售时间已到，从本次启动核查";
            MonitorConfig planned=config.withTiming(sale,follow);
            long stop=SaleTiming.effectiveStopAtMs(planned,System.currentTimeMillis());
            preview.setText(start+"\n"+(follow==0?"沿用原停止时间": "持续关注 "+follow+" 分钟")+"\n最迟停止 "+Instant.ofEpochMilli(stop).atZone(CHINA).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")));
        } catch(RuntimeException invalid){preview.setText("填好时间后，这里会显示本次计划。");}
    }
    private void readOpening() {
        if(store.getConfigRevision()!=revision){error.setText("计划已变化，请返回后重新打开时间设置。");return;}
        MonitorService service=MonitorService.instance;
        if(service==null || !service.isConnectionReady()) { error.setText("请先连接页面读取服务。");return; }
        android.content.Intent launch=getPackageManager().getLaunchIntentForPackage("cn.damai");
        if(launch==null){error.setText("未找到大麦，请先打开大麦目标详情。");return;}
        saleReadId=SaleTimeCapture.start(service,config,revision);readStatus.setText(SaleTimeCapture.get(saleReadId).status);
        try {startActivity(launch);} catch(RuntimeException unavailable) {
            SaleTimeCapture.cancel(saleReadId);saleReadId=0;error.setText("暂时无法打开大麦，请稍后重试。");
        }
    }
    @Override protected void onResume() {
        super.onResume();uiHandler.removeCallbacks(readUpdate);uiHandler.post(readUpdate);
    }
    @Override protected void onPause() {
        uiHandler.removeCallbacks(readUpdate);
        if(readDialog!=null){readDialog.dismiss();readDialog=null;}
        super.onPause();
    }
    @Override public void finish() {SaleTimeCapture.cancel(saleReadId);saleReadId=0;super.finish();}
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putLong("saleReadId",saleReadId);out.putLong("revision",revision);out.putBoolean("hasOpeningTime",hasOpeningTime);
        out.putLong("draftTime",saleDateTime.atZone(CHINA).toInstant().toEpochMilli());
        out.putBoolean("scheduled",scheduledMode.isChecked());out.putString("minutes",minutes.getText().toString());out.putBoolean("fast",fast.isChecked());
        super.onSaveInstanceState(out);
    }
    private void showReadResult() {
        if(cancelRead!=null)cancelRead.setVisibility(saleReadId==0?View.GONE:View.VISIBLE);
        if(saleReadId==0 || readStatus==null || readDialog!=null)return;
        SaleTimeCapture.Result result=SaleTimeCapture.get(saleReadId);
        if(result==null){saleReadId=0;cancelRead.setVisibility(View.GONE);readStatus.setText("读取已结束，打开大麦目标详情后可重新读取。");return;}
        readStatus.setText(result.status);
        if(result.waiting)return;
        if(!result.usable(store.getConfigRevision(),android.os.SystemClock.elapsedRealtime(),System.currentTimeMillis())) {
            if(result.value!=null)readStatus.setText("时间结果已过期或计划已变化，请重新读取。");
            SaleTimeCapture.cancel(saleReadId);saleReadId=0;cancelRead.setVisibility(View.GONE);return;
        }
        Instant captured=result.value;
        readDialog=new AlertDialog.Builder(this).setTitle("确认开售时间")
                .setMessage(config.eventKeyword+" · "+config.city+"\n演出场次 "+config.date+" "+config.time+"\n开售 "+captured.atZone(CHINA).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))+" 北京时间")
                .setNegativeButton("取消",(d,w)->{SaleTimeCapture.cancel(saleReadId);saleReadId=0;})
                .setOnCancelListener(d->{SaleTimeCapture.cancel(saleReadId);saleReadId=0;})
                .setPositiveButton("使用这个时间",(d,w)->{
                    if(!result.usable(store.getConfigRevision(),android.os.SystemClock.elapsedRealtime(),System.currentTimeMillis())) {
                        error.setText("时间结果已过期或计划已变化，请重新读取。");return;
                    }
                    hasOpeningTime=true;saleDateTime=captured.atZone(CHINA).toLocalDateTime();scheduledMode.setChecked(true);
                    readStatus.setText("开售 "+saleDateTime.format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))+"，保存后生效。");
                    SaleTimeCapture.cancel(saleReadId);saleReadId=0;refreshDateButtons();refreshPreview();
                }).create();
        readDialog.setOnDismissListener(d->readDialog=null);readDialog.show();
    }
    private void pickDate(){new DatePickerDialog(this,(v,y,m,d)->{saleDateTime=saleDateTime.withDayOfMonth(1).withYear(y).withMonth(m+1).withDayOfMonth(d);refreshDateButtons();refreshPreview();},saleDateTime.getYear(),saleDateTime.getMonthValue()-1,saleDateTime.getDayOfMonth()).show();}
    private void pickTime() {
        EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789:."));
        input.setText(OpeningTime.format(saleDateTime.toLocalTime()));input.setSelectAllOnFocus(true);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("北京时间 HH:mm:ss.SSS")
                .setMessage("例如 15:00:00.000。毫秒是本机计划精度，不代表服务器同步或保证入场速度。")
                .setView(input).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            try {
                saleDateTime=java.time.LocalDateTime.of(saleDateTime.toLocalDate(),OpeningTime.parse(input.getText().toString().trim()));hasOpeningTime=true;
                refreshDateButtons();refreshPreview();dialog.dismiss();
            } catch(RuntimeException invalid){input.setError("请输入有效时间，例如 15:00:00.000");}
        }));dialog.show();
    }
    private void refreshDateButtons(){dateButton.setText("开售日期  "+saleDateTime.toLocalDate());timeButton.setText("开售时间  "+saleDateTime.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")));}
    private void pause(String reason){MonitorService service=MonitorService.instance;if(service!=null)service.pause(reason);else store.recordMonitorStop(reason,"PAUSED",false,false);}
    private RadioButton choice(String label){RadioButton r=new RadioButton(this);r.setId(View.generateViewId());r.setText(label);r.setTextSize(16);r.setMinHeight(dp(48));return r;}
    private void compact(LinearLayout row,String label,int value){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(12);row.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->minutes.setText(Integer.toString(value)));}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(18),dp(18),dp(18),dp(18));GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(18));c.setBackground(bg);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(18);body.addView(c,p);return c;}
    private TextView text(LinearLayout target,String label,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(label);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,Typeface.BOLD);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);target.addView(t,p);return t;}
    private Button button(LinearLayout target,String label,boolean primary,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(15);b.setTextColor(primary?Color.WHITE:BLUE);b.setMinHeight(dp(48));GradientDrawable bg=new GradientDrawable();bg.setColor(primary?BLUE:Color.rgb(237,240,255));bg.setCornerRadius(dp(12));b.setBackground(bg);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(12);target.addView(b,p);b.setOnClickListener(v->action.run());return b;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private String seconds(long ms){return java.math.BigDecimal.valueOf(ms,3).stripTrailingZeros().toPlainString();}
}
