package cn.local.tickethelper;

import android.app.NotificationManager;
import android.content.Context;
import cn.local.tickethelper.catalog.CatalogPlanSelection;
import cn.local.tickethelper.core.MonitorConfig;
import java.util.Set;

/** Deliberate save shared by the Activity and the in-place panel. Never starts a task. */
public final class CatalogPlanSaver {
    private static final String DISPLAY_PREFS = "catalog_target_display";
    private CatalogPlanSaver() { }

    public static MonitorConfig save(Context context, CatalogStore.Snapshot snapshot,
                                     Set<String> keys, int quantity) {
        return save(context, snapshot, keys, quantity, snapshot == null ? "" : snapshot.query);
    }

    public static MonitorConfig save(Context context, CatalogStore.Snapshot snapshot,
                                     Set<String> keys, int quantity, String query) {
        AppStore store = new AppStore(context);
        MonitorConfig proposed = CatalogPlanSelection.buildConfig(snapshot, keys, quantity, query,
                store.loadConfig(), System.currentTimeMillis());
        // Check the current revision immediately before changing any saved target.
        String issue = CatalogPlanSelection.problem(snapshot, keys, quantity, query, System.currentTimeMillis());
        if (issue != null) throw new IllegalArgumentException(issue);
        MonitorService monitor = MonitorService.instance;
        if (monitor != null) monitor.pause("目标已更新；请重新准备后手动开始监控。");
        CatalogController.cancel(context);
        store.setArmed(false);
        store.saveConfig(proposed);
        store.saveViewerBinding("", "");
        store.setStatus("计划已保存。下一步核对购票条件与观演人，再由你开始。");
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications != null) notifications.cancel(12);
        context.getSharedPreferences(DISPLAY_PREFS, Context.MODE_PRIVATE).edit()
                .putString("title", snapshot.event.label).putString("target", targetFingerprint(proposed))
                .putLong("readAtMs", snapshot.readAtMs).apply();
        store.markImportedTargetReviewed();
        return proposed;
    }

    private static String targetFingerprint(MonitorConfig config) {
        return config.eventKeyword + "\n" + config.city + "\n" + config.date + "\n" + config.time
                + "\n" + config.prices + "\n" + config.quantity;
    }
}
