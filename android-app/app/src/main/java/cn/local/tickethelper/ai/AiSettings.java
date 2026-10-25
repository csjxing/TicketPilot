package cn.local.tickethelper.ai;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** User-owned provider configuration. It contains no credential and grants no device action. */
public final class AiSettings {
    public static final int MIN_OUTPUT_TOKENS = 128, MAX_OUTPUT_TOKENS = 4096, MAX_DAILY_CALLS = 100;
    public final boolean enabled, legacyTokenLimit;
    public final String baseUrl, model;
    public final int maxOutputTokens, dailyCallLimit;

    public AiSettings(boolean enabled, String baseUrl, String model, int maxOutputTokens,
                      int dailyCallLimit, boolean legacyTokenLimit) {
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.model = model == null ? "" : model.trim();
        this.maxOutputTokens = maxOutputTokens;
        this.dailyCallLimit = dailyCallLimit;
        this.legacyTokenLimit = legacyTokenLimit;
    }

    public static AiSettings defaults() { return new AiSettings(false, "", "", 1024, 20, false); }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (enabled || !baseUrl.isEmpty()) {
            try { endpoint(); }
            catch (IllegalArgumentException e) { errors.add("请填写有效的 HTTPS 接口地址，不包含账号、查询参数或片段。"); }
        }
        if ((enabled || !model.isEmpty()) && !model.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,199}"))
            errors.add("请填写接口支持的模型名，不能包含空格或控制字符。");
        if (maxOutputTokens < MIN_OUTPUT_TOKENS || maxOutputTokens > MAX_OUTPUT_TOKENS)
            errors.add("单次输出上限应为 128–4096 token。");
        if (dailyCallLimit < 1 || dailyCallLimit > MAX_DAILY_CALLS)
            errors.add("每日调用上限应为 1–100 次。");
        return List.copyOf(errors);
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
