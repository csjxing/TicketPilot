package cn.local.tickethelper;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.AtomicFile;
import cn.local.tickethelper.checkout.*;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.UUID;
import java.time.Instant;
import cn.local.tickethelper.core.MonitorConfig;

/** Same-UID, debug-only, read-only native parser probe. No input, config save or order is possible. */
public final class DebugCheckoutProbeReceiver extends BroadcastReceiver {
    static final class Preview {
        final String id = UUID.randomUUID().toString();
        final long at = System.currentTimeMillis();
        final MonitorConfig config;
        final List<ViewerMatcher.RequestedViewer> viewers;
        final String title;
        Preview(CheckoutObservation o, List<ViewerMatcher.RequestedViewer> viewers) {
            title = o.eventTitle; this.viewers = List.copyOf(viewers);
            config = new MonitorConfig(o.eventTitle, o.city, o.date, o.time, List.of(980), 2, 1960,
                    Instant.ofEpochMilli(at + 120_000), 500, 1500, MonitorConfig.Mode.LOCK_ORDER);
        }
    }
    private static Preview preview;
    static Preview takePreview(String id) {
        Preview found = preview; preview = null;
        long now = System.currentTimeMillis();
        return found != null && found.id.equals(id) && now >= found.at && now - found.at < 60_000 ? found : null;
    }
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !"cn.local.tickethelper.DEBUG_CHECKOUT_READ".equals(intent.getAction())) return;
        preview = null;
        JSONObject report = new JSONObject();
        try {
            report.put("schema", 1); report.put("scope", "read_only_checkout_candidate");
            MonitorService service = MonitorService.instance;
            if (service == null || service.isRunning() || new AppStore(context).isArmed()
                    || !context.getSystemService(PowerManager.class).isInteractive()
                    || context.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                report.put("status", "service_or_screen_not_ready");
            } else {
                android.content.pm.PackageInfo installed = context.getPackageManager().getPackageInfo("cn.damai", 0);
                String version = installed.versionName + "#" + installed.getLongVersionCode();
                CheckoutFrame frame = CheckoutFrameReader.capture(service, version, service::catalogWindowGeneration);
                List<ViewerMatcher.RequestedViewer> selectedViewers = new ArrayList<>();
                CheckoutParseResult result = CheckoutPageParser.parse(frame, Damai937Confirmation.profile(),
                        System.currentTimeMillis(), (rows, complete) -> {
                            // Ephemeral selected native names only. Never serialize or save them.
                            List<ViewerMatcher.RequestedViewer> selected = rows.stream().filter(row -> row.checked)
                                    .map(row -> new ViewerMatcher.RequestedViewer(row.visibleName, ""))
                                    .collect(Collectors.toList());
                            selectedViewers.addAll(selected);
                            return ViewerMatcher.matchMany(selected, rows, complete);
                        });
                CheckoutObservation o = result.observation;
                report.put("status", result.status.name()); report.put("reason", result.reason);
                report.put("frame_complete", frame.complete); report.put("node_count", frame.nodes.size());
                report.put("kind", o.kind.name()); report.put("quantity", o.quantity);
                report.put("unit_fen", o.unitPriceFen); report.put("payable_fen", o.payableFen);
                report.put("target_complete", !o.eventTitle.isEmpty() && !o.city.isEmpty() && !o.date.isEmpty() && !o.time.isEmpty());
                report.put("viewers_verified", o.viewerSelectionVerified); report.put("agreements_verified", o.agreementsAccepted);
                report.put("zero_additional_charge", o.noUnapprovedExtras); report.put("submit_verified", o.uniqueNativeSubmitTarget);
                long bindingBegan=android.os.SystemClock.elapsedRealtime();
                boolean rebound=OrderCheckoutAdapter.probeConfirmationSubmitBinding(service,o,
                        service::catalogWindowGeneration,bindingBegan+150);
                report.put("native_submit_rebound",rebound);
                report.put("native_submit_rebind_ms",android.os.SystemClock.elapsedRealtime()-bindingBegan);
                if (result.status == CheckoutParseResult.Status.PARSED && o.viewerSelectionVerified
                        && o.agreementsAccepted && o.uniqueNativeSubmitTarget && o.eventTitle.contains("庾澄庆")
                        && o.city.equals("杭州") && o.date.equals("2026-12-19") && o.time.equals("19:00")
                        && Integer.valueOf(2).equals(o.quantity) && Long.valueOf(98000).equals(o.unitPriceFen)
                        && Long.valueOf(196000).equals(o.payableFen) && selectedViewers.size() == 2) {
                    // A preview is data, not an authorization. Only the separately invoked same-UID
                    // manual acceptance receiver can consume it, once, after the operator's fresh approval.
                    preview = new Preview(o, selectedViewers); report.put("preview_id", preview.id);
                }
            }
        } catch (Exception unavailable) {
            try { report.put("status", "probe_unavailable"); } catch (Exception ignored) { }
        }
        AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "debug-checkout-probe.json"));
        FileOutputStream stream = null;
        try {
            stream = file.startWrite(); stream.write(report.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream);
        } catch (Exception failure) { if (stream != null) file.failWrite(stream); }
    }
}
