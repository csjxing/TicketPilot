package cn.local.tickethelper.cloud;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

/** Public, token-free GitHub release discovery and verified APK transfer. Call from a worker thread. */
public final class GitHubUpdates {
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> { Thread t = new Thread(r, "ticketpilot-update-http"); t.setDaemon(true); return t; });
    public enum Status { UPDATE_AVAILABLE, UP_TO_DATE, NOT_PUBLISHED }
    public enum Error { CANCELLED, TIMEOUT, NETWORK, RATE_LIMITED, SERVER, INVALID_RELEASE, INTEGRITY, STORAGE, BUSY }
    public static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        public final Error code;
        Failure(Error code, String message) { super(message); this.code = code; }
    }
    public static final class Check {
        public final Status status;
        public final CloudRelease release;
        private Check(Status status, CloudRelease release) { this.status = status; this.release = release; }
    }
    public static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean(), started = new AtomicBoolean();
        private volatile Future<?> future;
        private volatile HttpURLConnection connection;
        public boolean isCancelled() { return cancelled.get(); }
        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            Future<?> work = future; if (work != null) work.cancel(true);
            HttpURLConnection active = connection;
            if (active != null) disconnectAsync(active);
        }
        private void attach(Future<?> value) { future = value; if (isCancelled()) value.cancel(true); }
        private void attach(HttpURLConnection value) { connection = value; if (isCancelled()) disconnectAsync(value); }
    }
    interface Transport { Body open(String url, String tag, boolean api, Cancellation token, long deadline) throws IOException; }
    static final class Body implements AutoCloseable {
        final InputStream stream;
        final long length;
        Body(InputStream stream, long length) { this.stream = stream; this.length = length; }
        @Override public void close() throws IOException { stream.close(); }
    }
    private final Transport transport;
    public GitHubUpdates() { this(GitHubUpdates::openNetwork); }
    GitHubUpdates(Transport transport) { this.transport = transport; }

    public Check check(boolean includePreview, int installedVersionCode, Cancellation cancellation) throws Failure {
        if (installedVersionCode <= 0) throw invalidRelease();
        return bounded(cancellation, 60, (token, deadline) -> {
            String index;
            try (Body body = transport.open(ReleaseUrls.RELEASES_API, "", true, token, deadline)) {
                index = text(body, 512 * 1024, token, deadline);
            }
            ReleaseIndex.Entry entry;
            try { entry = ReleaseIndex.latest(index, includePreview); }
            catch (IllegalArgumentException ignored) { throw invalidRelease(); }
            if (entry == null) return new Check(Status.NOT_PUBLISHED, null);
            CloudRelease release;
            try (Body body = transport.open(entry.manifestUrl, entry.tag, false, token, deadline)) {
                try { release = CloudRelease.parse(text(body, 32768, token, deadline), entry.tag); }
                catch (IllegalArgumentException ignored) { throw invalidRelease(); }
            }
            if (!entry.contains(release)) throw invalidRelease();
            return new Check(release.versionCode > installedVersionCode ? Status.UPDATE_AVAILABLE : Status.UP_TO_DATE, release);
        });
    }

    /** Only returns a private-cache APK after both its exact byte count and SHA-256 have matched. */
    public File download(CloudRelease release, File cacheDirectory, Cancellation cancellation) throws Failure {
        if (release == null || cacheDirectory == null) throw invalidRelease();
        return bounded(cancellation, 600, (token, deadline) -> {
            File directory = new File(cacheDirectory, "cloud-updates");
            if ((!directory.isDirectory() && !directory.mkdirs()) || !directory.isDirectory()) throw storage();
            File temporary;
            try { temporary = File.createTempFile("update-", ".part", directory); }
            catch (IOException ignored) { throw storage(); }
            boolean completed = false;
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long count = 0;
                try (Body body = transport.open(release.apkUrl, release.tag, false, token, deadline);
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    if (body.length >= 0 && body.length != release.apkSize) throw integrity();
                    byte[] block = new byte[32768];
                    for (int read; (read = body.stream.read(block)) != -1;) {
                        ensureActive(token, deadline);
                        count += read;
                        if (count > release.apkSize || count > CloudRelease.MAX_APK_BYTES) throw integrity();
                        digest.update(block, 0, read); output.write(block, 0, read);
                    }
                    ensureActive(token, deadline);
                    output.getFD().sync();
                }
                if (count != release.apkSize || !hex(digest.digest()).equals(release.sha256)) throw integrity();
                File apk = new File(directory, "ticketpilot-" + release.versionCode + "-" + release.sha256.substring(0, 12) + ".apk");
                try { Files.move(temporary.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING); }
                catch (IOException ignored) { throw storage(); }
                completed = true;
                return apk;
            } finally {
                if (!completed && temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
            }
        });
    }

    private interface Request<T> { T run(Cancellation token, long deadline) throws Exception; }
    private static <T> T bounded(Cancellation cancellation, int seconds, Request<T> request) throws Failure {
        Cancellation token = cancellation == null ? new Cancellation() : cancellation;
        if (token.isCancelled()) throw cancelled();
        if (!token.started.compareAndSet(false, true)) throw new Failure(Error.BUSY, "请重新开始本次更新操作。");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        Future<T> work;
        try { work = WORKERS.submit(() -> request.run(token, deadline)); token.attach(work); }
        catch (RuntimeException ignored) { throw new Failure(Error.BUSY, "前一次连接尚未结束，请稍后再试。"); }
        try { return work.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (TimeoutException ignored) { token.cancel(); throw timeout(); }
        catch (CancellationException ignored) { throw cancelled(); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); token.cancel(); throw cancelled(); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof Failure) throw (Failure) e.getCause();
            if (e.getCause() instanceof SocketTimeoutException) throw timeout();
            throw new Failure(Error.NETWORK, "无法完成更新连接，请检查网络后重试。");
        }
    }
    private static Body openNetwork(String url, String tag, boolean api, Cancellation token, long deadline) throws IOException {
        if (api ? !ReleaseUrls.RELEASES_API.equals(url) : !initialAsset(url, tag)) throw invalidRelease();
        String current = url;
        for (int redirects = 0; redirects <= 4; redirects++) {
            ensureActive(token, deadline);
            HttpsURLConnection connection = (HttpsURLConnection) URI.create(current).toURL().openConnection();
            token.attach(connection);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
            connection.setRequestProperty("User-Agent", "TicketPilot-Android-Updates/1");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Accept", api ? "application/vnd.github+json" : "application/octet-stream");
            if (api) connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            try {
                int code = connection.getResponseCode();
                ensureActive(token, deadline);
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (api || redirects == 4 || location == null) throw invalidRelease();
                    String next;
                    try { next = URI.create(current).resolve(location).toString(); }
                    catch (IllegalArgumentException ignored) { throw invalidRelease(); }
                    if (!ReleaseUrls.assetRedirect(next, tag)) throw invalidRelease();
                    current = next; connection.disconnect(); continue;
                }
                if (code == 403 || code == 429) throw new Failure(Error.RATE_LIMITED, "更新服务暂时限制请求，请稍后重试。");
                if (code != 200) throw new Failure(Error.SERVER, "发布服务暂不可用，请稍后重试。");
                String encoding = connection.getHeaderField("Content-Encoding");
                if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw invalidRelease();
                long length = connection.getContentLengthLong();
                InputStream stream = connection.getInputStream();
                return new Body(new FilterInputStream(stream) {
                    @Override public void close() throws IOException {
                        try { super.close(); } finally { connection.disconnect(); }
                    }
                }, length);
            } catch (IOException | RuntimeException error) { connection.disconnect(); throw error; }
        }
        throw invalidRelease();
    }
    private static boolean initialAsset(String url, String tag) {
        return ReleaseUrls.releaseAsset(url, tag, ReleaseUrls.MANIFEST_NAME) || ReleaseUrls.apkAsset(url, tag);
    }
    private static String text(Body body, int maximum, Cancellation token, long deadline) throws IOException {
        if (body.length > maximum) throw invalidRelease();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        for (int read; (read = body.stream.read(block)) != -1;) {
            ensureActive(token, deadline);
            if (bytes.size() + read > maximum) throw invalidRelease();
            bytes.write(block, 0, read);
        }
        ensureActive(token, deadline);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (Exception ignored) { throw invalidRelease(); }
    }
    static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) { value.append(Character.forDigit((b >>> 4) & 15, 16)); value.append(Character.forDigit(b & 15, 16)); }
        return value.toString();
    }
    private static void ensureActive(Cancellation token, long deadline) throws Failure {
        if (token.isCancelled() || Thread.currentThread().isInterrupted()) throw cancelled();
        if (System.nanoTime() >= deadline) throw timeout();
    }
    private static void disconnectAsync(HttpURLConnection connection) {
        Thread closer = new Thread(connection::disconnect, "ticketpilot-update-close"); closer.setDaemon(true); closer.start();
    }
    private static Failure invalidRelease() { return new Failure(Error.INVALID_RELEASE, "发布清单无效，未安装任何内容。"); }
    private static Failure integrity() { return new Failure(Error.INTEGRITY, "安装包校验未通过，请重新下载。"); }
    private static Failure storage() { return new Failure(Error.STORAGE, "无法保存安装包，请检查可用空间。"); }
    private static Failure cancelled() { return new Failure(Error.CANCELLED, "本次更新操作已取消。"); }
    private static Failure timeout() { return new Failure(Error.TIMEOUT, "更新连接超时，本次已停止。"); }
}
