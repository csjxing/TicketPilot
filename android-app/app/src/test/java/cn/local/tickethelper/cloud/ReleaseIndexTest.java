package cn.local.tickethelper.cloud;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ReleaseIndexTest {
    @Test public void newestPublicationIsSelectedEvenWhenIndexIsUnordered() throws Exception {
        String json = new JSONArray().put(CloudFixtures.entry("v-old", false, "2026-10-25T01:00:00Z"))
                .put(CloudFixtures.entry(CloudFixtures.TAG, true, "2026-10-27T01:00:00Z")).toString();
        assertEquals(CloudFixtures.TAG, ReleaseIndex.latest(json, true).tag);
        assertEquals("v-old", ReleaseIndex.latest(json, false).tag);
    }
    @Test public void draftsAndIncompletePublicationsNeverBecomeUpdates() throws Exception {
        JSONObject draft = CloudFixtures.entry("v-draft", true, "2026-10-28T01:00:00Z").put("draft", true);
        JSONObject missing = CloudFixtures.entry("v-no-manifest", false, "2026-10-27T01:00:00Z").put("assets", new JSONArray());
        assertNull(ReleaseIndex.latest(new JSONArray().put(draft).put(missing).toString(), true));
        assertNull(ReleaseIndex.latest("[]", true));
    }
    @Test public void manifestMustOwnAnExactApkAssetInThisRelease() throws Exception {
        ReleaseIndex.Entry entry = ReleaseIndex.latest(CloudFixtures.index(), true);
        assertTrue(entry.contains(CloudFixtures.release()));
        JSONObject changed = CloudFixtures.manifest();
        changed.getJSONObject("apk").put("url", CloudFixtures.URL.replace(CloudFixtures.NAME, "other.apk"));
        assertFalse(entry.contains(CloudRelease.parse(changed.toString(), CloudFixtures.TAG)));
    }
    @Test public void duplicateManifestOrForeignAssetIsNotAPublication() throws Exception {
        JSONObject duplicate = CloudFixtures.entry(CloudFixtures.TAG, true, "2026-10-27T01:00:00Z");
        JSONArray assets = duplicate.getJSONArray("assets"); assets.put(assets.getJSONObject(0));
        assertNull(ReleaseIndex.latest(new JSONArray().put(duplicate).toString(), true));
        JSONObject foreign = CloudFixtures.entry(CloudFixtures.TAG, true, "2026-10-27T01:00:00Z");
        foreign.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.test/cloud-release.json");
        assertNull(ReleaseIndex.latest(new JSONArray().put(foreign).toString(), true));
    }
    @Test public void malformedAndOversizedIndexFailsClosedWithSafeMessage() {
        for (String value : new String[]{"{}", "x".repeat(512 * 1024 + 1), "[null]"}) {
            try { ReleaseIndex.latest(value, true); fail("Malformed index accepted"); }
            catch (IllegalArgumentException expected) { assertEquals("发布列表无效，请稍后重试。", expected.getMessage()); }
        }
    }
}
