package cn.local.tickethelper.sync;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

public final class DiagnosticQueueTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static DiagnosticReport enqueue(DiagnosticQueue queue, DiagnosticReport.State state, long now) throws IOException {
        return queue.enqueue(DiagnosticReportTest.snapshot(state), List.of(new DiagnosticReport.Event(now,
                DiagnosticReport.Tag.MONITOR, DiagnosticReport.Code.UNKNOWN)), now);
    }
    @Test public void randomIdentityAndSequenceSurviveRestartAndAcknowledgement() throws Exception {
        File directory = temp.newFolder(); DiagnosticQueue first = new DiagnosticQueue(directory);
        String identity = first.installId(); assertTrue(DiagnosticReport.uuid(identity));
        DiagnosticReport a = enqueue(first, DiagnosticReport.State.IDLE, DiagnosticReportTest.NOW);
        DiagnosticQueue second = new DiagnosticQueue(directory); assertEquals(identity, second.installId());
        assertEquals(a.reportId, second.first().reportId); assertEquals(1, second.size());
        assertTrue(second.acknowledge(a.reportId)); assertEquals(0, first.size());
        DiagnosticReport b = enqueue(first, DiagnosticReport.State.STOPPED, DiagnosticReportTest.NOW + 100);
        assertEquals(2, b.sequence); assertEquals(identity, b.installId);
    }
    @Test public void onlyExactHeadAckRemovesData() throws Exception {
        DiagnosticQueue queue = new DiagnosticQueue(temp.newFolder());
        DiagnosticReport first = enqueue(queue, DiagnosticReport.State.IDLE, DiagnosticReportTest.NOW);
        DiagnosticReport second = enqueue(queue, DiagnosticReport.State.STOPPED, DiagnosticReportTest.NOW + 1);
        assertFalse(queue.acknowledge(second.reportId)); assertFalse(queue.acknowledge("unrelated")); assertEquals(2, queue.size());
        assertTrue(queue.acknowledge(first.reportId)); assertFalse(queue.acknowledge(first.reportId)); assertEquals(second.reportId, queue.first().reportId);
    }
    @Test public void unchangedFactsAreDeduplicatedWithoutGrowingQueueOrSequence() throws Exception {
        DiagnosticQueue queue = new DiagnosticQueue(temp.newFolder());
        DiagnosticReport first = enqueue(queue, DiagnosticReport.State.IDLE, DiagnosticReportTest.NOW);
        DiagnosticReport duplicate = enqueue(queue, DiagnosticReport.State.IDLE, DiagnosticReportTest.NOW + 100);
        assertEquals(first.reportId, duplicate.reportId); assertEquals(1, queue.size());
        assertEquals(2, enqueue(queue, DiagnosticReport.State.READY, DiagnosticReportTest.NOW + 200).sequence);
    }
    @Test public void boundedQueueRetainsNewestAndReportsDroppedCount() throws Exception {
        DiagnosticQueue queue = new DiagnosticQueue(temp.newFolder());
        for (int i = 0; i < 40; i++) enqueue(queue, i % 2 == 0 ? DiagnosticReport.State.IDLE : DiagnosticReport.State.STOPPED,
                DiagnosticReportTest.NOW + i);
        assertEquals(32, queue.size()); assertEquals(8, queue.droppedCount()); assertEquals(9, queue.first().sequence);
    }
    @Test public void explicitClearKeepsIdentityAndMonotonicSequence() throws Exception {
        DiagnosticQueue queue = new DiagnosticQueue(temp.newFolder());
        DiagnosticReport a = enqueue(queue, DiagnosticReport.State.IDLE, DiagnosticReportTest.NOW); queue.clearPending();
        DiagnosticReport b = enqueue(queue, DiagnosticReport.State.IDLE, DiagnosticReportTest.NOW + 100);
        assertEquals(a.installId, b.installId); assertEquals(2, b.sequence);
    }
    @Test public void corruptionNeverResetsIdentityOrSilentlyDiscardsData() throws Exception {
        File directory = temp.newFolder(); DiagnosticQueue queue = new DiagnosticQueue(directory); queue.installId();
        File file = new File(directory, "diagnostic-outbox.json"); byte[] corrupt = "private invalid fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file.toPath(), corrupt);
        try { queue.installId(); fail(); } catch (IOException expected) { assertFalse(expected.getMessage().contains("private invalid fixture")); }
        try { enqueue(queue, DiagnosticReport.State.IDLE, DiagnosticReportTest.NOW); fail(); } catch (IOException expected) { }
        assertArrayEquals(corrupt, Files.readAllBytes(file.toPath()));
    }
}
