package cn.local.tickethelper.core;

/** One 1-to-2 UI transition. A dispatched + is never repeated on an unchanged or unknown read. */
public final class QuantityTransition {
    public enum Result { WAITING, VERIFIED, CHANGED, TIMED_OUT }
    public static final long TIMEOUT_MS = 6000;
    private Observation.VerifiedSelection original;
    private long started;
    public void reset() { original = null; started = 0; }
    public boolean pending() { return original != null; }
    public static boolean isIncreaseProposal(Decision decision) {
        return decision != null && decision.action == Decision.Action.INCREASE_QUANTITY && decision.state == Decision.State.PROBING
                && canBegin(decision.selectionEvidence) && decision.target == decision.selectionEvidence.quantityIncreaseTarget
                && decision.observedAtMs == decision.selectionEvidence.observedAtMs;
    }
    public static boolean canBegin(Observation.VerifiedSelection value) {
        UiItem plus = value == null ? null : value.quantityIncreaseTarget;
        return value != null && value.observedAtMs > 0 && !value.date.isEmpty() && !value.time.isEmpty()
                && value.selectedPrice != null && value.selectedPrice > 0
                && value.quantity != null && value.quantity == 1
                && value.totalYuan != null && value.totalYuan.equals(value.selectedPrice)
                && value.confirmTarget != null && value.confirmTarget.source == UiItem.Source.NODE
                && value.confirmTarget.enabled && value.confirmTarget.clickable && value.confirmTarget.hasBounds()
                && value.confirmTarget.resourceId.equals("cn.damai:id/btn_buy_view")
                && plus != null && plus.source == UiItem.Source.NODE && plus.resourceId.equals("cn.damai:id/img_jia")
                && plus.hasBounds() && plus.enabled && plus.clickable && plus.text.isEmpty();
    }
    public boolean begin(Observation.VerifiedSelection value, long nowElapsed) {
        if (pending() || nowElapsed < 0 || !canBegin(value)) return false;
        original = value; started = nowElapsed; return true;
    }
    public Result inspect(Observation.VerifiedSelection value, long nowElapsed) {
        if (!pending() || nowElapsed < started || nowElapsed - started >= TIMEOUT_MS) return Result.TIMED_OUT;
        if (value == null) return Result.WAITING;
        if (!original.date.equals(value.date) || !original.time.equals(value.time)
                || (value.selectedPrice != null && !original.selectedPrice.equals(value.selectedPrice))) return Result.CHANGED;
        if (value.selectedPrice == null || value.quantity == null || value.totalYuan == null) return Result.WAITING;
        if (value.quantity == 2 && value.totalYuan == (long) original.selectedPrice * 2 && value.confirmTarget != null
                && value.confirmTarget.source == UiItem.Source.NODE && value.confirmTarget.enabled
                && value.confirmTarget.clickable && value.confirmTarget.hasBounds())
            return Result.VERIFIED;
        if (value.quantity != 1 || !value.totalYuan.equals(original.selectedPrice)) return Result.CHANGED;
        return Result.WAITING;
    }
}
