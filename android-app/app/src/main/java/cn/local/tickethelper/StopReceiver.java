package cn.local.tickethelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Only this app's immutable notification PendingIntent can reach this non-exported receiver. */
public final class StopReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        context.getSystemService(android.app.NotificationManager.class).cancel(12);
        MonitorService service = MonitorService.instance;
        if (service != null) service.pause("已从通知栏暂停");
    }
}
