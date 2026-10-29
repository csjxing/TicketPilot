package cn.local.tickethelper.core;

import java.util.Locale;

/** Presentation precision is not a promise of scheduling or server precision. */
public final class CountdownText {
    private CountdownText() { }
    public static String format(long remainingMs) {
        long n=Math.max(0,remainingMs);
        return String.format(Locale.ROOT,"%02d:%02d:%02d.%03d",n/3600000,n/60000%60,n/1000%60,n%1000);
    }
}
