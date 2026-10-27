package cn.local.tickethelper.relay;

import cn.local.tickethelper.sync.DiagnosticReport;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

/** Synthetic protocol fixtures only; these tests are not production relay or phone evidence. */
public class RelayTransferTest {
    private static final String KEY = "fst_" + "A".repeat(43), ETAG = "\"" + "b".repeat(32) + "\"";
    private static final RelayEndpoint ENDPOINT = new RelayEndpoint("https://relay.example.com");
    private static DiagnosticReport report() {
        DiagnosticReport.Snapshot snap = new DiagnosticReport.Snapshot(new DiagnosticReport.Version("0.2.6-preview", 22),
                new DiagnosticReport.Version("9.0.37", 90037), 35, DiagnosticReport.A11y.READY, true, true, false,
                DiagnosticReport.Mode.PREPARE, DiagnosticReport.State.UNKNOWN);
        return new DiagnosticReport(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, 1793145600000L, snap,
                List.of(new DiagnosticReport.Event(1793145600000L, DiagnosticReport.Tag.MONITOR, DiagnosticReport.Code.READ_FAILED)));
    }
    private static RelaySecretsStore.Connection connection() { return new RelaySecretsStore.Connection(ENDPOINT, UUID.randomUUID().toString()); }
    private static final class Fake implements RelayClient.Transport {
        String itemId = "item_123", fileId = "file_123", requestId, filename, text;
        int expectedSize, creates, puts, completes, records, partReads;
        boolean partUploaded, ready, loseCreate, losePut, loseComplete, queryOnly, relayOff, badReady;
        boolean loseCompletionReadyUpdate, objectMerged;
        final List<String> apiKeys = new ArrayList<>();
        @Override public RelayClient.Response send(String url, String method, Map<String,String> headers, byte[] bytes, RelayClient.Cancellation cancel) throws IOException {
            cancel.check();
            try {
                if (url.startsWith("https://bucket.oss-cn-hangzhou.aliyuncs.com/")) {
                    assertEquals("PUT", method); assertFalse(headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("Authorization")));
                    assertFalse(headers.values().toString().contains(KEY)); assertEquals(expectedSize, bytes.length);
                    puts++; partUploaded = true;
                    if (losePut) { losePut = false; throw new IOException("synthetic network failure"); }
                    return new RelayClient.Response(200, new byte[0], Map.of("etag", ETAG));
                }
                assertTrue(url.startsWith(ENDPOINT.apiBase + "/")); assertEquals("Bearer " + KEY, headers.get("Authorization"));
                apiKeys.add(method + " " + url);
                JSONObject body = bytes == null ? null : new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                JSONObject result;
                if (url.endsWith("/me")) result = new JSONObject().put("kind", "api").put("token", new JSONObject()
                        .put("permission", queryOnly ? "query" : "manage").put("relayEnabled", !relayOff));
                else if (method.equals("POST") && url.equals(ENDPOINT.apiBase + "/relay")) {
                    creates++; String incoming = body.getString("clientRequestId");
                    if (requestId == null) { requestId = incoming; records++; text = body.getString("text"); }
                    else assertEquals(requestId, incoming);
                    assertEquals(7, body.getInt("retentionDays"));
                    if (loseCreate) { loseCreate = false; throw new IOException("synthetic lost create reply"); }
                    result = new JSONObject().put("id", itemId);
                } else if (method.equals("POST") && url.endsWith("/files")) {
                    filename = body.getString("filename"); expectedSize = body.getInt("size");
                    result = new JSONObject().put("fileId", fileId).put("status", ready ? "ready" : "pending");
                } else if (url.endsWith("/parts") && method.equals("GET")) {
                    partReads++;
                    if (objectMerged && !ready) throw new IOException("synthetic multipart session already consumed");
                    JSONArray uploaded = new JSONArray(); if (partUploaded) uploaded.put(new JSONObject().put("partNumber", 1).put("etag", ETAG));
                    result = new JSONObject().put("status", ready ? "ready" : "uploading").put("partSize", 8388608).put("partCount", 1).put("parts", uploaded);
                } else if (url.endsWith("/parts") && method.equals("POST")) {
                    result = new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("partNumber", 1)
                            .put("url", "https://bucket.oss-cn-hangzhou.aliyuncs.com/test?signature=synthetic")
                            .put("headers", new JSONObject().put("Content-Type", "application/octet-stream"))));
                } else if (url.endsWith("/complete")) {
                    completes++; objectMerged = true; assertEquals(ETAG, body.getJSONArray("parts").getJSONObject(0).getString("etag"));
                    if (loseCompletionReadyUpdate) { loseCompletionReadyUpdate = false; throw new IOException("synthetic DB ready update lost after merge"); }
                    ready = true;
                    if (loseComplete) { loseComplete = false; throw new IOException("synthetic lost completion reply"); }
                    result = item();
                } else if (method.equals("GET") && url.endsWith("/" + itemId)) result = item();
                else throw new AssertionError("Unexpected fake route");
                return new RelayClient.Response(200, new JSONObject().put("data", result).toString().getBytes(StandardCharsets.UTF_8), Map.of());
            } catch (IOException error) { throw error; }
            catch (Exception error) { throw new IOException(error); }
        }
        JSONObject item() throws Exception { return new JSONObject().put("id", itemId).put("text", text).put("files", new JSONArray().put(new JSONObject()
                .put("id", fileId).put("filename", filename).put("contentType", "application/json")
                .put("size", badReady ? expectedSize + 1 : expectedSize).put("status", ready ? "ready" : "pending"))); }
    }
    private static void fails(Throwing action) throws Exception { try { action.run(); fail("Expected safe rejection"); } catch (IOException | IllegalArgumentException expected) { } }
    private interface Throwing { void run() throws Exception; }
    @Test public void normalizesOnlyExplicitWebsiteOrApiRoot() throws Exception {
        assertEquals(ENDPOINT, new RelayEndpoint("https://RELAY.example.com/api/v1/"));
        assertEquals("https://relay.example.com/api/v1/relay/item_1/files/file_1/parts", ENDPOINT.apiUrl("/relay/item_1/files/file_1/parts"));
        for (String bad : List.of("http://relay.example.com", "https://key@relay.example.com", "https://relay.example.com?key=x", "https://relay.example.com/#x",
                "https://relay.example.com:444", "https://relay.example.com/other", "https://127.0.0.1", "https://test.local", "https://relay.example.com./"))
            fails(() -> new RelayEndpoint(bad));
    }
    @Test public void rejectsBearerCookieAndRoutingHeadersOnOss() throws Exception {
        for (String name : List.of("Authorization", "authorization", "Cookie", "Host", "X-Forwarded-Host"))
            fails(() -> RelayClient.uploadHeaders(new JSONObject().put(name, KEY)));
        fails(() -> RelayClient.uploadHeaders(new JSONObject().put("Content-Type", "application/json\r\nAuthorization: secret")));
        assertEquals("application/octet-stream", RelayClient.uploadHeaders(new JSONObject()).get("content-type"));
    }
    @Test public void permissionsComeFromNestedTokenAndRequireBothGrants() throws Exception {
        Fake fake = new Fake(); RelayClient client = new RelayClient(ENDPOINT, KEY, fake);
        client.verifyManage(new RelayClient.Cancellation()); fake.queryOnly = true; fails(() -> client.verifyManage(new RelayClient.Cancellation()));
        fake.queryOnly = false; fake.relayOff = true; fails(() -> client.verifyManage(new RelayClient.Cancellation()));
    }
    @Test public void uploadsTypedJsonWithoutSendingApiBearerToOssAndChecksReady() throws Exception {
        File dir = Files.createTempDirectory("relay-test").toFile(); RelayTransfer transfer = new RelayTransfer(dir);
        Fake fake = new Fake(); RelayTransfer.Job job = transfer.prepare(connection(), report());
        transfer.upload(job, new RelayClient(ENDPOINT, KEY, fake), new RelayClient.Cancellation());
        assertTrue(transfer.read().complete()); assertEquals(1, fake.records); assertEquals(1, fake.puts); assertEquals(1, fake.completes);
        String journal = new String(Files.readAllBytes(new File(dir, "relay-diagnostic-transfer.json").toPath()), StandardCharsets.UTF_8);
        assertFalse(journal.contains(KEY)); assertFalse(journal.contains("signature=")); assertFalse(journal.contains("aliyuncs"));
    }
    @Test public void lostCreateReplyReusesSavedRequestIdentity() throws Exception {
        RelayTransfer transfer = new RelayTransfer(Files.createTempDirectory("relay-test").toFile()); Fake fake = new Fake(); fake.loseCreate = true;
        RelayTransfer.Job job = transfer.prepare(connection(), report()); RelayClient client = new RelayClient(ENDPOINT, KEY, fake);
        fails(() -> transfer.upload(job, client, new RelayClient.Cancellation()));
        transfer.upload(transfer.read(), client, new RelayClient.Cancellation()); assertEquals(1, fake.records); assertEquals(2, fake.creates);
    }
    @Test public void lostPutReplyRecoversUploadedPartBeforeAnyNewPut() throws Exception {
        RelayTransfer transfer = new RelayTransfer(Files.createTempDirectory("relay-test").toFile()); Fake fake = new Fake(); fake.losePut = true;
        RelayTransfer.Job job = transfer.prepare(connection(), report()); RelayClient client = new RelayClient(ENDPOINT, KEY, fake);
        fails(() -> transfer.upload(job, client, new RelayClient.Cancellation()));
        transfer.upload(transfer.read(), client, new RelayClient.Cancellation()); assertEquals(1, fake.puts); assertEquals(1, fake.records);
    }
    @Test public void lostCompletionReplyRecoversReadyWithoutDuplicatingFile() throws Exception {
        RelayTransfer transfer = new RelayTransfer(Files.createTempDirectory("relay-test").toFile()); Fake fake = new Fake(); fake.loseComplete = true;
        RelayTransfer.Job job = transfer.prepare(connection(), report()); RelayClient client = new RelayClient(ENDPOINT, KEY, fake);
        fails(() -> transfer.upload(job, client, new RelayClient.Cancellation()));
        transfer.upload(transfer.read(), client, new RelayClient.Cancellation()); assertEquals(2, fake.completes); assertEquals(1, fake.puts);
    }
    @Test public void mergedObjectWithLostReadyUpdateRecoversWithoutListingConsumedPartsOrPuttingAgain() throws Exception {
        File dir = Files.createTempDirectory("relay-test").toFile(); RelayTransfer transfer = new RelayTransfer(dir);
        Fake fake = new Fake(); fake.loseCompletionReadyUpdate = true;
        RelayTransfer.Job job = transfer.prepare(connection(), report()); RelayClient client = new RelayClient(ENDPOINT, KEY, fake);
        fails(() -> transfer.upload(job, client, new RelayClient.Cancellation()));
        assertTrue(fake.objectMerged); assertFalse(fake.ready); assertEquals(ETAG, transfer.read().etag);
        assertFalse(transfer.read().complete());
        // A new facade simulates process death: only the journal carries the ETag forward.
        RelayTransfer restarted = new RelayTransfer(dir);
        restarted.upload(restarted.read(), client, new RelayClient.Cancellation());
        assertTrue(restarted.read().complete()); assertTrue(fake.ready);
        assertEquals(1, fake.partReads); assertEquals(1, fake.puts); assertEquals(2, fake.completes); assertEquals(1, fake.records);
    }
    @Test public void journalReadsLegacyWithoutEtagButRejectsMalformedOrOrphanedCompletionInputs() throws Exception {
        File dir = Files.createTempDirectory("relay-test").toFile(); RelayTransfer transfer = new RelayTransfer(dir);
        RelayTransfer.Job job = transfer.prepare(connection(), report());
        File journal = new File(dir, "relay-diagnostic-transfer.json");
        JSONObject legacy = new JSONObject(new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8)); legacy.remove("etag"); legacy.put("schema", 1);
        Files.write(journal.toPath(), legacy.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(job.requestId, transfer.read().requestId); assertEquals("", transfer.read().etag);
        JSONObject orphan = new JSONObject(legacy.toString()).put("schema", 2).put("etag", ETAG);
        Files.write(journal.toPath(), orphan.toString().getBytes(StandardCharsets.UTF_8)); fails(transfer::read);
        orphan.put("itemId", "item_123").put("fileId", "file_123").put("etag", "not-an-etag");
        Files.write(journal.toPath(), orphan.toString().getBytes(StandardCharsets.UTF_8)); fails(transfer::read);
        orphan.put("etag", ETAG).put("schema", "2"); Files.write(journal.toPath(), orphan.toString().getBytes(StandardCharsets.UTF_8)); fails(transfer::read);
    }
    @Test public void pendingResultCannotBeReportedAsSuccess() throws Exception {
        RelayTransfer transfer = new RelayTransfer(Files.createTempDirectory("relay-test").toFile()); Fake fake = new Fake(); fake.badReady = true;
        RelayTransfer.Job job = transfer.prepare(connection(), report());
        fails(() -> transfer.upload(job, new RelayClient(ENDPOINT, KEY, fake), new RelayClient.Cancellation())); assertFalse(transfer.read().complete());
    }
    @Test public void cancelledRequestStopsBeforeSendingAndKeepsLocalCopy() throws Exception {
        RelayTransfer transfer = new RelayTransfer(Files.createTempDirectory("relay-test").toFile()); Fake fake = new Fake();
        RelayTransfer.Job job = transfer.prepare(connection(), report()); RelayClient.Cancellation token = new RelayClient.Cancellation(); token.cancel();
        fails(() -> transfer.upload(job, new RelayClient(ENDPOINT, KEY, fake), token)); assertEquals(0, fake.apiKeys.size()); assertNotNull(transfer.read());
    }
    @Test public void unknownDiagnosticFieldsAndTamperedJournalAreRejectedWithoutDeletion() throws Exception {
        JSONObject diagnostic = report().toJson(); diagnostic.put("rawPage", "synthetic private content"); fails(() -> DiagnosticReport.parse(diagnostic));
        File dir = Files.createTempDirectory("relay-test").toFile(); RelayTransfer transfer = new RelayTransfer(dir); transfer.prepare(connection(), report());
        File journal = new File(dir, "relay-diagnostic-transfer.json"); JSONObject changed = new JSONObject(new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8)).put("rawLog", "not allowed");
        Files.write(journal.toPath(), changed.toString().getBytes(StandardCharsets.UTF_8)); fails(transfer::read); assertTrue(journal.isFile());
    }
    @Test public void unfinishedDiagnosticCannotBeOverwrittenByFreshCapture() throws Exception {
        RelayTransfer transfer = new RelayTransfer(Files.createTempDirectory("relay-test").toFile()); RelaySecretsStore.Connection conn = connection();
        RelayTransfer.Job first = transfer.prepare(conn, report()); assertEquals(first.requestId, transfer.prepare(conn, report()).requestId);
    }
    @Test public void completedRetryMakesNoAdditionalNetworkRequest() throws Exception {
        RelayTransfer transfer = new RelayTransfer(Files.createTempDirectory("relay-test").toFile()); Fake fake = new Fake();
        RelayTransfer.Job job = transfer.prepare(connection(), report()); RelayClient client = new RelayClient(ENDPOINT, KEY, fake);
        transfer.upload(job, client, new RelayClient.Cancellation()); int before = fake.apiKeys.size();
        transfer.upload(transfer.read(), client, new RelayClient.Cancellation()); assertEquals(before, fake.apiKeys.size());
    }
    @Test public void changedReportOrBytesCannotUseAnOldPreviewConsent() throws Exception {
        RelayTransfer transfer = new RelayTransfer(Files.createTempDirectory("relay-test").toFile()); RelayTransfer.Job job = transfer.prepare(connection(), report());
        RelayTransfer.verifyPreview(job, job.report.reportId, RelayTransfer.hash(job.bytes()));
        fails(() -> RelayTransfer.verifyPreview(job, UUID.randomUUID().toString(), RelayTransfer.hash(job.bytes())));
        fails(() -> RelayTransfer.verifyPreview(job, job.report.reportId, "0".repeat(64)));
        fails(() -> RelayTransfer.verifyPreview(job, job.report.reportId, "secret"));
    }
}
