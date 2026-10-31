package cn.local.tickethelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import cn.local.tickethelper.checkout.LockAuthorization;
import cn.local.tickethelper.checkout.OrderAttemptStore;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Manual, same-UID live acceptance harness, NOT unattended production capability.
 * Invoke only after fresh explicit user approval of the one fixed sample order and its selected
 * attendees. Requires a fresh successful read-only preview; page content alone never invokes it.
 * The production durable one-input dispatcher executes the action. No journal/config bypass exists.
 */
public final class DebugCheckoutSubmitReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if(intent==null||!"cn.local.tickethelper.DEBUG_CHECKOUT_SUBMIT_ONCE".equals(intent.getAction()))return;
        JSONObject report=new JSONObject();
        try {
            report.put("schema",1);report.put("scope","manual_single_confirmation_acceptance");
            DebugCheckoutProbeReceiver.Preview preview=DebugCheckoutProbeReceiver.takePreview(intent.getStringExtra("preview_id"));
            MonitorService service=MonitorService.instance;
            if(preview==null||service==null||service.isRunning()||new AppStore(context).isArmed()
                    ||new OrderAttemptStore(context.getFilesDir()).hasUnresolved()) {
                report.put("status","not_started_preview_or_barrier");
            } else {
                long now=System.currentTimeMillis();
                LockAuthorization grant=new LockAuthorization(UUID.randomUUID().toString(),preview.config,preview.title,
                        LockAuthorization.viewerBindingsHash(preview.viewers),now,preview.config.deadline.toEpochMilli());
                service.armConfirmationTest(grant,preview.viewers);
                report.put("status",service.isRunning()?"started_result_unverified":"not_started");
            }
        } catch(Exception unavailable) {
            try{report.put("status","not_started_unavailable");}catch(Exception ignored){}
        }
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(context.getFilesDir(),"debug-checkout-submit-receipt.json"))) {
            out.write(report.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();
        }catch(Exception ignored){}
    }
}
