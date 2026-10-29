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

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);store=new AppStore(this);
        pause("正在设置开售与持续监控时间，监控保持暂停。");
        config=store.loadConfig();revision=store.getConfigRevision();
        saleDateTime=(config.saleAt==null?Instant.now():config.saleAt).atZone(CHINA).toLocalDateTime();
        ScrollView scroll=new ScrollView(this);body=column();body.setPadding(dp(24),dp(24),dp(24),dp(32));scroll.addView(body);
        scroll.setBackgroundColor(Color.rgb(247,248,252));setContentView(scroll);
        getWindow().getDecorView().setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets b=insets.getInsets(android.view.WindowInsets.Type.systemBars());scroll.setPadding(b.left,b.top,b.right,b.bottom);return insets;
        });getWindow().getDecorView().requestApplyInsets();
        button(body,"‹ 返回",false,this::finish);
        text(body,"开售与回流票",30,INK,true);
        text(body,"开售前准备好，开售后持续关注。有票后的动作仍按当前模式执行。",15,MUTED,false);

        LinearLayout when=card();text(when,"什么时候开始核查？",20,INK,true);
        startModes=new RadioGroup(this);nowMode=choice("现在开始，立即核查");scheduledMode=choice("等待官方开售时间");
        startModes.addView(nowMode);startModes.addView(scheduledMode);when.addView(startModes);
        openingFields=column();when.addView(openingFields);
        dateButton=button(openingFields,"",false,this::pickDate);timeButton=button(openingFields,"",false,this::pickTime);
        text(openingFields,"这里是你的本机计划，请按大麦显示的官方开售时间选择；时间统一按北京时间。",12,MUTED,false);

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
    }
    private void save() {
        try {
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
            String start=sale==null?"现在开始核查":sale.isAfter(Instant.now())?"等待 "+saleDateTime.format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS"))+" 开售":"设定开售时间已到，从本次启动核查";
            MonitorConfig planned=config.withTiming(sale,follow);
            long stop=SaleTiming.effectiveStopAtMs(planned,System.currentTimeMillis());
            preview.setText(start+"\n"+(follow==0?"沿用原停止时间": "持续关注 "+follow+" 分钟")+"\n最迟停止 "+Instant.ofEpochMilli(stop).atZone(CHINA).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")));
        } catch(RuntimeException invalid){preview.setText("填好时间后，这里会显示本次计划。");}
    }
    private void pickDate(){new DatePickerDialog(this,(v,y,m,d)->{saleDateTime=saleDateTime.withDayOfMonth(1).withYear(y).withMonth(m+1).withDayOfMonth(d);refreshDateButtons();refreshPreview();},saleDateTime.getYear(),saleDateTime.getMonthValue()-1,saleDateTime.getDayOfMonth()).show();}
    private void pickTime() {
        EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(InputType.TYPE_CLASS_DATETIME);
        input.setText(OpeningTime.format(saleDateTime.toLocalTime()));input.setSelectAllOnFocus(true);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("北京时间 HH:mm:ss.SSS")
                .setMessage("例如 15:00:00.000。毫秒是本机计划精度，不代表服务器同步或保证入场速度。")
                .setView(input).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            try {
                saleDateTime=java.time.LocalDateTime.of(saleDateTime.toLocalDate(),OpeningTime.parse(input.getText().toString().trim()));
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
