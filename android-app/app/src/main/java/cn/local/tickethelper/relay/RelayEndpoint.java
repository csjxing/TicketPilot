package cn.local.tickethelper.relay;

import java.net.URI;
import java.util.Locale;

/** Explicit website receiver; API bearer credentials are never redirected to a different origin. */
public final class RelayEndpoint {
    public static final String DEFAULT_URL = "https://foresight.weisee.com";
    public final String origin, apiBase;

    public RelayEndpoint(String input) {
        try {
            String value = input == null ? "" : input.trim();
            URI uri = new URI(value);
            String host = uri.getHost(), path = uri.getRawPath();
            if (value.length() > 1024 || !"https".equalsIgnoreCase(uri.getScheme()) || !publicHost(host)
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !(path == null || path.isEmpty() || path.equals("/") || path.equals("/api/v1") || path.equals("/api/v1/")))
                throw new IllegalArgumentException();
            origin = "https://" + host.toLowerCase(Locale.ROOT); apiBase = origin + "/api/v1";
        } catch (Exception error) { throw new IllegalArgumentException("请填写文件中转站的 HTTPS 网站地址，不含账号、参数或其他路径。"); }
    }
    public String relayPageUrl() { return origin + "/relay"; }
    public String apiUrl(String path) {
        if (path == null || !path.matches("/(?:me|relay(?:/[A-Za-z0-9_-]{1,128})*(?:/complete|/parts|/files)?)"))
            throw new IllegalArgumentException("Invalid relay API path");
        return apiBase + path;
    }
    public static boolean validToken(String value) { return value != null && value.matches("fst_[A-Za-z0-9_-]{43}"); }
    static String segment(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid relay identifier");
        return value;
    }
    static boolean publicHost(String host) {
        if (host == null || host.length() > 253 || !host.matches("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")
                || !host.contains(".") || host.contains("..") || host.matches("[0-9.]+")) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        return !lower.endsWith(".local") && !lower.endsWith(".localhost") && !lower.endsWith(".internal")
                && !lower.endsWith(".lan") && !lower.endsWith(".home") && !lower.endsWith(".invalid");
    }
    static String signedUploadUrl(String value) {
        try {
            URI uri = new URI(value);
            if (value.length() > 8192 || !"https".equalsIgnoreCase(uri.getScheme()) || !publicHost(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443))
                throw new IllegalArgumentException();
            return value;
        } catch (Exception error) { throw new IllegalArgumentException("文件服务返回的上传地址不安全，尚未发送文件。"); }
    }
    @Override public boolean equals(Object other) { return other instanceof RelayEndpoint && origin.equals(((RelayEndpoint) other).origin); }
    @Override public int hashCode() { return origin.hashCode(); }
}
