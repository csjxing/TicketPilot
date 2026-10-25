package cn.local.tickethelper.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure validation shared by the application and its explicitly authorized shell service. */
public final class ShellTapPolicy {
    public static final long MAX_FUTURE_MS = 3000;
    public static final long MAX_EXECUTION_MS = 2000;
    private static final int MAX_DIMENSION = 16384;
    private static final Pattern FOCUS = Pattern.compile("(?m)^\\s*mCurrentFocus=(.*)$");
    private static final Pattern DAMAI = Pattern.compile(
            "Window\\{[^}\\r\\n]*\\su0\\s+cn\\.damai/[^}\\r\\n]+\\}\\s*");

    private ShellTapPolicy() {}

    public static String validate(int x, int y, int width, int height, long now, long deadline) {
        if (now < 0 || deadline <= now || deadline - now > MAX_FUTURE_MS) return "stale_deadline";
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION)
            return "invalid_display";
        if (x < 0 || y < 0 || x >= width || y >= height) return "invalid_coordinates";
        return null;
    }

    /** Missing, ambiguous, null, overlay or other-profile focus always rejects the command. */
    public static boolean damaiFocused(String snapshot) {
        if (snapshot == null || snapshot.contains("mShowingLockscreen=true")) return false;
        Matcher focus = FOCUS.matcher(snapshot);
        if (!focus.find()) return false;
        String value = focus.group(1).trim();
        return !focus.find() && DAMAI.matcher(value).matches();
    }

    /** The only input executable and arguments the service can create. */
    public static String[] tapCommand(int x, int y) {
        if (x < 0 || y < 0 || x >= MAX_DIMENSION || y >= MAX_DIMENSION)
            throw new IllegalArgumentException("invalid_coordinates");
        return new String[]{"/system/bin/input", "-d", "0", "tap", Integer.toString(x), Integer.toString(y)};
    }
}
