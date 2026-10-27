package cn.local.tickethelper.cloud;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class GitHubUpdatesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void publicCheckReturnsAvailableCurrentAndNotPublishedWithoutCredentials() throws Exception {
        GitHubUpdates client = client(CloudFixtures.index(), CloudFixtures.manifest().toString(), CloudFixtures.APK, -1);
        GitHubUpdates.Check update = client.check(true, 19, new GitHubUpdates.Cancellation());
        assertEquals(GitHubUpdates.Status.UPDATE_AVAILABLE, update.status); assertEquals(20, update.release.versionCode);
        assertEquals(GitHubUpdates.Status.UP_TO_DATE, client.check(true, 20, null).status);
        assertEquals(GitHubUpdates.Status.UP_TO_DATE, client.check(true, 21, null).status);
        assertEquals(GitHubUpdates.Status.NOT_PUBLISHED, client.check(false, 19, null).status);
        assertNull(client("[]", "", CloudFixtures.APK, -1).check(true, 19, null).release);
    }
    @Test public void exactBytesArePublishedInPrivateCacheOnlyAfterSuccessfulVerification() throws Exception {
        File cache = temporary.newFolder();
        GitHubUpdates client = client("[]", "", CloudFixtures.APK, -1);
        File apk = client.download(CloudFixtures.release(), cache, null);
        assertEquals(new File(cache, "cloud-updates"), apk.getParentFile());
        assertTrue(apk.getName().endsWith(".apk")); assertArrayEquals(CloudFixtures.APK, Files.readAllBytes(apk.toPath()));
        assertEquals(1, apk.getParentFile().list().length);
    }
    @Test public void corruptOrTruncatedBytesNeverBecomeAnApkAndPartialFilesAreRemoved() throws Exception {
        for (byte[] bytes : new byte[][]{new byte[]{1, 2}, new byte[]{80, 75, 3, 4, 1, 2, 3, 5}, new byte[9]}) {
            File cache = temporary.newFolder();
            try { client("[]", "", bytes, -1).download(CloudFixtures.release(), cache, null); fail("Corrupt APK accepted"); }
            catch (GitHubUpdates.Failure expected) { assertEquals(GitHubUpdates.Error.INTEGRITY, expected.code); }
            assertEquals(0, new File(cache, "cloud-updates").list().length);
        }
    }
    @Test public void contentLengthIsComparedBeforeReadingButUnknownLengthIsCountedNormally() throws Exception {
        try { client("[]", "", CloudFixtures.APK, 500).download(CloudFixtures.release(), temporary.newFolder(), null); fail("Wrong length accepted"); }
        catch (GitHubUpdates.Failure expected) { assertEquals(GitHubUpdates.Error.INTEGRITY, expected.code); }
        File apk = client("[]", "", CloudFixtures.APK, CloudFixtures.APK.length).download(CloudFixtures.release(), temporary.newFolder(), null);
        assertEquals(CloudFixtures.APK.length, apk.length());
    }
    @Test public void manifestCannotInventAnApkOutsideTheApiReleaseAssets() throws Exception {
        JSONObject manifest = CloudFixtures.manifest();
        manifest.getJSONObject("apk").put("url", CloudFixtures.URL.replace(CloudFixtures.NAME, "not-in-release.apk"));
        try { client(CloudFixtures.index(), manifest.toString(), CloudFixtures.APK, -1).check(true, 19, null); fail("Invented asset accepted"); }
        catch (GitHubUpdates.Failure expected) { assertEquals(GitHubUpdates.Error.INVALID_RELEASE, expected.code); }
    }
    @Test public void oversizedOrInvalidUtf8MetadataFailsClosed() throws Exception {
        byte[] index = CloudFixtures.index().getBytes(StandardCharsets.UTF_8);
        for (byte[] bytes : new byte[][]{new byte[32769], new byte[]{(byte) 0xc3, (byte) 0x28}}) {
            GitHubUpdates client = new GitHubUpdates((url, tag, api, token, deadline) -> {
                byte[] body = api ? index : bytes;
                return new GitHubUpdates.Body(new ByteArrayInputStream(body), -1);
            });
            try { client.check(true, 19, null); fail("Invalid UTF-8/oversized manifest accepted"); }
            catch (GitHubUpdates.Failure expected) { assertEquals(GitHubUpdates.Error.INVALID_RELEASE, expected.code); }
        }
    }
    @Test public void cancelledOperationMakesNoNetworkRequestAndTokenCannotBeReused() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        GitHubUpdates client = new GitHubUpdates((url, tag, api, token, deadline) -> {
            requests.incrementAndGet(); return new GitHubUpdates.Body(new ByteArrayInputStream("[]".getBytes(StandardCharsets.UTF_8)), 2);
        });
        GitHubUpdates.Cancellation stopped = new GitHubUpdates.Cancellation(); stopped.cancel();
        try { client.check(true, 19, stopped); fail("Cancelled request ran"); }
        catch (GitHubUpdates.Failure expected) { assertEquals(GitHubUpdates.Error.CANCELLED, expected.code); }
        assertEquals(0, requests.get());
        GitHubUpdates.Cancellation used = new GitHubUpdates.Cancellation(); client.check(true, 19, used);
        try { client.check(true, 19, used); fail("Token reused"); }
        catch (GitHubUpdates.Failure expected) { assertEquals(GitHubUpdates.Error.BUSY, expected.code); }
        assertEquals(1, requests.get());
    }
    @Test public void rawNetworkExceptionContentsAreNeverReturnedToUi() throws Exception {
        GitHubUpdates client = new GitHubUpdates((url, tag, api, token, deadline) -> { throw new IOException("PRIVATE_TOKEN_OR_URL"); });
        try { client.check(true, 19, null); fail("Network error ignored"); }
        catch (GitHubUpdates.Failure expected) {
            assertEquals(GitHubUpdates.Error.NETWORK, expected.code); assertFalse(expected.getMessage().contains("PRIVATE_TOKEN_OR_URL"));
            assertNull(expected.getCause());
        }
    }
    private GitHubUpdates client(String index, String manifest, byte[] apk, long apkLength) {
        return new GitHubUpdates((url, tag, api, token, deadline) -> {
            byte[] data;
            if (ReleaseUrls.RELEASES_API.equals(url)) data = index.getBytes(StandardCharsets.UTF_8);
            else if (CloudFixtures.manifestUrl().equals(url)) data = manifest.getBytes(StandardCharsets.UTF_8);
            else if (CloudFixtures.URL.equals(url)) return new GitHubUpdates.Body(new ByteArrayInputStream(apk), apkLength);
            else throw new IOException("unexpected test route");
            return new GitHubUpdates.Body(new ByteArrayInputStream(data), data.length);
        });
    }
}
