package cn.local.tickethelper.sync;

import org.json.JSONObject;
import org.junit.Test;
import java.util.UUID;
import static org.junit.Assert.*;

public final class DiagnosticSyncClientTest {
    private JSONObject ack(String id) throws Exception {
        return new JSONObject().put("schema", 1).put("accepted", true).put("duplicate", false).put("cursor", 12).put("reportId", id);
    }
    @Test public void matchingPositiveAckAndDuplicateAckAreAccepted() throws Exception {
        String id = UUID.randomUUID().toString(); JSONObject json = ack(id);
        DiagnosticSyncClient.UploadResult first = DiagnosticSyncClient.parseAcknowledgement(json, id);
        assertTrue(first.uploaded); assertFalse(first.duplicate); assertEquals(12, first.cursor);
        json.put("duplicate", true); assertTrue(DiagnosticSyncClient.parseAcknowledgement(json, id).duplicate);
    }
    @Test public void wrongMissingNegativeOrActionRepliesCannotAcknowledgeQueuedData() throws Exception {
        String id = UUID.randomUUID().toString();
        JSONObject[] values = {ack(UUID.randomUUID().toString()), ack(id).put("accepted", false), ack(id).put("cursor", 0),
                ack(id).put("commands", "tap"), ack(id).put("cursor", "12"), ack(id).put("accepted", "true")};
        for (JSONObject json : values) {
            try { DiagnosticSyncClient.parseAcknowledgement(json, id); fail(); }
            catch (DiagnosticSyncClient.Failure expected) { assertFalse(expected.getMessage().contains("tap")); }
        }
    }
    @Test public void redirectsAndServerErrorsNeverCountAsSuccessfulUpload() throws Exception {
        for (int status : new int[]{301, 302, 307, 308, 400, 401, 403, 409, 413, 422, 429, 500, 503}) {
            try { DiagnosticSyncClient.rejectStatus(status); fail(String.valueOf(status)); }
            catch (DiagnosticSyncClient.Failure expected) { assertNotNull(expected.getMessage()); }
        }
        DiagnosticSyncClient.rejectStatus(200); DiagnosticSyncClient.rejectStatus(201);
    }
    @Test public void permanentProtocolAndAuthorizationFailuresRequireUserAction() throws Exception {
        for (int status : new int[]{301, 302, 307, 308, 400, 401, 403, 404, 405, 409, 413, 422}) {
            try { DiagnosticSyncClient.rejectStatus(status); fail(String.valueOf(status)); }
            catch (DiagnosticSyncClient.Failure expected) { assertFalse(String.valueOf(status), expected.retryable); }
        }
        for (int status : new int[]{408, 429, 500, 502, 503, 504}) {
            try { DiagnosticSyncClient.rejectStatus(status); fail(String.valueOf(status)); }
            catch (DiagnosticSyncClient.Failure expected) { assertTrue(String.valueOf(status), expected.retryable); }
        }
    }
    @Test public void cancellationCanBeCalledRepeatedlyBeforeRequest() {
        DiagnosticSyncClient.Cancellation token = new DiagnosticSyncClient.Cancellation();
        assertFalse(token.isCancelled()); token.cancel(); token.cancel(); assertTrue(token.isCancelled());
    }
}
