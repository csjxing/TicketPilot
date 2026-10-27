package cn.local.tickethelper.sync;

import java.net.URI;
import java.net.URISyntaxException;

/** A single explicitly paired HTTPS receiver. Credentials never follow redirects. */
public final class SyncEndpoint {
    public final String baseUrl;

    public SyncEndpoint(String value) {
        try {
            String input = value == null ? "" : value.trim();
            URI uri = new URI(input);
            String host = uri.getHost();
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (input.length() > 1024 || !"https".equalsIgnoreCase(uri.getScheme())
                    || host == null || host.isEmpty() || host.endsWith(".")
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !path.matches("(?:/[A-Za-z0-9_-]+)*/?") || path.contains("//"))
                throw new IllegalArgumentException();
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            baseUrl = "https://" + host.toLowerCase(java.util.Locale.ROOT) + path;
        } catch (URISyntaxException | IllegalArgumentException error) {
            throw new IllegalArgumentException("请填写不含账号、参数的 HTTPS 云端地址。");
        }
    }

    public String pairUrl() { return baseUrl + "/v1/pair"; }
    public String diagnosticsUrl() { return baseUrl + "/v1/diagnostics"; }
    @Override public boolean equals(Object value) {
        return value instanceof SyncEndpoint && baseUrl.equals(((SyncEndpoint) value).baseUrl);
    }
    @Override public int hashCode() { return baseUrl.hashCode(); }
}
