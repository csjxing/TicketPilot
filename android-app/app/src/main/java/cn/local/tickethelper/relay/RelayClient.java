package cn.local.tickethelper.relay;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

/** Bounded, explicitly initiated file relay. No remote command or application input API. */
public final class RelayClient {
    public static final int MAX_DIAGNOSTIC_BYTES = 32768;
    private static final int MAX_REPLY_BYTES = 131072;
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ticketpilot-relay-deadline"); thread.setDaemon(true); return thread;
    });
    private final RelayEndpoint endpoint;
    private final String bearer;
    private final Transport transport;
    public static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private HttpURLConnection connection;
        public synchronized void cancel() { cancelled.set(true); if (connection != null) connection.disconnect(); }
        public boolean isCancelled() { return cancelled.get(); }
        private synchronized void attach(HttpURLConnection value) throws IOException {
            if (isCancelled()) { value.disconnect(); throw cancelled(); } connection = value;
        }
        private synchronized void detach(HttpURLConnection value) { if (connection == value) connection = null; }
        void check() throws IOException { if (isCancelled() || Thread.currentThread().isInterrupted()) throw cancelled(); }
    }
    interface Transport { Response send(String url, String method, Map<String,String> headers, byte[] bytes, Cancellation token) throws IOException; }
    static final class Response {
        final int status; final byte[] bytes; final Map<String,String> headers;
        Response(int status, byte[] bytes, Map<String,String> headers) { this.status = status; this.bytes = bytes; this.headers = headers; }
    }
    public RelayClient(RelayEndpoint endpoint, String bearer) { this(endpoint, bearer, RelayClient::http); }
    RelayClient(RelayEndpoint endpoint, String bearer, Transport transport) {
        if (endpoint == null || !RelayEndpoint.validToken(bearer) || transport == null) throw new IllegalArgumentException("请输入网站颁发的完整 Key。");
        this.endpoint = endpoint; this.bearer = bearer; this.transport = transport;
    }
    public void verifyManage(Cancellation token) throws IOException {
        JSONObject data = api("GET", "/me", null, token);
        try {
            JSONObject identity = data.getJSONObject("token");
            if (!Boolean.TRUE.equals(identity.get("relayEnabled")) || !"manage".equals(identity.getString("permission")))
                throw new IOException("此 Key 没有文件中转站的管理权限，请在远见启用中转站并选择管理权限。");
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw invalidReply(); }
    }
    JSONObject create(String text, String title, String requestId, Cancellation token) throws IOException {
        try { return api("POST", "/relay", new JSONObject().put("title", title).put("text", text)
                .put("retentionDays", 7).put("clientRequestId", requestId), token); }
        catch (IOException error) { throw error; } catch (Exception error) { throw invalidReply(); }
    }
    JSONObject detail(String id, Cancellation token) throws IOException { return api("GET", "/relay/" + id(id), null, token); }
    JSONObject reserve(String itemId, String filename, int size, String requestId, Cancellation token) throws IOException {
        try { return api("POST", "/relay/" + id(itemId) + "/files", new JSONObject().put("filename", filename)
                .put("contentType", "application/json").put("size", size).put("clientRequestId", requestId), token); }
        catch (IOException error) { throw error; } catch (Exception error) { throw invalidReply(); }
    }
    JSONObject parts(String itemId, String fileId, Cancellation token) throws IOException {
        return api("GET", path(itemId, fileId) + "/parts", null, token);
    }
    JSONObject sign(String itemId, String fileId, Cancellation token) throws IOException {
        try { return api("POST", path(itemId, fileId) + "/parts", new JSONObject().put("partNumbers", new JSONArray().put(1)), token); }
        catch (IOException error) { throw error; } catch (Exception error) { throw invalidReply(); }
    }
    JSONObject complete(String itemId, String fileId, String etag, Cancellation token) throws IOException {
        try { return api("POST", path(itemId, fileId) + "/complete", new JSONObject().put("parts", new JSONArray()
                .put(new JSONObject().put("partNumber", 1).put("etag", etag(etag)))), token); }
        catch (IOException error) { throw error; } catch (Exception error) { throw invalidReply(); }
    }
    String put(JSONObject signed, byte[] bytes, Cancellation token) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_DIAGNOSTIC_BYTES) throw new IOException("诊断文件超出允许范围，尚未上传。");
        try {
            JSONArray parts = signed.getJSONArray("parts");
            if (parts.length() != 1 || parts.getJSONObject(0).getInt("partNumber") != 1) throw invalidReply();
            JSONObject part = parts.getJSONObject(0);
            String url = RelayEndpoint.signedUploadUrl(part.getString("url"));
            Map<String,String> headers = uploadHeaders(part.getJSONObject("headers"));
            token.check();
            Response response = transport.send(url, "PUT", headers, bytes, token);
            token.check();
            if (response.status < 200 || response.status >= 300) throw new IOException("诊断附件发送未确认，请保留本次记录，稍后点“继续上传”核实。");
            String returned = response.headers.get("etag");
            if (returned == null) returned = response.headers.get("ETag");
            return etag(returned);
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw invalidReply(); }
    }
    static Map<String,String> uploadHeaders(JSONObject data) throws IOException {
        Map<String,String> result = new LinkedHashMap<>();
        if (data == null || data.length() > 2) throw invalidReply();
        Iterator<String> iterator = data.keys();
        while (iterator.hasNext()) {
            String key = iterator.next(), lower = key.toLowerCase(Locale.ROOT);
            // Do not forward arbitrary server-provided credentials, cookies, routing or host headers.
            if (!(lower.equals("content-type") || lower.equals("content-md5"))) throw invalidReply();
            Object raw = data.opt(key);
            if (!(raw instanceof String)) throw invalidReply();
            String value = (String) raw;
            if (value.isEmpty() || value.length() > 256 || value.chars().anyMatch(c -> c < 32 || c > 126) || result.containsKey(lower)) throw invalidReply();
            result.put(lower, value);
        }
        if (!result.containsKey("content-type")) result.put("content-type", "application/octet-stream");
        return result;
    }
    private JSONObject api(String method, String path, JSONObject body, Cancellation token) throws IOException {
        if (token == null) throw new IllegalArgumentException("Cancellation required");
        try {
            byte[] bytes = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes != null && bytes.length > 4096) throw invalidReply();
            Map<String,String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + bearer); headers.put("Accept", "application/json");
            if (bytes != null) headers.put("Content-Type", "application/json; charset=utf-8");
            token.check(); Response response = transport.send(endpoint.apiUrl(path), method, headers, bytes, token); token.check();
            if (response.status == 401) throw new IOException("文件中转站 Key 已失效或被撤销，请重新连接。");
            if (response.status == 403) throw new IOException("文件中转站拒绝此操作，请核对 Key 的中转站管理权限。");
            if (response.status == 409) throw new IOException("本次中转记录与服务器不一致，请先核查中转站；没有另建记录。");
            if (response.status < 200 || response.status >= 300) throw new IOException("中转站未确认本次操作，诊断保留在手机；稍后可继续核实。");
            if (response.bytes == null || response.bytes.length > MAX_REPLY_BYTES) throw invalidReply();
            return new JSONObject(new String(response.bytes, StandardCharsets.UTF_8)).getJSONObject("data");
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw invalidReply(); }
    }
    private static String id(String value) throws IOException { try { return RelayEndpoint.segment(value); } catch (RuntimeException error) { throw invalidReply(); } }
    private static String path(String item, String file) throws IOException { return "/relay/" + id(item) + "/files/" + id(file); }
    static String etag(String value) throws IOException {
        if (value == null || !value.matches("(?:[a-fA-F0-9]{32}|\"[a-fA-F0-9]{32}\")")) throw invalidReply();
        return value;
    }
    private static IOException invalidReply() { return new IOException("中转站返回的数据无法可靠核实，诊断仍保留在手机。"); }
    private static IOException cancelled() { return new IOException("本次中转已取消，待发送诊断保留；可稍后继续核实。"); }
    private static Response http(String url, String method, Map<String,String> headers, byte[] body, Cancellation token) throws IOException {
        HttpsURLConnection connection = null;
        ScheduledFuture<?> deadline = null;
        try {
            connection = (HttpsURLConnection) new URL(url).openConnection(); token.attach(connection);
            final HttpsURLConnection active = connection;
            // Also closes stalled request-body writes, which are not covered by readTimeout.
            deadline = DEADLINES.schedule(active::disconnect, 25, TimeUnit.SECONDS);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setConnectTimeout(10000); connection.setReadTimeout(20000); connection.setRequestMethod(method);
            connection.setRequestProperty("Accept-Encoding", "identity");
            for (Map.Entry<String,String> header : headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
            if (body != null) {
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length); token.check();
                try (OutputStream out = connection.getOutputStream()) { token.check(); out.write(body); }
            }
            token.check(); int status = connection.getResponseCode();
            Map<String,String> responseHeaders = new HashMap<>(); String etag = connection.getHeaderField("ETag");
            if (etag != null) responseHeaders.put("etag", etag);
            if (status < 200 || status >= 300 || method.equals("PUT")) return new Response(status, new byte[0], responseHeaders);
            if (connection.getContentLengthLong() > MAX_REPLY_BYTES) throw invalidReply();
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) { token.check(); if (out.size() + n > MAX_REPLY_BYTES) throw invalidReply(); out.write(buffer, 0, n); }
                return new Response(status, out.toByteArray(), responseHeaders);
            }
        } catch (IOException error) {
            if (token.isCancelled() || Thread.currentThread().isInterrupted()) throw cancelled();
            throw new IOException("网络中断或超时，未确认的中转记录保留；请稍后继续核实。");
        } catch (Exception error) { throw new IOException("无法安全连接文件中转站，请检查 HTTPS 地址与网络。"); }
        finally { if (deadline != null) deadline.cancel(false); if (connection != null) { token.detach(connection); connection.disconnect(); } }
    }
}
