package cn.local.tickethelper;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

/** Explicit immutable notification action: arms in place without reopening Damai's launcher. */
public final class StartReceiver extends BroadcastReceiver {
    public static final String ACTION_START = "cn.local.tickethelper.START_FROM_NOTIFICATION";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) return;
        AppStore store = new AppStore(context);
        store.appendEvent("START_RECEIVED", "notification_action", 0);
        MonitorService service = MonitorService.instance;
        if (service == null) {
            store.appendEvent("START_REJECTED", "accessibility_unavailable", 0);
            showStartFailure(context, store,
                    "无障碍未连接，尚未开始监控。请返回票务助手开启服务，再重新准备。");
            return;
        }
        try {
            if (service.isRunning()) {
                store.appendEvent("START_IGNORED", "already_running", 0);
                return;
            }
            service.arm();
        } catch (RuntimeException ignored) {
            String message = "启动监控失败，任务已暂停。请返回票务助手检查连接与配置，再重新准备。";
            store.setArmed(false);
            try { service.pause(message); }
            catch (RuntimeException pauseFailure) { /* Best effort; never retry the failed start. */ }
            store.appendEvent("START_FAILED", "arm_runtime_exception", 0);
            showStartFailure(context, store, message);
        }
    }

    private void showStartFailure(Context context, AppStore store, String message) {
        store.setArmed(false); store.setStatus(message);
        try {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return;
            manager.cancel(12);
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
            if (!manager.areNotificationsEnabled()) return;
            manager.createNotificationChannel(new NotificationChannel("monitor_status", "运行状态", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(context, 12, new Intent(context, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            manager.notify(12, new Notification.Builder(context, "monitor_status")
                    .setSmallIcon(R.drawable.ic_ticket).setContentTitle("票务助手 · 请重新准备")
                    .setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                    .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build());
        } catch (RuntimeException ignored) {
            // The persisted failure remains visible in MainActivity even when system UI is unavailable.
        }
    }
}
