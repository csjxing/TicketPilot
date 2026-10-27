package cn.local.tickethelper.cloud;

import org.json.JSONObject;

/** Public release metadata. Nothing in this structure grants monitoring or purchase authority. */
public final class CloudRelease {
    public static final long MAX_APK_BYTES = 200L * 1024 * 1024;
    public static final String PACKAGE_NAME = "cn.local.tickethelper";
    public final int versionCode;
    public final String versionName, tag, apkUrl, sha256, certificateSha256, sourceCommit, notes;
    public final long apkSize;

    private CloudRelease(int code, String name, String tag, String url, long size, String hash,
                         String certificate, String commit, String notes) {
        versionCode = code; versionName = name; this.tag = tag; apkUrl = url; apkSize = size;
        sha256 = hash; certificateSha256 = certificate; sourceCommit = commit; this.notes = notes;
    }

    public static CloudRelease parse(String json, String releaseTag) {
        try {
            if (json == null || json.length() > 32768 || !ReleaseUrls.validTag(releaseTag)) throw invalid();
            JSONObject root = new JSONObject(json), apk = root.getJSONObject("apk");
            long schema = integer(root.get("schema")), code = integer(root.get("versionCode")), size = integer(apk.get("size"));
            String name = root.getString("versionName"), url = apk.getString("url"), hash = apk.getString("sha256"),
                    certificate = apk.getString("certificateSha256"), commit = root.getString("sourceCommit"),
                    notes = root.optString("notes", "");
            if (schema != 1 || code <= 0 || code > Integer.MAX_VALUE || size <= 0 || size > MAX_APK_BYTES
                    || name.isEmpty() || name.length() > 80 || controls(name) || notes.length() > 6000
                    || !hash.matches("[a-f0-9]{64}") || !certificate.matches("[a-f0-9]{64}")
                    || !commit.matches("[a-f0-9]{40}|[a-f0-9]{64}") || !ReleaseUrls.apkAsset(url, releaseTag)) throw invalid();
            return new CloudRelease((int) code, name, releaseTag, url, size, hash, certificate, commit, notes);
        } catch (Exception ignored) { throw invalid(); }
    }
    private static boolean controls(String value) { return value.chars().anyMatch(c -> c < 32 || c == 127); }
    private static long integer(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) throw invalid();
        return ((Number) value).longValue();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("更新清单不完整或格式无效。"); }
}
