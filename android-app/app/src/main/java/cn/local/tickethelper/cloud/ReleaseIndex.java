package cn.local.tickethelper.cloud;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Selects one published release. Manifest/APK associations remain tied to its exact tag and assets. */
public final class ReleaseIndex {
    private ReleaseIndex() { }
    public static final class Entry {
        public final String tag, manifestUrl;
        public final boolean preview;
        public final long publishedEpochSeconds;
        private final Set<String> apkAssets;
        private Entry(String tag, String manifestUrl, boolean preview, long published, Set<String> assets) {
            this.tag = tag; this.manifestUrl = manifestUrl; this.preview = preview;
            publishedEpochSeconds = published; apkAssets = Collections.unmodifiableSet(new HashSet<>(assets));
        }
        public boolean contains(CloudRelease release) {
            return release != null && tag.equals(release.tag) && apkAssets.contains(release.apkUrl);
        }
    }
    public static Entry latest(String json, boolean includePreview) {
        try {
            if (json == null || json.length() > 512 * 1024) throw new IllegalArgumentException();
            JSONArray releases = new JSONArray(json);
            if (releases.length() > 20) throw new IllegalArgumentException();
            Entry best = null;
            for (int i = 0; i < releases.length(); i++) {
                JSONObject item = releases.getJSONObject(i);
                if (!(item.get("draft") instanceof Boolean) || !(item.get("prerelease") instanceof Boolean)) continue;
                if (item.getBoolean("draft") || (!includePreview && item.getBoolean("prerelease"))) continue;
                String tag = item.getString("tag_name");
                if (!ReleaseUrls.validTag(tag)) continue;
                long published;
                try { published = Instant.parse(item.getString("published_at")).getEpochSecond(); }
                catch (Exception ignored) { continue; }
                JSONArray assets = item.getJSONArray("assets");
                if (assets.length() > 100) continue;
                String manifest = "";
                Set<String> apks = new HashSet<>();
                boolean duplicateManifest = false;
                for (int a = 0; a < assets.length(); a++) {
                    JSONObject asset = assets.getJSONObject(a);
                    String name = asset.getString("name"), url = asset.getString("browser_download_url");
                    if (name.equals(ReleaseUrls.MANIFEST_NAME) && ReleaseUrls.releaseAsset(url, tag, name)) {
                        if (!manifest.isEmpty()) duplicateManifest = true;
                        manifest = url;
                    }
                    if (name.endsWith(".apk") && ReleaseUrls.releaseAsset(url, tag, name)) apks.add(url);
                }
                if (manifest.isEmpty() || duplicateManifest || apks.isEmpty()) continue;
                Entry candidate = new Entry(tag, manifest, item.getBoolean("prerelease"), published, apks);
                if (best == null || candidate.publishedEpochSeconds > best.publishedEpochSeconds) best = candidate;
            }
            return best;
        } catch (Exception ignored) { throw new IllegalArgumentException("发布列表无效，请稍后重试。"); }
    }
}
