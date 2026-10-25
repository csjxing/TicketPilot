package cn.local.tickethelper.ai;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** User-owned provider configuration. It contains no credential and grants no device action. */
public final class AiSettings {
    public static final int MIN_OUTPUT_TOKENS = 128, MAX_OUTPUT_TOKENS = 131072, MAX_DAILY_CALLS = 1_000_000;
    public static final int MIN_INPUT_CHARS = 2000, MAX_INPUT_CHARS = 262144, DEFAULT_INPUT_CHARS = 32768;
    public static final int MIN_TIMEOUT_SECONDS = 15, MAX_TIMEOUT_SECONDS = 600, DEFAULT_TIMEOUT_SECONDS = 120;
    public static final int MAX_QUESTION_CHARS = 64000;
    public final boolean enabled, legacyTokenLimit;
    public final String baseUrl, model;
    public final int maxOutputTokens, dailyCallLimit, maxInputChars, timeoutSeconds;

    public AiSettings(boolean enabled, String baseUrl, String model, int maxOutputTokens,
                      int dailyCallLimit, boolean legacyTokenLimit) {
        this(enabled, baseUrl, model, maxOutputTokens, dailyCallLimit, legacyTokenLimit,
                DEFAULT_INPUT_CHARS, DEFAULT_TIMEOUT_SECONDS);
    }

    public AiSettings(boolean enabled, String baseUrl, String model, int maxOutputTokens,
                      int dailyCallLimit, boolean legacyTokenLimit, int maxInputChars, int timeoutSeconds) {
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.model = model == null ? "" : model.trim();
        this.maxOutputTokens = maxOutputTokens;
        this.dailyCallLimit = dailyCallLimit;
        this.legacyTokenLimit = legacyTokenLimit;
        this.maxInputChars = maxInputChars;
        this.timeoutSeconds = timeoutSeconds;
    }

    public static AiSettings defaults() { return new AiSettings(false, "", "", 8192, 0, false); }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (enabled || !baseUrl.isEmpty()) {
            try { endpoint(); }
            catch (IllegalArgumentException e) { errors.add("请填写有效的 HTTPS 接口地址，不包含账号、查询参数或片段。"); }
        }
        if ((enabled || !model.isEmpty()) && !model.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,199}"))
            errors.add("请填写接口支持的模型名，不能包含空格或控制字符。");
        if (maxOutputTokens != 0 && (maxOutputTokens < MIN_OUTPUT_TOKENS || maxOutputTokens > MAX_OUTPUT_TOKENS))
            errors.add("单次输出可设为 128–131072 token，填 0 使用服务商默认值。");
        if (dailyCallLimit < 0 || dailyCallLimit > MAX_DAILY_CALLS)
            errors.add("每日调用上限可设为 1–1000000 次，填 0 不设本机次数限制。");
        if (maxInputChars < MIN_INPUT_CHARS || maxInputChars > MAX_INPUT_CHARS)
            errors.add("单次输入上限应为 2000–262144 个字符，包含问题、系统说明与公开摘要。");
        if (timeoutSeconds < MIN_TIMEOUT_SECONDS || timeoutSeconds > MAX_TIMEOUT_SECONDS)
            errors.add("等待时间应为 15–600 秒。");
        return List.copyOf(errors);
    }

    /** Exact text shown in the preview must fit; never silently trim an accepted request. */
    public void validateInput(String systemPrompt, String userPrompt) {
        if (systemPrompt == null || systemPrompt.isEmpty() || userPrompt == null || userPrompt.isEmpty())
            throw new IllegalArgumentException("发送内容为空，请填写问题或读取页面后再试。");
        if ((long) systemPrompt.length() + userPrompt.length() > maxInputChars)
            throw new IllegalArgumentException("本次输入超过设置的 " + maxInputChars + " 个字符；可在 AI 设置中提高上限，或减少问题与上下文。内容未发送，也未被截短。");
    }

    /** A frozen preview cannot silently use changed credentials or request policy. */
    public boolean matchesRequestSettings(AiSettings other) {
        if (other == null || enabled != other.enabled || !model.equals(other.model)
                || maxOutputTokens != other.maxOutputTokens || dailyCallLimit != other.dailyCallLimit
                || maxInputChars != other.maxInputChars || timeoutSeconds != other.timeoutSeconds
                || legacyTokenLimit != other.legacyTokenLimit) return false;
        try { return endpoint().equals(other.endpoint()); }
        catch (IllegalArgumentException e) { return false; }
    }

    /** Accepts a provider base URL or its complete chat-completions URL. */
    public String endpoint() {
        try {
            if (baseUrl.isEmpty() || baseUrl.length() > 2048 || baseUrl.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException();
            URI uri = new URI(baseUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.isOpaque())
                throw new IllegalArgumentException();
            String path = uri.getRawPath();
            if (path == null) path = "";
            if (path.contains("\\") || !uri.normalize().getRawPath().equals(path)) throw new IllegalArgumentException();
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (path.isEmpty()) path = "/v1";
            if (!path.endsWith("/chat/completions")) path += "/chat/completions";
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.contains(":" ) && !host.startsWith("[")) host = "[" + host + "]";
            String port = uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort();
            return "https://" + host + port + path;
        } catch (Exception e) {
            // Never include a supplied URL: it could contain a pasted credential.
            throw new IllegalArgumentException("接口地址无效。");
        }
    }

    static boolean validApiKey(String value) {
        return value != null && value.matches("[\\x21-\\x7e]{1,8192}");
    }
}
