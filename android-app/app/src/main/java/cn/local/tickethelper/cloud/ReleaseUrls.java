package cn.local.tickethelper.cloud;

import java.net.URI;
import java.util.Locale;

/** The update channel is fixed to the project; a release cannot supply an arbitrary server. */
public final class ReleaseUrls {
    public static final String REPOSITORY = "https://github.com/csjxing/TicketPilot";
    public static final String RELEASES_API = "https://api.github.com/repos/csjxing/TicketPilot/releases?per_page=20";
    public static final String MANIFEST_NAME = "cloud-release.json";
    private static final String PREFIX = "/csjxing/TicketPilot/releases/download/";
    private ReleaseUrls() { }

    public static boolean validTag(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}") && !value.contains("..");
    }

    public static boolean releaseAsset(String value, String tag, String name) {
        if (!validTag(tag) || name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}") || name.contains("..")) return false;
        URI uri = parse(value);
        return secure(uri) && "github.com".equalsIgnoreCase(uri.getHost()) && uri.getRawQuery() == null
                && uri.getRawPath().equals(PREFIX + tag + "/" + name);
    }

    public static boolean apkAsset(String value, String tag) {
        URI uri = parse(value);
        if (uri == null || uri.getRawPath() == null) return false;
        String name = uri.getRawPath().substring(uri.getRawPath().lastIndexOf('/') + 1);
        return name.endsWith(".apk") && releaseAsset(value, tag, name);
    }

    /** Only used after starting from a validated release asset URL, never for a caller supplied URL. */
    static boolean assetRedirect(String value, String tag) {
        URI uri = parse(value);
        if (!secure(uri)) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("release-assets.githubusercontent.com") || host.equals("objects.githubusercontent.com"))
            return uri.getRawPath() != null && !uri.getRawPath().isEmpty();
        if (!host.equals("github.com") || uri.getRawQuery() != null || uri.getRawPath() == null) return false;
        String name = uri.getRawPath().substring(uri.getRawPath().lastIndexOf('/') + 1);
        return releaseAsset(value, tag, name);
    }

    private static URI parse(String value) {
        if (value == null || value.length() > 8192 || value.indexOf('\\') >= 0 || value.chars().anyMatch(c -> c <= 32 || c == 127)) return null;
        try { return URI.create(value); } catch (IllegalArgumentException ignored) { return null; }
    }
    private static boolean secure(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }
}
