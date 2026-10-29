package cn.local.tickethelper.core;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** User-entered local wall-clock precision; never a claim of server clock synchronization. */
public final class OpeningTime {
    private OpeningTime() { }
    public static LocalTime parse(String value) {
        if (value == null || !value.matches("[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{3})?"))
            throw new IllegalArgumentException("Use HH:mm:ss or HH:mm:ss.SSS");
        return LocalTime.parse(value, DateTimeFormatter.ISO_LOCAL_TIME);
    }
    public static String format(LocalTime time) {
        return time.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
    }
}
