package cn.local.tickethelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Nonexported same-UID bridge for already-paired ADB computers. Fixed operations only. */
public final class ComputerLinkReceiver extends BroadcastReceiver {
    private static final java.util.concurrent.ExecutorService WORK=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ticketpilot-computer-link");t.setDaemon(true);return t;});
    @Override public void onReceive(Context c,Intent i){
        if(i==null||!"cn.local.tickethelper.COMPUTER_LINK".equals(i.getAction()))return;
        String request=i.getStringExtra("request"),owner=i.getStringExtra("owner"),op=i.getStringExtra("operation");
        if(!cn.local.tickethelper.computer.ComputerLease.id(request))return;
        PendingResult pending=goAsync();
        WORK.execute(()->{JSONObject result=new JSONObject();try{
            result.put("schema",1).put("request",request);
            ComputerLinkStore link=new ComputerLinkStore(c);
            if("claim".equals(op))link.claim(owner);
            if(!"status".equals(op)&&!link.permits(owner))throw new IllegalArgumentException();
            if("pause".equals(op)||"claim".equals(op)||"release".equals(op)){
                // Service state/input cancellation belongs to its main thread.
                java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{try{
                    MonitorService m=MonitorService.instance;if(m!=null)m.pause("电脑协作中，手机监控暂停。");
                    new AppStore(c).setArmed(false);
                }finally{done.countDown();}});
                if(!done.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException();
            }
            if("release".equals(op))link.end();
            if("diagnostics".equals(op)){
                JSONObject report=CloudDiagnostics.get(c).captureForSharing().toJson();
                // Pairing / device identifiers are unnecessary for the desktop AI report.
                report.remove("installId");result.put("diagnostics",report);
            }else if(!java.util.List.of("status","claim","pause","release").contains(op))throw new IllegalArgumentException();
            AppStore store=new AppStore(c);cn.local.tickethelper.core.MonitorConfig config=store.loadConfig();
            result.put("status","ok").put("remaining_ms",link.remaining()).put("owned",link.permits(owner))
                .put("armed",store.isArmed()).put("mode",config.mode.name())
                .put("app_version",c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName)
                .put("target",new JSONObject().put("event",config.eventKeyword).put("city",config.city)
                    .put("date",config.date).put("time",config.time).put("prices",new org.json.JSONArray(config.prices))
                    .put("quantity",config.quantity).put("max_total",config.maxTotal));
        }catch(Exception e){try{result.put("status","session_unavailable_or_operation_failed");}catch(Exception ignored){}}
        AtomicFile f=new AtomicFile(new File(c.getFilesDir(),"computer-link-result.json"));FileOutputStream out=null;
        try{out=f.startWrite();out.write(result.toString().getBytes(StandardCharsets.UTF_8));f.finishWrite(out);}
        catch(Exception e){if(out!=null)f.failWrite(out);}finally{pending.finish();}
        });
    }
}
