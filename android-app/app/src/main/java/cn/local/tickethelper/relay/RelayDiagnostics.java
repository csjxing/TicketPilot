package cn.local.tickethelper.relay;

import android.content.Context;
import cn.local.tickethelper.sync.DiagnosticReport;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Background-thread facade for manual, previewed diagnostic sharing through the user's relay. */
public final class RelayDiagnostics {
    private static final Object NETWORK = new Object();
    private final RelaySecretsStore secrets;
    private final RelayTransfer transfer;
    public static final class Pending {
        public final String origin, filename, reportId, preview, sha256;
        public final int size;
        public final boolean complete;
        Pending(RelayTransfer.Job job) {
            origin = job.endpoint.origin; filename = job.filename(); reportId = job.report.reportId;
            preview = new String(job.bytes(), StandardCharsets.UTF_8); sha256 = RelayTransfer.hash(job.bytes());
            size = job.bytes().length; complete = job.complete();
        }
    }
    public static final class Receipt {
        public final String itemId, fileId, origin, pageUrl, sha256, completedAt;
        public final int size;
        Receipt(RelayTransfer.Job job) {
            itemId = job.itemId; fileId = job.fileId; origin = job.endpoint.origin; pageUrl = job.endpoint.relayPageUrl();
            sha256 = RelayTransfer.hash(job.bytes()); size = job.bytes().length; completedAt = job.completedAt;
        }
    }
    public RelayDiagnostics(Context context) {
        Context app = context.getApplicationContext(); secrets = new RelaySecretsStore(app);
        transfer = new RelayTransfer(new File(app.getNoBackupFilesDir(), "relay-diagnostics"));
    }
    public RelaySecretsStore.Connection connection() throws IOException { return secrets.load(); }
    public RelaySecretsStore.Connection connect(String websiteUrl, String key, RelayClient.Cancellation token) throws IOException {
        RelayEndpoint endpoint = new RelayEndpoint(websiteUrl);
        String enteredKey = key == null ? "" : key.trim();
        synchronized (NETWORK) {
            new RelayClient(endpoint, enteredKey).verifyManage(token); token.check(); return secrets.save(endpoint, enteredKey);
        }
    }
    public Pending prepare(DiagnosticReport report) throws IOException {
        if (report == null) throw new IllegalArgumentException("Typed diagnostic report required");
        synchronized (NETWORK) {
            RelaySecretsStore.Connection connection = secrets.load();
            if (connection == null) throw new IOException("请先连接文件中转站。");
            RelayTransfer.Job job = transfer.prepare(connection, report);
            if (!job.binding.equals(connection.binding) || !job.endpoint.equals(connection.endpoint))
                throw new IOException("上次诊断属于旧连接，请先在中转站核查，再明确清除本机待发送副本。");
            return new Pending(job);
        }
    }
    public Pending pending() throws IOException { RelayTransfer.Job job = transfer.read(); return job == null ? null : new Pending(job); }
    public Receipt upload(String expectedReportId, String expectedSha256, RelayClient.Cancellation token) throws IOException {
        synchronized (NETWORK) {
            RelayTransfer.Job job = transfer.read(); RelaySecretsStore.Connection connection = secrets.load();
            if (job == null) throw new IOException("请先预览并准备诊断。");
            RelayTransfer.verifyPreview(job, expectedReportId, expectedSha256);
            if (connection == null || !job.binding.equals(connection.binding) || !job.endpoint.equals(connection.endpoint))
                throw new IOException("连接已变化，没有向新接收方发送旧诊断，请先核查待发送记录。");
            RelayClient client = new RelayClient(connection.endpoint, secrets.token(connection));
            return new Receipt(transfer.upload(job, client, token));
        }
    }
    public Receipt lastReceipt() throws IOException { RelayTransfer.Job job = transfer.read(); return job != null && job.complete() ? new Receipt(job) : null; }
    /** Explicit local copy removal only; does not delete cloud files, credentials or order barriers. */
    public void discardPending() throws IOException { synchronized (NETWORK) { transfer.discard(); } }
    public void disconnect() throws IOException { synchronized (NETWORK) { secrets.clear(); } }
}
