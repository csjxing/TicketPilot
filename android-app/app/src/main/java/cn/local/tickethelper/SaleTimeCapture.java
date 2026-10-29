package cn.local.tickethelper;

import android.app.KeyguardManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;
import cn.local.tickethelper.core.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Explicit bounded local read. No page text, identities or automatic actions are retained. */
final class SaleTimeCapture {
    static final class Result {
        final long id, revision, completedAt;
        final Instant value;
        final boolean waiting;
        final String status;
        Result(long id,long revision,long completedAt,Instant value,boolean waiting,String status) {
            this.id=id;this.revision=revision;this.completedAt=completedAt;
            this.value=value;this.waiting=waiting;this.status=status;
        }
        boolean usable(long currentRevision,long elapsed,long now) {
            return value!=null && revision==currentRevision && elapsed>=completedAt
                    && elapsed-completedAt<=300000 && value.toEpochMilli()>now;
        }
    }
    private static final Handler HANDLER=new Handler(Looper.getMainLooper());
    private static long sequence;
    private static Result result;
    private static Runnable pending,timeout;
    private static OcrReader reader;
    private SaleTimeCapture() { }
    static Result get(long id) { return result!=null && result.id==id?result:null; }
    private static void cleanup() {
        if(pending!=null)HANDLER.removeCallbacks(pending);
        if(timeout!=null)HANDLER.removeCallbacks(timeout);
        if(reader!=null)reader.close();
        pending=null;timeout=null;reader=null;
    }
    static void cancel(long id) {
        if(get(id)==null)return;
        result=null;cleanup();
    }
    private static void finish(long id,Instant value,String status) {
        Result current=get(id);if(current==null || !current.waiting)return;
        result=new Result(id,current.revision,SystemClock.elapsedRealtime(),value,false,status);cleanup();
    }
    static long start(MonitorService service,MonitorConfig config,long revision) {
        if(result!=null)cancel(result.id);
        service.pause("正在从大麦读取开售时间，监控保持暂停。");
        long id=++sequence,deadline=SystemClock.elapsedRealtime()+120000;
        result=new Result(id,revision,0,null,true,"打开当前计划对应的大麦场次页，读到时间后会提醒你；最多等待2分钟。");
        AppStore store=new AppStore(service);
        KeyguardManager keyguard=service.getSystemService(KeyguardManager.class);
        timeout=()->finish(id,null,"没有读到目标场次明确的开售时间。打开大麦对应页面后可重试。");
        HANDLER.postDelayed(timeout,120000);
        pending=new Runnable() {
            private boolean allowed() {
                return get(id)!=null && get(id).waiting && SystemClock.elapsedRealtime()<deadline
                        && store.getConfigRevision()==revision && service.isConnectionReady() && !service.isRunning()
                        && keyguard!=null && !keyguard.isKeyguardLocked();
            }
            private boolean damai() {
                AccessibilityNodeInfo root=service.getRootInActiveWindow();
                return root!=null && "cn.damai".contentEquals(root.getPackageName()==null?"":root.getPackageName());
            }
            private void retry() {if(get(id)!=null && get(id).waiting)HANDLER.postDelayed(this,500);}
            private void accept(Instant value) {
                finish(id,value,"已读取开售时间，返回助手即可确认。");
                Toast.makeText(service,"已读取开售时间，请回助手确认",Toast.LENGTH_LONG).show();
            }
            @Override public void run() {
                if(get(id)==null || !get(id).waiting)return;
                if(!allowed()) {finish(id,null,"读取已停止，请解锁手机并确认页面读取服务与当前计划后重试。");return;}
                try {
                    if(!damai()){retry();return;}
                    long generation=service.catalogWindowGeneration(),boundary=service.catalogReadBoundaryGeneration();
                    long now=System.currentTimeMillis();
                    List<UiItem> nodes=service.catalogReadNodes();
                    Instant captured=SaleAnnouncement.read(new Observation("cn.damai",nodes,now),config,System.currentTimeMillis());
                    if(generation!=service.catalogWindowGeneration() || boundary!=service.catalogReadBoundaryGeneration()) {retry();return;}
                    if(captured!=null && allowed()) {accept(captured);return;}
                    if(reader==null)reader=new OcrReader(service);
                    reader.read(nodes,now,(items,evidence,error)->{
                        if(get(id)==null || !get(id).waiting)return;
                        try {
                            if(!allowed()){finish(id,null,"读取已停止，请重新打开目标页面后重试。");return;}
                            if(error==null && damai() && generation==service.catalogWindowGeneration()
                                    && boundary==service.catalogReadBoundaryGeneration()) {
                                List<UiItem> merged=new ArrayList<>(nodes);merged.addAll(items);
                                Instant value=SaleAnnouncement.read(new Observation("cn.damai",merged,now),config,System.currentTimeMillis());
                                if(value!=null){accept(value);return;}
                            }
                            if("secure_window".equals(error)){finish(id,null,"此页面禁止截图，未能读取开售时间。");return;}
                        } catch(RuntimeException unavailable) { /* Next bounded read may have a valid tree. */ }
                        retry();
                    });
                } catch(RuntimeException unavailable) {retry();}
            }
        };
        HANDLER.postDelayed(pending,500);
        return id;
    }
}
