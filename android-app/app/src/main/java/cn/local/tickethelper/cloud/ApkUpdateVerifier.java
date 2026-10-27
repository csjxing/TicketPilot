package cn.local.tickethelper.cloud;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/** Run immediately before issuing the normal installer intent, on a worker thread. No installation here. */
public final class ApkUpdateVerifier {
    public static final class Result {
        public final boolean allowed;
        public final ApkIdentity.Reason reason;
        public final String message;
        private Result(ApkIdentity.Reason reason) { this.reason = reason; allowed = reason == ApkIdentity.Reason.READY; message = message(reason); }
    }
    private ApkUpdateVerifier() { }
    public static Result verify(Context context, File file, CloudRelease release) {
        try {
            if (context == null || file == null || release == null || !CloudRelease.PACKAGE_NAME.equals(context.getPackageName()))
                return result(ApkIdentity.Reason.UNREADABLE);
            File directory = new File(context.getCacheDir(), "cloud-updates").getCanonicalFile();
            File canonical = file.getCanonicalFile();
            if (!directory.equals(canonical.getParentFile()) || !canonical.isFile() || canonical.length() != release.apkSize)
                return result(ApkIdentity.Reason.FILE_CHANGED);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream stream = new FileInputStream(canonical)) {
                long count = 0;
                byte[] block = new byte[32768];
                for (int read; (read = stream.read(block)) != -1;) {
                    count += read;
                    if (count > release.apkSize || Thread.currentThread().isInterrupted()) return result(ApkIdentity.Reason.FILE_CHANGED);
                    digest.update(block, 0, read);
                }
                if (count != release.apkSize || !GitHubUpdates.hex(digest.digest()).equals(release.sha256)) return result(ApkIdentity.Reason.FILE_CHANGED);
            }
            PackageManager manager = context.getPackageManager();
            PackageInfo archive = manager.getPackageArchiveInfo(canonical.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            PackageInfo installed = manager.getPackageInfo(CloudRelease.PACKAGE_NAME, PackageManager.GET_SIGNING_CERTIFICATES);
            if (archive == null || installed == null) return result(ApkIdentity.Reason.UNREADABLE);
            return result(ApkIdentity.check(release, archive.packageName, archive.getLongVersionCode(), archive.versionName,
                    signer(archive.signingInfo), installed.getLongVersionCode(), signer(installed.signingInfo)));
        } catch (Exception ignored) { return result(ApkIdentity.Reason.UNREADABLE); }
    }
    private static String signer(SigningInfo info) throws Exception {
        if (info == null || info.hasMultipleSigners()) return "";
        Signature[] certificates = info.getApkContentsSigners();
        if (certificates == null || certificates.length != 1) return "";
        return GitHubUpdates.hex(MessageDigest.getInstance("SHA-256").digest(certificates[0].toByteArray()));
    }
    private static Result result(ApkIdentity.Reason reason) { return new Result(reason); }
    private static String message(ApkIdentity.Reason reason) {
        switch (reason) {
            case READY: return "安装包已核对，系统将提示你确认更新。";
            case FILE_CHANGED: return "安装包文件已变化或校验未通过，请重新下载。";
            case PACKAGE_MISMATCH: return "安装包不属于票务助手，已停止更新。";
            case VERSION_MISMATCH: return "安装包版本与发布清单不一致，已停止更新。";
            case NOT_NEWER: return "当前已安装相同或更新版本，无需覆盖。";
            case SIGNATURE_MISMATCH: return "安装包签名与当前应用不一致。请联系开发者提供同签名更新；不要卸载或清除数据。";
            default: return "无法核对安装包，未执行安装。请重新下载或联系开发者。";
        }
    }
}
