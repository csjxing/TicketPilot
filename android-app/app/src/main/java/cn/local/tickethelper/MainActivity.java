package cn.local.tickethelper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import cn.local.tickethelper.ai.AiContextRepository;
import cn.local.tickethelper.checkout.LockAuthorization;
import cn.local.tickethelper.checkout.OrderAttemptStore;
import cn.local.tickethelper.core.IntervalSeconds;
import cn.local.tickethelper.core.MonitorConfig;
import cn.local.tickethelper.core.MonitorStopState;
import cn.local.tickethelper.core.PilotJourney;
import cn.local.tickethelper.core.TaskPreferences;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Daily workspace. Forms and diagnostic tools live in AdvancedSettingsActivity. */
public final class MainActivity extends Activity {
    public static final String EXTRA_ROUTE = "route";
    private static final int BG = Color.rgb(247,248,252), INK = Color.rgb(24,33,58);
    private static final int MUTED = Color.rgb(102,113,138), BLUE = Color.rgb(61,90,254);
    private static final int LINE = Color.rgb(228,232,242), TINT = Color.rgb(238,241,255);
    private static final int EXPORT = 41, NOTIFY = 42, SHIZUKU = 43;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AppStore store;
    private LinearLayout shell, body, footer, navigation;
    private ScrollView scroll;
    private EditText search, minimum, maximum;
    private TextView preferenceError;
    private String route = "plan", query = "", lastRender = "";
    private MonitorConfig preferenceBase;
    private MonitorConfig.Mode preferredMode;
    private long preferenceRevision;
    private boolean active, customPace;
    private long preferredMin, preferredMax;
    private String draftMin, draftMax;
    private StartReview pageReview;
    private MonitorConfig reviewConfig;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!"preferences".equals(route) && (search == null || !search.hasFocus())) {
                String signature = signature();
                if (!signature.equals(lastRender)) render();
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new AppStore(this);
        route = route(state == null ? getIntent().getStringExtra(EXTRA_ROUTE) : state.getString("route"));
        query = state == null ? "" : state.getString("query", "");
        // Uncommitted preference drafts and start confirmations never resume after recreation.
        if ("preferences".equals(route)) beginPreferences();
        if ("review".equals(route)) { reviewConfig = store.loadConfig(); pageReview = new StartReview(reviewConfig, store.getConfigRevision()); }
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        shell = column(); shell.setBackgroundColor(BG);
        shell.setFocusableInTouchMode(true);
        scroll = new ScrollView(this); scroll.setFillViewport(true);
        body = column(); body.setPadding(dp(24), dp(18), dp(24), dp(24)); scroll.addView(body);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        footer = column(); footer.setPadding(dp(24), dp(12), dp(24), dp(14)); footer.setBackgroundColor(Color.WHITE); shell.addView(footer);
        navigation = row(); navigation.setPadding(dp(12), dp(5), dp(12), dp(6)); navigation.setBackgroundColor(Color.WHITE); shell.addView(navigation);
        shell.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            navigation.setVisibility(ime.bottom > bars.bottom ? View.GONE : View.VISIBLE);
            return insets;
        });
        setContentView(shell); shell.requestFocus(); render();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        String next = intent.getStringExtra(EXTRA_ROUTE);
        if (intent.getBooleanExtra(CatalogActivity.EXTRA_APPLIED, false) || intent.getBooleanExtra("show_task", false)) next = "plan";
        navigate(route(next));
        intent.removeExtra(CatalogActivity.EXTRA_APPLIED); intent.removeExtra("show_task");
    }

    private String route(String value) {
        return List.of("plan", "setup", "preferences", "account", "help", "review", "recovery").contains(value == null ? "" : value) ? value : "plan";
    }

    private void navigate(String next) {
        rememberQuery();
        if ("preferences".equals(next)) { pause("正在调整购票条件，尚未开始。"); beginPreferences(); }
        if ("setup".equals(next)) pause("正在准备手机，完成后请重新开始。");
        if ("review".equals(next)) { reviewConfig = store.loadConfig(); pageReview = new StartReview(reviewConfig, store.getConfigRevision()); }
        route = next; render(); scroll.scrollTo(0,0);
    }

    private void beginPreferences() {
        preferenceBase = store.loadConfig(); preferenceRevision = store.getConfigRevision();
        preferredMode = preferenceBase.mode; preferredMin = preferenceBase.minIntervalMs; preferredMax = preferenceBase.maxIntervalMs;
        draftMin = null; draftMax = null;
        customPace = !((preferredMin == 500 && preferredMax == 1500) || (preferredMin == 1000 && preferredMax == 3000) || (preferredMin == 5000 && preferredMax == 10000));
    }

    private void rememberQuery() { if (search != null) query = search.getText().toString(); }

    private String signature() {
        MonitorService s = MonitorService.instance;
        return route + '|' + store.getConfigRevision() + '|' + store.getStatus() + '|' + notificationsReady()
                + '|' + (s != null) + '|' + (s != null && s.isRunning()) + '|' + (s == null ? 0 : s.waitingForDamaiSeconds())
                + '|' + ShizukuInputBridge.get(this).status();
    }

    private void render() {
        if (body == null) return;
        rememberQuery(); search = null;
        body.removeAllViews(); footer.removeAllViews(); navigation.removeAllViews();
        footer.setVisibility(View.VISIBLE);
        if ("preferences".equals(route)) renderPreferences();
        else if ("review".equals(route)) renderStartReview();
        else if ("setup".equals(route)) renderSetup();
        else if ("account".equals(route)) renderAccount();
        else if ("help".equals(route)) renderHelp();
        else if ("recovery".equals(route)) renderRecovery();
        else renderPlan();
        navItem("计划", "plan", () -> navigate("plan"));
        navItem("领航员", "navigator", () -> openNavigator());
        navItem("我的", "account", () -> navigate("account"));
        lastRender = signature();
    }

    private void renderPlan() {
        MonitorConfig c = store.loadConfig(); PilotJourney.Result journey = journey(c);
        LinearLayout brand = row(); brand.addView(text("TicketPilot", 21, INK, true), weighted());
        brand.addView(tag("手机本地运行", MUTED, BG)); body.addView(brand);
        boolean hasTarget = !c.eventKeyword.isEmpty();
        if (!hasTarget) {
            add(body, "下一站，现场。", 34, INK, true, 28);
            add(body, "选一场想看的，领航员带你完成准备。", 14, MUTED, false, 10);
            LinearLayout searchRow = row(); searchRow.setBackground(shape(Color.WHITE, LINE, 16));
            search = new EditText(this); search.setSingleLine(); search.setTextSize(17); search.setTextColor(INK);
            search.setHint("艺人、演出或城市"); search.setHintTextColor(MUTED); search.setText(query);
            search.setBackgroundColor(Color.TRANSPARENT); search.setPadding(dp(16),dp(16),dp(8),dp(16));
            search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            search.setImeOptions(EditorInfo.IME_ACTION_SEARCH); search.setContentDescription("搜索演出或城市");
            search.setOnEditorActionListener((v,a,e) -> { if (a != EditorInfo.IME_ACTION_SEARCH) return false; openCatalog(); return true; });
            searchRow.addView(search,weighted()); searchRow.addView(button("搜索", false, v -> openCatalog())); body.addView(searchRow, space(22));
            body.addView(textAction("还没想好？让领航员帮我整理需求  ↗", () -> openAi("配置建议", "购票需求", query.isBlank() ? "我想看演出，请帮我明确艺人、城市、日期和预算，先问我最重要的一项。" : query)), space(12));
        } else renderTicket(c);

        LinearLayout next = column(); next.setPadding(dp(20),dp(20),dp(20),dp(20)); next.setBackground(shape(INK,INK,18));
        add(next, journey.eyebrow, 11, Color.rgb(174,187,225),true,0);
        add(next, journey.title, 22, Color.WHITE,true,10);
        String detail = journey.detail;
        if (MonitorService.instance != null && MonitorService.instance.isWaitingForDamai()) detail = "请在 " + MonitorService.instance.waitingForDamaiSeconds() + " 秒内通过最近任务切回目标票档页。";
        add(next, detail, 14, Color.rgb(211,220,242),false,8);
        body.addView(next, space(24));
        body.addView(textAction("不知道怎么继续？打开领航员  ↗", this::openNavigator),space(8));
        if (hasTarget) {
            section("购票条件", () -> navigate("preferences"), "调整");
            summaryRow("有票以后", mode(c.mode));
            summaryRow("检查间隔", IntervalSeconds.format(c.minIntervalMs) + "–" + IntervalSeconds.format(c.maxIntervalMs) + " 秒");
            summaryRow("实付上限", "¥" + c.maxTotal + " · " + c.quantity + " 张");
            add(body,"操作间隔不包含页面加载时间。",12,MUTED,false,7);
        }
        actionFooter(journey.button, journey.outcome, () -> perform(journey.action));
    }

    private void renderTicket(MonitorConfig c) {
        add(body,"我的购票计划",13,MUTED,true,24);
        LinearLayout ticket = column(); ticket.setPadding(dp(20),dp(22),dp(20),dp(20)); ticket.setBackground(shape(Color.WHITE,LINE,20));
        LinearLayout top = row(); top.addView(tag(c.city.isBlank() ? "城市待选" : c.city,BLUE,TINT),weighted());
        top.addView(text("01 / TICKET",11,MUTED,true)); ticket.addView(top);
        String title = CatalogActivity.selectedTitle(this,c); if (title.isBlank()) title=c.eventKeyword;
        add(ticket,title,23,INK,true,18);
        add(ticket,displayDate(c) + "  " + c.time,15,MUTED,false,10);
        View divider = new View(this); divider.setBackgroundColor(LINE); LinearLayout.LayoutParams dividerParams=space(20);dividerParams.height=dp(1);ticket.addView(divider,dividerParams);
        LinearLayout bottom=row(); LinearLayout money=column();
        add(money,priceText(c),22,INK,true,0); add(money,"接受票价 · 尚未生成订单",11,MUTED,false,4);
        bottom.addView(money,weighted());bottom.addView(tag(c.quantity+" 张",BLUE,TINT));ticket.addView(bottom,space(18));
        ticket.addView(textAction("修改演出 / 场次 / 票价  ›", this::openCatalog),space(14));body.addView(ticket,space(10));
    }

    private void renderPreferences() {
        header("购票条件","先选好处理方式，再决定检查节奏。", () -> navigate("plan"));
        add(body,"发现票以后",20,INK,true,26);
        choice("只提醒我","有票时通知我，我去大麦继续操作。",preferredMode==MonitorConfig.Mode.ALERT,true,() -> {readCustomPace();preferredMode=MonitorConfig.Mode.ALERT;render();});
        choice("帮我选票","选到确认购买页，提交订单和付款由我完成。",preferredMode==MonitorConfig.Mode.PREPARE,true,() -> {readCustomPace();preferredMode=MonitorConfig.Mode.PREPARE;render();});
        choice("自动锁票 · 尚未就绪","真实订单适配未完成，暂时不能选择。",false,false,() -> {});
        add(body,"检查节奏",20,INK,true,28);
        LinearLayout speeds=row();
        paceChip(speeds,"快速","0.5–1.5秒",500,1500);paceChip(speeds,"常用","1–3秒",1000,3000);paceChip(speeds,"稳健","5–10秒",5000,10000);
        body.addView(speeds,space(12));
        body.addView(textAction(customPace ? "自定义间隔已展开" : "自定义间隔  ›", () -> {customPace=true;render();}),space(9));
        minimum=null;maximum=null;
        if(customPace) {
            LinearLayout inputs=row();minimum=number(draftMin == null ? IntervalSeconds.format(preferredMin) : draftMin,"最短秒数");maximum=number(draftMax == null ? IntervalSeconds.format(preferredMax) : draftMax,"最长秒数");
            inputs.addView(minimum,weighted());LinearLayout.LayoutParams gap=weighted();gap.leftMargin=dp(12);inputs.addView(maximum,gap);body.addView(inputs,space(9));
        }
        add(body,"这是操作后的等待区间，页面加载另需时间。",12,MUTED,false,12);
        body.addView(textAction("让领航员解释怎么选  ↗", () -> openAi("配置建议","购票条件","请解释提醒、选票到确认页的区别，以及快速、常用、稳健的操作节奏。不要承诺服务器刷新频率或购票成功。")),space(12));
        preferenceError=text("",13,Color.rgb(184,51,63),false);body.addView(preferenceError,space(8));
        actionFooter("保存条件","仅保存设置，不会自动开始。",this::savePreferences);
    }

    private void renderStartReview() {
        header("准备开始", "再看一眼这场演出，然后按下方两步继续。", () -> navigate("plan"));
        MonitorConfig c = reviewConfig;
        if (c == null || pageReview == null) { navigate("plan"); return; }
        renderTicket(c);
        summaryRow("处理方式", mode(c.mode));
        summaryRow("实付上限", "¥" + c.maxTotal);
        if (store.isImportedTargetPendingReview() || !validTarget(c)) {
            actionFooter("重新选择并核对", "场次需要从大麦重新核对，暂不能开始。", this::openCatalog); return;
        }
        if (!deviceReady()) {
            actionFooter("先完成手机准备", "连接或权限尚未准备好，完成后回来继续。", () -> navigate("setup")); return;
        }
        if (c.mode == MonitorConfig.Mode.LOCK_ORDER) {
            actionFooter("选择可用处理方式", "自动锁票尚未完成，请选择提醒或帮我选票。", () -> navigate("preferences")); return;
        }
        boolean orderNeedsReview;
        try { orderNeedsReview = new OrderAttemptStore(getFilesDir()).read() != null; }
        catch (Exception e) { orderNeedsReview = true; }
        if (orderNeedsReview) {
            actionFooter("去大麦核查订单", "存在提交记录或记录无法读取，不能再次开始。", this::openDamai); return;
        }
        setupStep(1, "先准备好大麦页面", false, "在大麦打开这场演出的票档选择页，再返回这里。");
        body.addView(textAction("去大麦打开票档页  ↗", this::openDamai), space(4));
        setupStep(2, "点开始，再切回大麦", false, "20秒内通过最近任务切回刚才的票档页。保持亮屏，助手才会继续。");
        boolean unchanged = pageReview.revision == store.getConfigRevision();
        actionFooter(unchanged ? "票档已打开，开始" : "计划已更新，重新核对",
                "当前只会提醒或选票到确认页，尚不能自动锁票。", () -> {
                    if (!unchanged) navigate("review"); else executeStart(pageReview);
                });
    }

    private void paceChip(LinearLayout parent,String title,String sub,long min,long max) {
        boolean selected=!customPace&&preferredMin==min&&preferredMax==max;
        Button b=button(title+"\n"+sub,selected,v->{preferredMin=min;preferredMax=max;draftMin=null;draftMax=null;customPace=false;render();});
        LinearLayout.LayoutParams p=weighted(); if(parent.getChildCount()>0)p.leftMargin=dp(8);parent.addView(b,p);
    }

    private void readCustomPace() {
        if(customPace&&minimum!=null&&maximum!=null) {
            draftMin=minimum.getText().toString(); draftMax=maximum.getText().toString();
            try {preferredMin=IntervalSeconds.parse(draftMin);preferredMax=IntervalSeconds.parse(draftMax);}catch(IllegalArgumentException ignored) { }
        }
    }

    private void savePreferences() {
        if(preferenceRevision!=store.getConfigRevision()){message("计划已更新","请返回计划，重新调整条件。");return;}
        try {
            long min=customPace&&minimum!=null?IntervalSeconds.parse(minimum.getText().toString()):preferredMin;
            long max=customPace&&maximum!=null?IntervalSeconds.parse(maximum.getText().toString()):preferredMax;
            if(preferredMode==MonitorConfig.Mode.LOCK_ORDER){preferenceError.setText("请选择提醒或帮我选票。自动锁票还未完成。");return;}
            MonitorConfig changed=TaskPreferences.withMode(TaskPreferences.withPace(preferenceBase,min,max),preferredMode);
            pause("已保存购票条件，尚未开始。");store.saveConfig(changed);navigate("plan");toast("条件已保存");
        }catch(IllegalArgumentException e){preferenceError.setText("间隔应为0.5–600秒，最长值不能小于最短值。");}
    }

    private void renderSetup() {
        header("准备好这台手机","只需完成一次；返回这里会自动检查进度。",() -> navigate("plan"));
        boolean page=MonitorService.instance!=null,alerts=notificationsReady(),click=inputReady();
        setupStep(1,"读取大麦页面",page,"只开启票务助手服务，无需 TalkBack。");
        setupStep(2,"收到有票提醒",alerts,"发现变化或需要你处理时通知你。");
        setupStep(3,"连接点击服务",click,AppStore.INPUT_SHIZUKU.equals(store.getInputMode())?ShizukuInputBridge.get(this).status():"当前使用标准触控，依赖页面读取服务。");
        if(!damaiInstalled())add(body,"尚未发现官方大麦，请先在手机安装。",14,INK,true,18);
        body.addView(textAction("这一步怎么做？让领航员带我  ↗",() -> openAi("异常分析","手机准备","请根据当前准备状态，明确我接下来只需要点哪个按钮、去哪个页面，完成后怎样返回。")),space(20));
        body.addView(textAction("点击方式与高级权限  ›",() -> openAdvanced("phone")),space(8));
        String label=!page?"开启页面读取":!alerts?"允许提醒通知":!click?inputNextLabel():!damaiInstalled()?"查看安装说明":"准备完成，去选票";
        actionFooter(label,page&&alerts&&click&&damaiInstalled()?"返回计划，选择演出或开始。":"按提示完成后，再返回 TicketPilot。",() -> {
            if(!page)discloseAccessibility();else if(!alerts)requestNotifications();else if(!click)prepareInput();else if(!damaiInstalled())message("需要大麦","请安装官方大麦并登录后返回。" );else navigate("plan");
        });
    }

    private void setupStep(int number,String title,boolean done,String detail) {
        LinearLayout line=row();TextView badge=tag(done?"✓":String.valueOf(number),done?BLUE:MUTED,done?TINT:Color.WHITE);line.addView(badge);
        LinearLayout words=column();add(words,title+(done?" · 已完成":""),17,INK,true,0);add(words,done?"可以继续下一步。":detail,13,MUTED,false,5);
        LinearLayout.LayoutParams p=weighted();p.leftMargin=dp(14);line.addView(words,p);body.addView(line,space(26));
    }

    private void renderAccount() {
        add(body,"我的",30,INK,true,8);add(body,"把工具留在这里，把注意力留给现场。",14,MUTED,false,8);
        section("常用",null,"");
        menuRow("AI 模型","接口、密钥与用量",() -> {pause("正在设置模型，监控暂停。");startActivity(new Intent(this,AiAssistantActivity.class).putExtra("screen","settings").putExtra("origin_label","我的"));});
        menuRow("手机准备",deviceReady()?"已具备运行条件":"检查还缺哪一步",() -> navigate("setup"));
        menuRow("购票条件","有票后的动作与检查速度",() -> navigate("preferences"));
        menuRow("使用指南与记录","查步骤、查看运行结果",() -> navigate("help"));
        section("维护",null,"");
        menuRow("高级工具","手动目标、兼容设置与导出",() -> openAdvanced("target"));
        menuRow("电脑接手与备份","源码、知识库与交接资料",() -> startActivity(new Intent(this,TransferActivity.class)));
        add(body,"TicketPilot 0.2.0 · 当前仅支持1张\n自动锁票尚未开放，付款由你完成。",12,MUTED,false,28);
        footer.setVisibility(View.GONE);
    }

    private void renderHelp() {
        header("使用指南与记录","先看下一步，需要时再查详细原因。",() -> navigate("account"));
        menuRow("领航员带我继续","根据当前步骤给出下一步",this::openNavigator);
        menuRow("离线使用指南","权限、选演出、手机独立使用",() -> startActivity(new Intent(this,HelpActivity.class)));
        section("最近状态",null,"");
        add(body,safeStatus(),14,INK,false,0);
        add(body,"已记录 "+store.historyCount()+" 次处理结果",12,MUTED,false,14);
        menuRow("导出检查记录","保存本机检查结果用于排查",this::exportHistory);
        footer.setVisibility(View.GONE);
    }

    private PilotJourney.Result journey(MonitorConfig c) {
        PilotJourney.Input i=new PilotJourney.Input();MonitorService s=MonitorService.instance;
        i.running=s!=null&&s.isRunning();i.waitingForDamai=s!=null&&s.isWaitingForDamai();
        i.hasTarget=!c.eventKeyword.isEmpty();i.targetValid=validTarget(c);i.importedPendingReview=store.isImportedTargetPendingReview();
        i.deviceReady=deviceReady();i.unsupportedMode=c.mode==MonitorConfig.Mode.LOCK_ORDER;
        i.stopState=store.getMonitorStopState();
        i.paused=!i.running&&i.stopState==MonitorStopState.NONE&&isPaused(safeStatus());
        // Historical text and model replies never establish checkout/payment success.
        try {if(new OrderAttemptStore(getFilesDir()).read()!=null)i.orderState=PilotJourney.OrderState.NEEDS_REVIEW;}
        catch(Exception e){i.orderState=PilotJourney.OrderState.NEEDS_REVIEW;}
        return PilotJourney.resolve(i);
    }

    private void perform(PilotJourney.Action action) {
        switch(action) {
            case SETUP:navigate("setup");break;
            case SELECT_EVENT:openCatalog();break;
            case CHANGE_MODE:navigate("preferences");break;
            case START:confirmStart();break;
            case OPEN_DAMAI:
                if (MonitorService.instance != null && MonitorService.instance.isWaitingForDamai())
                    message("切回刚才的大麦票档页", "请从屏幕底部上滑并停顿，打开最近任务，点大麦回到刚才的票档页。助手会在那里继续检查。");
                else openDamai();
                break;
            case PAUSE:pause("已由你手动暂停。");render();break;
            case REVIEW_ORDER:new AlertDialog.Builder(this).setTitle("请先在大麦核查订单")
                    .setMessage("已有提交记录或无法读取记录，不能据此重新下单。记录不会被清除。")
                    .setNegativeButton("返回", null).setPositiveButton("打开大麦", (d,w) -> openDamai()).show();break;
            case RESOLVE_PAUSE:navigate("recovery");break;
        }
    }

    private void renderRecovery() {
        header("处理这次暂停", "任务已停下，处理好后再由你开始。", () -> navigate("plan"));
        add(body, "上次停止原因", 18, INK, true, 28);
        String reason=store.getMonitorStopDetail();
        add(body, reason.isEmpty()?safeStatus():reason, 15, INK, false, 10);
        setupStep(1, "检查大麦当前页面", false, "如需登录或验证，请在大麦完成，再打开目标票档页。");
        body.addView(textAction("打开大麦处理  ↗", this::openDamai), space(8));
        body.addView(textAction("不明白原因？让领航员解释  ↗", this::openNavigator), space(12));
        actionFooter("已处理，重新核对计划", "下一页会再次核对；不会自动恢复。", this::confirmStart);
    }

    private void openCatalog() {
        rememberQuery();
        if(!deviceReady()){navigate("setup");toast("先完成手机准备，就能读取真实演出。");return;}
        pause("正在选择演出，选好后回到计划。");
        Intent intent=new Intent(this,CatalogActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if(!query.trim().isEmpty())intent.putExtra("search_query",query.trim());
        startActivity(intent);
    }

    private void openNavigator() {
        MonitorConfig c=store.loadConfig();
        boolean issue=!deviceReady()||isPaused(safeStatus());
        openAi(issue?"异常分析":"配置建议",issue?"当前步骤":"购票计划",issue
                ?"请看当前步骤和准备状态，告诉我现在要做什么、点哪里，以及完成后会发生什么。先给一个最重要的下一步。"
                :c.eventKeyword.isEmpty()?"我想选一场演出，请一步一步帮我明确想看谁、哪个城市、场次和预算。":"请核对当前购票计划，说明现在已经选好了什么、接下来要去哪里操作。不要把历史库存当成实时库存。");
    }

    private void openAi(String topic,String origin,String question) {
        boolean hasIssue = isPaused(safeStatus());
        AiContextRepository.recordTaskIssue(store.getStatus());pause("正在使用领航员，监控保持暂停。");
        startActivity(new Intent(this,AiAssistantActivity.class).putExtra("topic",topic).putExtra("origin_label",origin).putExtra("question",question).putExtra("has_issue",hasIssue));
    }

    private void openAdvanced(String section) {
        pause("正在打开高级工具，监控保持暂停。");
        startActivity(new Intent(this,AdvancedSettingsActivity.class).putExtra("section",section));
    }

    private boolean validTarget(MonitorConfig c) {
        try{return c.validate().isEmpty()&&c.deadline.isAfter(Instant.now())&&!LocalDate.parse(c.date).isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai")));}
        catch(RuntimeException e){return false;}
    }

    private void confirmStart() {
        if(!active||!hasWindowFocus())return;
        MonitorConfig c=store.loadConfig();
        if(store.isImportedTargetPendingReview()||!validTarget(c)){openCatalog();return;}
        if(!deviceReady()){navigate("setup");return;}
        if(c.mode==MonitorConfig.Mode.LOCK_ORDER){navigate("preferences");toast("自动锁票尚未完成，请选择当前可用方式。");return;}
        MonitorService s=MonitorService.instance;if(s!=null&&s.isRunning()){toast("已在运行");return;}
        navigate("review");
    }

    private void executeStart(StartReview review) {
        if(!active||!hasWindowFocus()||review.revision!=store.getConfigRevision()||store.isImportedTargetPendingReview()
                ||!review.fingerprint.equals(LockAuthorization.configFingerprint(store.loadConfig()))||!deviceReady()||!validTarget(store.loadConfig())){
            toast("准备条件已变化，请重新核对");render();return;
        }
        MonitorService s=MonitorService.instance;
        if(s==null||s.isRunning()||store.loadConfig().mode==MonitorConfig.Mode.LOCK_ORDER)return;
        try {
            if(new OrderAttemptStore(getFilesDir()).read()!=null){message("先核查已有订单","已有提交记录，不能自动再次操作。请在大麦检查。");return;}
        }catch(Exception e){message("先核查订单","提交记录无法读取，任务没有开始。");return;}
        store.appendEvent("START_RECEIVED","activity_button",0);
        try{s.arm();}catch(RuntimeException e){store.setArmed(false);try{s.pause("启动失败，任务已暂停。");}catch(RuntimeException ignored){}store.appendEvent("START_FAILED","arm_runtime_exception",0);}
        route = "plan"; render();
    }

    private static final class StartReview {
        final long revision;final String fingerprint;
        StartReview(MonitorConfig c,long revision){this.revision=revision;this.fingerprint=LockAuthorization.configFingerprint(c);}
    }

    private void pause(String reason) {
        MonitorService s=MonitorService.instance;
        if(s!=null)s.pause(reason);else store.recordMonitorStop(reason,"PAUSED",false,false);
        NotificationManager n=getSystemService(NotificationManager.class);if(n!=null)n.cancel(12);
    }

    private boolean damaiInstalled(){return getPackageManager().getLaunchIntentForPackage("cn.damai")!=null;}
    private boolean inputReady(){return AppStore.INPUT_SHIZUKU.equals(store.getInputMode())?ShizukuInputBridge.get(this).isReady():MonitorService.instance!=null;}
    private boolean deviceReady(){return MonitorService.instance!=null&&notificationsReady()&&inputReady()&&damaiInstalled();}
    private boolean notificationsReady(){
        NotificationManager n=getSystemService(NotificationManager.class);
        if(n==null||!n.areNotificationsEnabled()||(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED))return false;
        NotificationChannel c=n.getNotificationChannel("monitor_alert");return c!=null&&c.getImportance()!=NotificationManager.IMPORTANCE_NONE;
    }

    private void requestNotifications(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFY);return;}
        Intent i=new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName());
        try{startActivity(i);}catch(RuntimeException e){message("允许通知","请在系统设置中允许票务助手的提醒通知。");}
    }

    private void discloseAccessibility(){
        new AlertDialog.Builder(this).setTitle("允许票务助手读取和操作大麦")
                .setMessage("此服务在你主动选演出或启动后，读取真实大麦的可见页面并执行对应选票操作；必要时在手机本地识别文字，不上传截图。遇到验证、锁屏或未知页面会暂停。\n\n在系统无障碍设置找到“票务助手 · 界面读取”，开启服务开关。无需 TalkBack 或快捷方式。")
                .setNegativeButton("取消",null).setPositiveButton("去系统开启",(d,w)->{try{startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}catch(RuntimeException e){toast("请从系统设置打开无障碍服务");}}).show();
    }

    private String inputNextLabel(){String s=ShizukuInputBridge.get(this).status();return s.contains("未运行")||s.contains("尚未连接")?"打开 Shizuku 并启动":s.contains("尚未授权")?"允许票务助手使用":"连接点击服务";}
    private void prepareInput(){
        String label=inputNextLabel();
        if(label.startsWith("打开")){
            Intent i=getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if(i==null)message("需要 Shizuku","请按官方指南安装并启动 Shizuku，然后返回这里。");else startActivity(i);return;
        }
        try{
            if(label.startsWith("允许"))ShizukuInputBridge.get(this).requestPermission(this,SHIZUKU);
            else ShizukuInputBridge.get(this).connect(result->{if(!isFinishing()&&!isDestroyed()){store.setStatus(result.success?"点击服务已连接，尚未开始。":"点击服务连接未完成，请查看准备步骤。");render();}});
        }catch(RuntimeException e){message("点击服务未准备好","请先在 Shizuku 启动服务，并允许票务助手使用，再回来连接。");}
    }

    private void openDamai(){
        Intent launch=getPackageManager().getLaunchIntentForPackage("cn.damai");
        if(launch==null){message("没有找到大麦","请先安装官方大麦并登录。");return;}
        try{startActivity(launch);}catch(RuntimeException e){toast("请在手机上手动打开大麦");}
    }

    private String safeStatus(){String s=store.getStatus();if(s==null||s.isBlank())return "尚未开始。";return s.length()>240?s.substring(0,240):s;}
    private boolean isPaused(String s){
        MonitorStopState stopped=store.getMonitorStopState();
        if(stopped!=MonitorStopState.NONE)return stopped==MonitorStopState.NEEDS_ATTENTION||stopped==MonitorStopState.REVIEW_DAMAI||stopped==MonitorStopState.REVIEW_ORDER;
        return s.contains("锁屏")||s.contains("熄屏")||s.contains("切换应用")||s.contains("不在大麦")||s.contains("断开")||s.contains("失败")||s.contains("超时")||s.contains("未知")||s.contains("无法")||s.contains("验证码");
    }
    private String mode(MonitorConfig.Mode m){return m==MonitorConfig.Mode.ALERT?"有票时提醒我":m==MonitorConfig.Mode.PREPARE?"帮我选票，到确认页停下":"自动锁票 · 尚未开放";}
    private String displayDate(MonitorConfig c){try{return LocalDate.parse(c.date).format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEE",Locale.CHINA));}catch(RuntimeException e){return "日期待选择";}}
    private String priceText(MonitorConfig c){return c.prices.isEmpty()?"票价待选择":"¥"+String.join(" / ¥",c.prices.stream().map(String::valueOf).collect(java.util.stream.Collectors.toList()));}

    private void header(String title,String description,Runnable back){body.addView(textAction("‹ 返回",back));add(body,title,30,INK,true,20);add(body,description,14,MUTED,false,8);}
    private void section(String title,Runnable action,String label){LinearLayout line=row();line.addView(text(title,18,INK,true),weighted());if(action!=null)line.addView(textAction(label,action));body.addView(line,space(28));}
    private void summaryRow(String label,String value){LinearLayout r=row();r.addView(text(label,14,MUTED,false),weighted());TextView t=text(value,14,INK,true);t.setGravity(Gravity.END);r.addView(t);body.addView(r,space(17));}
    private void menuRow(String title,String description,Runnable action){LinearLayout item=column();item.setPadding(0,dp(19),0,dp(19));LinearLayout top=row();top.addView(text(title,17,INK,true),weighted());top.addView(text("›",20,MUTED,false));item.addView(top);add(item,description,13,MUTED,false,5);item.setClickable(true);item.setFocusable(true);item.setContentDescription(title+"，"+description);item.setOnClickListener(v->action.run());body.addView(item);View line=new View(this);line.setBackgroundColor(LINE);body.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));}
    private void choice(String title,String detail,boolean selected,boolean enabled,Runnable action){LinearLayout c=column();c.setPadding(dp(18),dp(16),dp(18),dp(16));c.setBackground(shape(selected?TINT:Color.WHITE,selected?BLUE:LINE,14));add(c,(selected?"●  ":"○  ")+title,16,enabled?INK:MUTED,true,0);add(c,detail,13,MUTED,false,6);c.setAlpha(enabled?1f:.58f);c.setEnabled(enabled);c.setFocusable(enabled);c.setClickable(enabled);c.setContentDescription(title+(selected?"，已选择":"")+(enabled?"":"，不可用"));c.setOnClickListener(v->{if(enabled)action.run();});body.addView(c,space(10));}
    private void actionFooter(String label,String outcome,Runnable action){footer.removeAllViews();if(!outcome.isEmpty())add(footer,outcome,12,MUTED,false,0);Button b=button(label,true,v->action.run());footer.addView(b,space(outcome.isEmpty()?0:8));}
    private void navItem(String label,String tab,Runnable action){boolean selected=route.equals(tab)||"account".equals(tab)&&List.of("help").contains(route);Button b=button(label,false,v->action.run());b.setBackgroundColor(Color.WHITE);b.setTextColor(selected?BLUE:MUTED);b.setTextSize(13);b.setContentDescription(label+(selected?"，当前页面":""));navigation.addView(b,weighted());}
    private TextView text(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(dp(3),1);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private void add(LinearLayout parent,String s,int size,int color,boolean bold,int margin){parent.addView(text(s,size,color,bold),space(margin));}
    private TextView tag(String s,int color,int background){TextView v=text(s,11,color,true);v.setPadding(dp(10),dp(6),dp(10),dp(6));v.setBackground(shape(background,background,8));return v;}
    private Button textAction(String title,Runnable action){Button b=button(title,false,v->action.run());b.setBackgroundColor(Color.TRANSPARENT);b.setTextColor(BLUE);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setPadding(0,dp(5),0,dp(5));return b;}
    private Button button(String title,boolean primary,View.OnClickListener click){Button b=new Button(this);b.setText(title);b.setTextSize(15);b.setAllCaps(false);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setTextColor(primary?Color.WHITE:INK);b.setBackground(shape(primary?BLUE:Color.WHITE,primary?BLUE:LINE,13));b.setMinHeight(dp(50));b.setPadding(dp(13),dp(12),dp(13),dp(12));b.setStateListAnimator(null);b.setOnClickListener(click);return b;}
    private EditText number(String value,String hint){EditText e=new EditText(this);e.setText(value);e.setHint(hint);e.setContentDescription(hint);e.setSingleLine();e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);e.setTextColor(INK);e.setTextSize(17);e.setPadding(dp(14),dp(12),dp(14),dp(12));e.setBackground(shape(Color.WHITE,LINE,10));return e;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private GradientDrawable shape(int color,int border,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));d.setStroke(dp(1),border);return d;}
    private LinearLayout.LayoutParams space(int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);return p;}
    private LinearLayout.LayoutParams weighted(){return new LinearLayout.LayoutParams(0,-2,1);}
    private int dp(int n){return Math.round(getResources().getDisplayMetrics().density*n);}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_SHORT).show();}
    private void message(String title,String value){new AlertDialog.Builder(this).setTitle(title).setMessage(value).setPositiveButton("知道了",null).show();}

    private void exportHistory() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, "ticketpilot-history.jsonl");
        try { startActivityForResult(i, EXPORT); } catch (RuntimeException e) { toast("系统文件保存暂不可用"); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        try (java.io.OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
            if (out == null) throw new java.io.IOException(); store.exportHistory(out); toast("记录已导出");
        } catch (Exception e) { message("未能导出", "请换一个保存位置后重试。"); }
    }

    @Override protected void onResume(){super.onResume();active=true;handler.removeCallbacks(refresh);handler.post(refresh);}
    @Override protected void onPause(){active=false;handler.removeCallbacks(refresh);super.onPause();}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);render();}
    @Override public void onBackPressed(){if(!"plan".equals(route))navigate("plan");else super.onBackPressed();}
    @Override protected void onSaveInstanceState(Bundle state){rememberQuery();state.putString("route",route);state.putString("query",query);super.onSaveInstanceState(state);}
}
