package cn.local.tickethelper.cloud;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CloudReleaseTest {
    @Test public void validManifestBindsExactVersionAssetAndCertificate() throws Exception {
        CloudRelease value = CloudFixtures.release();
        assertEquals(20, value.versionCode); assertEquals("0.2.4-preview", value.versionName);
        assertEquals(CloudFixtures.URL, value.apkUrl); assertEquals(8L, value.apkSize);
        assertEquals(CloudFixtures.SIGNER, value.certificateSha256); assertEquals(CloudFixtures.TAG, value.tag);
    }
    @Test public void integerFieldsCannotUseStringsFractionsOrOverflow() throws Exception {
        for (Object invalid : new Object[]{"20", 20.5, -1, 0, 2147483648L}) {
            JSONObject value = CloudFixtures.manifest().put("versionCode", invalid); rejects(value.toString());
        }
        JSONObject schema = CloudFixtures.manifest().put("schema", 2); rejects(schema.toString());
        for (long size : new long[]{0, -1, CloudRelease.MAX_APK_BYTES + 1, Long.MAX_VALUE}) {
            JSONObject value = CloudFixtures.manifest(); value.getJSONObject("apk").put("size", size); rejects(value.toString());
        }
    }
    @Test public void incompleteOrAmbiguousHashesAndSourceAreRejected() throws Exception {
        for (String key : new String[]{"sha256", "certificateSha256"}) {
            for (String invalid : new String[]{"", "A".repeat(64), "z".repeat(64), "a".repeat(63), "a".repeat(65)}) {
                JSONObject value = CloudFixtures.manifest(); value.getJSONObject("apk").put(key, invalid); rejects(value.toString());
            }
        }
        for (String invalid : new String[]{"main", "abc", "b".repeat(39), "Z".repeat(40)})
            rejects(CloudFixtures.manifest().put("sourceCommit", invalid).toString());
        rejects("{}"); rejects("not json"); rejects(" ".repeat(32769));
    }
    @Test public void manifestCannotPointToAnotherRepoTagHostOrCredential() throws Exception {
        for (String url : new String[]{CloudFixtures.URL.replace("TicketPilot", "Other"), CloudFixtures.URL.replace(CloudFixtures.TAG, "other"),
                CloudFixtures.URL.replace("https:", "http:"), CloudFixtures.URL.replace("github.com", "github.com.evil.test"),
                CloudFixtures.URL + "?secret=not-a-key", CloudFixtures.URL + "#fragment", CloudFixtures.URL.replace("github.com", "user@github.com")}) {
            JSONObject value = CloudFixtures.manifest(); value.getJSONObject("apk").put("url", url); rejects(value.toString());
        }
    }
    @Test public void releaseRoutesRejectTraversalEncodedPathsAndUnsafeNames() {
        for (String tag : new String[]{"../v1", "v/1", "v%2F1", "v1..x", "", "x".repeat(81)}) assertFalse(ReleaseUrls.validTag(tag));
        assertFalse(ReleaseUrls.releaseAsset(CloudFixtures.URL, CloudFixtures.TAG, "../" + CloudFixtures.NAME));
        assertFalse(ReleaseUrls.apkAsset(CloudFixtures.URL.replace(".apk", "%2Eapk"), CloudFixtures.TAG));
        assertFalse(ReleaseUrls.apkAsset(CloudFixtures.URL.replace("https://", "https://github.com\\@"), CloudFixtures.TAG));
        assertFalse(ReleaseUrls.apkAsset(CloudFixtures.URL.replace("github.com", "github.com:8443"), CloudFixtures.TAG));
    }
    @Test public void signedAssetCdnRedirectsRequireHttpsAndKnownHost() {
        assertTrue(ReleaseUrls.assetRedirect("https://release-assets.githubusercontent.com/github-production-release-asset/1/file?signature=fixture", CloudFixtures.TAG));
        assertTrue(ReleaseUrls.assetRedirect("https://objects.githubusercontent.com/github-production-release-asset/file", CloudFixtures.TAG));
        for (String url : new String[]{"https://evil.test/file", "http://release-assets.githubusercontent.com/file", "https://user@objects.githubusercontent.com/file",
                "https://objects.githubusercontent.com:8443/file", "https://objects.githubusercontent.com/file#fragment", "file:///tmp/apk"})
            assertFalse(ReleaseUrls.assetRedirect(url, CloudFixtures.TAG));
    }
    private void rejects(String value) {
        try { CloudRelease.parse(value, CloudFixtures.TAG); fail("Invalid manifest accepted"); }
        catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().contains("not-a-key")); }
    }
}
