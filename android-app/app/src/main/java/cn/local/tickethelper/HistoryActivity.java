package cn.local.tickethelper;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.*;
import org.json.JSONObject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only local audit viewer. Opening this screen never arms or changes the plan. */
public final class HistoryActivity extends Activity {
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private List<JSONObject> events = Collections.emptyList();
    private LinearLayout rows;
    private TextView summary;
    private Button refresh;
    private int filter;
    private boolean closed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density); page.setPadding(pad,pad,pad,pad);
        setContentView(page);
        Button back = new Button(this); back.setText("‹ 返回"); back.setOnClickListener(v -> finish()); page.addView(back);
        TextView title = new TextView(this); title.setText("抢票记录"); title.setTextSize(26); page.addView(title);
        TextView help = new TextView(this);
        help.setText("仅保存在本机，按时间倒序。缺票、有票信号和未读清分开记录；有票信号不等于锁票成功。保留最近约2000条事件，较早记录会轮换。查看记录会离开大麦。\n随机等待是计划冷却时间，不是实际刷新或服务器请求间隔。");
        page.addView(help);
        summary = new TextView(this); summary.setTextSize(14); page.addView(summary);
        Spinner filters = new Spinner(this);
        filters.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"全部记录", "库存结果", "操作与等待", "异常与暂停"}));
        filters.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int position, long id) { filter=position; render(); }
            public void onNothingSelected(android.widget.AdapterView<?> p) { }
        }); page.addView(filters);
        refresh = new Button(this); refresh.setText("刷新记录"); refresh.setOnClickListener(v -> load()); page.addView(refresh);
        ScrollView scroll = new ScrollView(this); rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows); page.addView(scroll, new LinearLayout.LayoutParams(-1,0,1)); load();
    }
    private void load() {
        refresh.setEnabled(false); summary.setText("正在读取本机记录…");
        reader.execute(() -> {
            try {
                List<JSONObject> snapshot = new AppStore(getApplicationContext()).recentHistory(2200);
                runOnUiThread(() -> { if(closed)return; events=snapshot; refresh.setEnabled(true); render(); });
            } catch (Exception e) {
                runOnUiThread(() -> { if(closed)return; refresh.setEnabled(true); summary.setText("记录读取失败，请重试；这不代表无票。"); });
            }
        });
    }
    private boolean matches(String state) {
        if(filter==1)return state.equals("OUT_OF_STOCK")||state.equals("IN_STOCK")||state.equals("UNKNOWN");
        if(filter==2)return state.equals("ACTION")||state.equals("REFRESH_WAIT")||state.startsWith("ORDER_")||state.startsWith("OPENING_");
        if(filter==3)return state.equals("UNKNOWN")||state.equals("BLOCKED")||state.equals("PAUSED")||state.endsWith("FAILED")||state.equals("ORDER_UNKNOWN");
        return true;
    }
    private void render() {
        if(rows==null)return;
        rows.removeAllViews(); int shown=0,matched=0,stock=0,out=0,unknown=0;
        for(JSONObject e:events) {
            String state=e.optString("state");
            if(state.equals("OUT_OF_STOCK"))out++; if(state.equals("IN_STOCK"))stock++; if(state.equals("UNKNOWN"))unknown++;
            if(!matches(state))continue; matched++; if(shown>=300)continue; shown++;
            String time=e.optString("timestamp");
            try { time=Instant.parse(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")); } catch(Exception ignored) { time="时间未知"; }
            String detail=e.optString("detail");
            if(state.equals("REFRESH_WAIT") && detail.matches("sampled_cooldown_ms_[0-9]+"))
                detail="本次随机等待 " + detail.substring("sampled_cooldown_ms_".length()) + " 毫秒（连续前进动作可跳过）";
            else if(state.equals("ACTION")) {
                switch(detail) {
                    case "open_tickets_dispatched": detail="已请求进入选票页"; break;
                    case "select_session_dispatched": detail="已请求选择场次"; break;
                    case "back_dispatched": detail="缺票后返回详情页"; break;
                    default: detail="诊断代码："+detail;
                }
            } else detail="诊断代码："+detail;
            TextView line=new TextView(this);line.setTextSize(14);line.setPadding(0,14,0,14);
            line.setTextColor(state.equals("IN_STOCK")?Color.rgb(0,110,65):Color.DKGRAY);
            line.setText(time+"  ·  "+AppStore.stateLabel(state)+"\n"+detail+"\n记录耗时 "+e.optLong("elapsed_ms")+" ms"); rows.addView(line);
        }
        summary.setText("保留记录中的观察：缺票 "+out+" · 有票信号 "+stock+" · 未读清 "+unknown+"\n显示 "+shown+" / "+matched+" 条（最多展示最近300条匹配记录；不是刷票轮数）");
        if(shown==0){TextView empty=new TextView(this);empty.setText("暂无此类记录。开始监控后，结果会自动保存。");rows.addView(empty);}
    }
    @Override protected void onDestroy(){closed=true;reader.shutdownNow();super.onDestroy();}
}
