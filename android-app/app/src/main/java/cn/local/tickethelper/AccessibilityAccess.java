package cn.local.tickethelper;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import cn.local.tickethelper.core.AccessibilityReadiness;

/** Distinguish the user's saved service grant from a fully connected service instance. */
final class AccessibilityAccess {
    private AccessibilityAccess() { }

    static AccessibilityReadiness.State state(Context context) {
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        if (manager == null) return AccessibilityReadiness.State.UNKNOWN;
        ComponentName expected = new ComponentName(context, MonitorService.class);
        Boolean configured = null;
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            configured = false;
            if (enabled != null) for (String entry : enabled.split(":"))
                if (expected.equals(ComponentName.unflattenFromString(entry))) { configured = true; break; }
        } catch (RuntimeException unavailable) { /* Retain unknown if both platform sources fail. */ }
        try {
            for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                ResolveInfo resolved = info.getResolveInfo();
                if (resolved != null && resolved.serviceInfo != null && expected.equals(new ComponentName(
                        resolved.serviceInfo.packageName, resolved.serviceInfo.name))) { configured = true; break; }
            }
        } catch (RuntimeException ignored) { /* Unknown is not the same as denied. */ }
        MonitorService service = MonitorService.instance;
        try {
            return AccessibilityReadiness.evaluate(configured, manager.isEnabled(), service != null && service.isConnectionReady());
        } catch (RuntimeException unavailable) { return AccessibilityReadiness.State.UNKNOWN; }
    }

    static void openSettings(Context context) {
        // The OEM details action is signature-permission protected. Use the public
        // settings route; the caller names the exact service to enable or reconnect.
        context.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }
}
