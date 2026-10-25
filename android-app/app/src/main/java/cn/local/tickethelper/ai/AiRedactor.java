package cn.local.tickethelper.ai;

import java.util.List;
import java.util.regex.Pattern;

/** Best-effort second layer. Producers must still use an explicit public-field allowlist. */
public final class AiRedactor {
    private static final Pattern SECRET = Pattern.compile("(?i)(?:bearer\\s+[a-z0-9._~+/=-]+|sk-[a-z0-9_-]{8,}|(?:api[_ -]?key|token|cookie|password|密码|密钥)\\s*[:=：]\\s*[^\\s,，;；]+)");
    // Start only at a token boundary: on long opaque text, retrying the greedy local part at
    // every character would be quadratic even when the input contains no email address.
    private static final Pattern EMAIL = Pattern.compile("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern LONG_ID = Pattern.compile("(?<![0-9])[0-9]{7,}[0-9xX]?(?![0-9])");
    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");
    private AiRedactor() { }
    public static String clean(String value, List<String> privateValues, int maxChars) {
        String text = value == null ? "" : value;
        // Redact before clipping, so a clipped credential cannot escape a full-value match.
        if (privateValues != null) for (String secret : privateValues)
            if (secret != null && !secret.isBlank()) text = text.replace(secret, "[已隐藏]");
        text = SECRET.matcher(text).replaceAll("[凭据已隐藏]");
        text = EMAIL.matcher(text).replaceAll("[邮箱已隐藏]");
        text = LONG_ID.matcher(text).replaceAll("[长号码已隐藏]");
        text = URL.matcher(text).replaceAll("[链接已隐藏]");
        text = text.replaceAll("[\\p{Cc}&&[^\\n\\t]]", "");
        return text.length() > maxChars ? text.substring(0, maxChars) + "\n[已截短]" : text;
    }
}
