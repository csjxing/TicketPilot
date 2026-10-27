package cn.local.tickethelper.sync;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

/** Public package versions plus explicit booleans/enums; never inspects page contents or raw logs. */
public final class DiagnosticCollector {
    private DiagnosticCollector() { }
    public static DiagnosticReport.Snapshot capture(Context context, DiagnosticReport.A11y a11y,
            boolean notifications, boolean shizuku, boolean armed, DiagnosticReport.Mode mode, DiagnosticReport.State state) {
        Context app = context.getApplicationContext();
        DiagnosticReport.Version own = version(app, app.getPackageName());
        if (own == null) throw new IllegalStateException("无法读取本应用版本，诊断没有生成。");
        return new DiagnosticReport.Snapshot(own, version(app, "cn.damai"), Build.VERSION.SDK_INT,
                a11y, notifications, shizuku, armed, mode, state);
    }
    private static DiagnosticReport.Version version(Context context, String packageName) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            long code = info.getLongVersionCode(); if (code < 0 || code > Integer.MAX_VALUE) return null;
            return new DiagnosticReport.Version(info.versionName, (int) code);
        } catch (Exception error) { return null; }
    }
}
