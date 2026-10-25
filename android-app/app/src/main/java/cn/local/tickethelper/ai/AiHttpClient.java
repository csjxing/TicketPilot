package cn.local.tickethelper.ai;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

/** A bounded, text-only manual request. Replies have no path to a gesture or order executor. */
public final class AiHttpClient {
    private static final int MAX_BODY_BYTES = 256 * 1024, MAX_REPLY_CHARS = 16000;
    private static final long TOTAL_TIMEOUT_MS = 40000;
    // A provider/DNS stall cannot grow an unbounded number of workers or hold the caller forever.
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> { Thread thread = new Thread(task, "ticketpilot-ai-http"); thread.setDaemon(true); return thread; });
    private AiHttpClient() { }

    public static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        private Failure(String safeMessage) { super(safeMessage); }
    }

    public static final class Result {
        public final String text;
        public final long promptTokens, completionTokens, elapsedMs;
        private Result(String text, long promptTokens, long completionTokens, long elapsedMs) {
            this.text = text; this.promptTokens = promptTokens; this.completionTokens = completionTokens; this.elapsedMs = elapsedMs;
        }
    }

    /** One instance per request. cancel() never waits on networking on the caller's UI thread. */
    public static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean(), started = new AtomicBoolean();
        private volatile HttpURLConnection connection;
        private volatile Future<?> future;
        public boolean isCancelled() { return cancelled.get(); }
        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            Future<?> pending = future;
            if (pending != null) pending.cancel(true);
            HttpURLConnection active = connection;
            if (active != null) disconnectAsync(active);
        }
        private void attach(Future<?> value) {
            future = value;
            if (isCancelled()) value.cancel(true);
        }
        private void attach(HttpURLConnection value) {
            connection = value;
            if (isCancelled()) disconnectAsync(value);
        }
    }

    public static Result complete(AiSettings settings, String apiKey, String systemPrompt,
                                  String userPrompt, Cancellation cancellation) throws Failure {
        if (settings == null || !settings.enabled || !settings.validate().isEmpty())
            throw new Failure("请先启用并保存有效的模型设置。");
        if (!AiSettings.validApiKey(apiKey)) throw new Failure("请先在设置中填写有效的 API Key。");
        if (systemPrompt == null || systemPrompt.isEmpty() || systemPrompt.length() > 12000
                || userPrompt == null || userPrompt.isEmpty() || userPrompt.length() > 32000)
            throw new Failure("发送内容为空或过长，请减少本次问题与上下文。");
        Cancellation token = cancellation == null ? new Cancellation() : cancellation;
        if (token.isCancelled()) throw new Failure("本次分析已取消。");
        if (!token.started.compareAndSet(false, true)) throw new Failure("请为每次分析重新建立请求。");
        long began = System.nanoTime(), deadline = began + TimeUnit.MILLISECONDS.toNanos(TOTAL_TIMEOUT_MS);
        Future<Result> future;
        try {
            byte[] body = requestBody(settings, systemPrompt, userPrompt);
            future = WORKERS.submit(() -> request(settings.endpoint(), apiKey, body, token, began, deadline));
            token.attach(future);
        } catch (Failure e) { throw e; }
        catch (RuntimeException e) { throw new Failure("上一次网络请求尚未结束，请稍后再试。"); }
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            token.cancel(); throw new Failure("模型响应超时，本次已停止；不会自动重试。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); token.cancel(); throw new Failure("本次分析已取消。");
        } catch (CancellationException e) {
            throw new Failure("本次分析已取消。");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Failure) throw (Failure) e.getCause();
            throw new Failure("无法完成模型请求，请检查网络、接口和模型设置。");
        }
    }

    static byte[] requestBody(AiSettings settings, String systemPrompt, String userPrompt) throws Failure {
        try {
            JSONObject request = new JSONObject().put("model", settings.model).put("stream", false).put("store", false)
                    .put(settings.legacyTokenLimit ? "max_tokens" : "max_completion_tokens", settings.maxOutputTokens)
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", systemPrompt))
                            .put(new JSONObject().put("role", "user").put("content", userPrompt)));
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            if (body.length > MAX_BODY_BYTES) throw new Failure("发送内容过长，请减少本次上下文。");
            return body;
        } catch (Failure e) { throw e; }
        catch (Exception e) { throw new Failure("无法准备本次模型请求。"); }
    }

    private static Result request(String endpoint, String apiKey, byte[] body, Cancellation token,
                                  long began, long deadline) throws Failure {
        HttpsURLConnection connection = null;
        try {
            ensureActive(token, deadline);
            connection = (HttpsURLConnection) new URL(endpoint).openConnection();
            token.attach(connection);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            // Streaming mode plus a fresh connection prevents automatic body replay on redirects/auth.
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Connection", "close");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            ensureActive(token, deadline);
            try (OutputStream out = connection.getOutputStream()) {
                ensureActive(token, deadline); out.write(body);
            }
            ensureActive(token, deadline);
            int status = connection.getResponseCode();
            rejectStatus(status);
            if (connection.getContentLengthLong() > MAX_BODY_BYTES) throw new Failure("模型响应过大，本次已停止。");
            byte[] response;
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                while (true) {
                    ensureActive(token, deadline);
                    connection.setReadTimeout((int) Math.min(30000, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
                    int count = in.read(buffer);
                    if (count == -1) break;
                    if (bytes.size() + count > MAX_BODY_BYTES) throw new Failure("模型响应过大，本次已停止。");
                    bytes.write(buffer, 0, count);
                }
                response = bytes.toByteArray();
            }
            ensureActive(token, deadline);
            Result result = parseResponse(response, apiKey, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began));
            ensureActive(token, deadline);
            return result;
        } catch (Failure e) { throw e; }
        catch (SocketTimeoutException e) { throw new Failure("模型响应超时，本次已停止；不会自动重试。"); }
        catch (Exception e) {
            if (token.isCancelled() || Thread.currentThread().isInterrupted()) throw new Failure("本次分析已取消。");
            throw new Failure("无法连接模型接口，请检查网络、HTTPS 地址和证书。");
        } finally {
            token.connection = null;
            if (connection != null) disconnectAsync(connection);
        }
    }

    static void rejectStatus(int status) throws Failure {
        if (status >= 200 && status < 300) return;
        if (status >= 300 && status < 400) throw new Failure("接口要求重定向，已停止发送密钥；请填写最终 HTTPS 地址。");
        if (status == 401 || status == 403) throw new Failure("接口认证失败，请核对 API Key 和账号权限。");
        if (status == 429) throw new Failure("接口限流或额度不足，本次已停止；不会自动重试。");
        if (status == 400 || status == 404 || status == 422)
            throw new Failure("接口或模型不接受当前请求，请核对地址、模型名和输出上限参数。");
        throw new Failure("模型服务暂时无法完成请求，本次已停止；不会自动重试。");
    }

    static Result parseResponse(byte[] bytes, String apiKey, long elapsedMs) throws Failure {
        try {
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_BODY_BYTES) throw new Failure("模型响应为空或过大。");
            String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JSONObject root = new JSONObject(body);
            JSONArray choices = root.getJSONArray("choices");
            if (choices.length() != 1) throw new Failure("模型没有返回可用的单条建议。");
            JSONObject choice = choices.getJSONObject(0), message = choice.getJSONObject("message");
            Object calls = message.opt("tool_calls");
            boolean hasCalls = calls != null && calls != JSONObject.NULL
                    && (!(calls instanceof JSONArray) || ((JSONArray) calls).length() != 0);
            if (!"assistant".equals(message.optString("role", "assistant")) || hasCalls
                    || (message.has("function_call") && !message.isNull("function_call")) || "tool_calls".equals(choice.optString("finish_reason"))
                    || "function_call".equals(choice.optString("finish_reason")))
                throw new Failure("模型返回了操作指令，本助手只接受文字建议，本次未执行任何操作。");
            Object value = message.opt("content");
            if (!(value instanceof String) || ((String) value).trim().isEmpty())
                throw new Failure("模型没有返回文字建议，请调整问题或输出上限。");
            String text = ((String) value).trim();
            if (apiKey != null && !apiKey.isEmpty()) text = text.replace(apiKey, "[已隐藏密钥]");
            boolean truncated = text.length() > MAX_REPLY_CHARS || "length".equals(choice.optString("finish_reason"));
            if (text.length() > MAX_REPLY_CHARS) text = text.substring(0, MAX_REPLY_CHARS);
            if (truncated) text += "\n\n（本次建议可能未完整，请缩小问题范围后再分析。）";
            JSONObject usage = root.optJSONObject("usage");
            return new Result(text, usageNumber(usage, "prompt_tokens"), usageNumber(usage, "completion_tokens"), elapsedMs);
        } catch (Failure e) { throw e; }
        catch (CharacterCodingException e) { throw new Failure("模型响应编码无效，本次未采用建议。"); }
        catch (Exception e) { throw new Failure("模型响应格式无法识别，请核对接口兼容性。"); }
    }

    private static long usageNumber(JSONObject usage, String field) {
        if (usage == null) return -1;
        Object value = usage.opt(field);
        if (!(value instanceof Integer) && !(value instanceof Long)) return -1;
        long count = ((Number) value).longValue();
        return count < 0 || count > 1_000_000_000L ? -1 : count;
    }

    private static void ensureActive(Cancellation token, long deadline) throws Failure {
        if (token.isCancelled() || Thread.currentThread().isInterrupted()) throw new Failure("本次分析已取消。");
        if (System.nanoTime() >= deadline) throw new Failure("模型响应超时，本次已停止；不会自动重试。");
    }

    private static void disconnectAsync(HttpURLConnection connection) {
        Thread close = new Thread(() -> { try { connection.disconnect(); } catch (RuntimeException ignored) { } }, "ticketpilot-ai-close");
        close.setDaemon(true); close.start();
    }
}
