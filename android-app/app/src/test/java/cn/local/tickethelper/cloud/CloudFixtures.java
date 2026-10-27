package cn.local.tickethelper.cloud;

import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

final class CloudFixtures {
    static final String TAG = "v0.2.4-preview", NAME = "ticketpilot-v0.2.4-preview.apk", SIGNER = "a".repeat(64);
    static final String URL = ReleaseUrls.REPOSITORY + "/releases/download/" + TAG + "/" + NAME;
    static final byte[] APK = new byte[]{80, 75, 3, 4, 1, 2, 3, 4};
    static JSONObject manifest() throws Exception {
        return new JSONObject().put("schema", 1).put("versionCode", 20).put("versionName", "0.2.4-preview")
                .put("sourceCommit", "b".repeat(40)).put("notes", "更新说明\n保留本机配置")
                .put("apk", new JSONObject().put("url", URL).put("size", APK.length)
                        .put("sha256", GitHubUpdates.hex(MessageDigest.getInstance("SHA-256").digest(APK)))
                        .put("certificateSha256", SIGNER));
    }
    static CloudRelease release() throws Exception { return CloudRelease.parse(manifest().toString(), TAG); }
    static JSONObject entry(String tag, boolean preview, String published) throws Exception {
        String prefix = ReleaseUrls.REPOSITORY + "/releases/download/" + tag + "/";
        return new JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", preview).put("published_at", published)
                .put("assets", new JSONArray().put(new JSONObject().put("name", ReleaseUrls.MANIFEST_NAME)
                        .put("browser_download_url", prefix + ReleaseUrls.MANIFEST_NAME))
                        .put(new JSONObject().put("name", NAME).put("browser_download_url", prefix + NAME)));
    }
    static String index() throws Exception { return new JSONArray().put(entry(TAG, true, "2026-10-27T01:00:00Z")).toString(); }
    static String manifestUrl() { return ReleaseUrls.REPOSITORY + "/releases/download/" + TAG + "/" + ReleaseUrls.MANIFEST_NAME; }
}
