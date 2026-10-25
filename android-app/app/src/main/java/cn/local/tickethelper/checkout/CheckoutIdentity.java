package cn.local.tickethelper.checkout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

final class CheckoutIdentity {
    private CheckoutIdentity() { }
    static String clean(String value) { return value == null ? "" : value.trim(); }
    static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) result.append(String.format(Locale.ROOT, "%02x", item & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String part(String value) { String text = clean(value); return text.length() + ":" + text; }
    static String title(String value, String city) {
        String title = clean(value), quoted = Pattern.quote(clean(city));
        if (!clean(city).isEmpty()) {
            title = title.replaceFirst("^(?:【" + quoted + "】|\\[" + quoted + "\\]|" + quoted + "[·・•])", "");
            title = title.replaceFirst("[-—]" + quoted + "站$", "");
        }
        return title.toLowerCase(Locale.ROOT).replaceAll("[\\s·・•\\-—_【】\\[\\]（）()]", "");
    }
    static String deadlineText(String value) {
        String text = clean(value);
        return text.length() <= 80 && text.matches("[0-9:：年月日时分秒剩余支付时间截止前周一二三四五六天\\s./-]*") ? text : "";
    }
}
