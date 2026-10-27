package cn.local.tickethelper.core;

import java.util.List;

/** Read-only recovery policy. It never extends an observation's input lifetime or authorizes input. */
public final class ReadStability {
    public static final long MAX_FRAME_AGE_MS = 3000, RECOVERY_BUDGET_MS = 15_000;
    public static final int MAX_ATTEMPTS = 12;
    private long beganAt = -1;
    private int attempts;

    public void reset() { beganAt = -1; attempts = 0; }
    public int attempts() { return attempts; }
    /** true means retry another fresh read; the existing frame is always discarded. */
    public boolean retry(long nowElapsed) {
        if (beganAt < 0) beganAt = nowElapsed;
        attempts++;
        long age = nowElapsed - beganAt;
        return age >= 0 && age < RECOVERY_BUDGET_MS && attempts < MAX_ATTEMPTS;
    }
    public long delayMs() { return Math.min(1500, 350L + attempts * 100L); }

    /** Only a content event in the same application window can request a visual recheck. */
    public static boolean mayRecheck(long oldRevision, long currentRevision, long oldBoundary,
                                     long currentBoundary, int oldWindow, int currentWindow, long ageMs) {
        return currentRevision > oldRevision && oldBoundary == currentBoundary && oldWindow >= 0
                && oldWindow == currentWindow && ageMs >= 0 && ageMs < MAX_FRAME_AGE_MS;
    }

    /** Exact native facts, including selection, availability, identity and geometry, must still agree. */
    public static boolean sameNative(List<UiItem> before, List<UiItem> after) {
        if (before == null || after == null || before.isEmpty() || before.size() >= 500
                || before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) {
            UiItem a = before.get(i), b = after.get(i);
            if (a == null || b == null || a.source != UiItem.Source.NODE || b.source != UiItem.Source.NODE
                    || !a.text.equals(b.text) || !a.resourceId.equals(b.resourceId)
                    || a.left != b.left || a.top != b.top || a.right != b.right || a.bottom != b.bottom
                    || a.enabled != b.enabled || a.clickable != b.clickable || a.selected != b.selected) return false;
        }
        return true;
    }
}
