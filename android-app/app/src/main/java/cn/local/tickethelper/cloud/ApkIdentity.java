package cn.local.tickethelper.cloud;

/** Deterministic identity gate, separate from Android package parsing for regression coverage. */
public final class ApkIdentity {
    public enum Reason { READY, UNREADABLE, FILE_CHANGED, PACKAGE_MISMATCH, VERSION_MISMATCH, NOT_NEWER, SIGNATURE_MISMATCH }
    private ApkIdentity() { }
    public static Reason check(CloudRelease release, String packageName, long versionCode, String versionName,
                               String archiveSigner, long installedVersionCode, String installedSigner) {
        if (release == null || archiveSigner == null || installedSigner == null
                || !archiveSigner.matches("[a-f0-9]{64}") || !installedSigner.matches("[a-f0-9]{64}")) return Reason.UNREADABLE;
        if (!CloudRelease.PACKAGE_NAME.equals(packageName)) return Reason.PACKAGE_MISMATCH;
        if (versionCode != release.versionCode || !release.versionName.equals(versionName)) return Reason.VERSION_MISMATCH;
        if (installedVersionCode <= 0 || versionCode <= installedVersionCode) return Reason.NOT_NEWER;
        // Deliberately no automatic key rotation or uninstall fallback. Keep the existing app and its journal.
        if (!release.certificateSha256.equals(archiveSigner) || !installedSigner.equals(archiveSigner)) return Reason.SIGNATURE_MISMATCH;
        return Reason.READY;
    }
}
