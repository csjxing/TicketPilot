package cn.local.tickethelper.checkout;

/** No raw native text or viewer names in diagnostics. The observation remains ephemeral. */
public final class CheckoutParseResult {
    public enum Status { PARSED, UNSUPPORTED, INVALID_FRAME, AMBIGUOUS, INCOMPLETE }
    public final Status status;
    public final CheckoutObservation observation;
    public final String reason;
    /** A selection proposal is advisory only and never constitutes verified viewer selection. */
    public final ViewerMatcher.Result viewerResult;
    CheckoutParseResult(Status status, CheckoutObservation observation, String reason, ViewerMatcher.Result viewerResult) {
        this.status = status; this.observation = observation; this.reason = reason; this.viewerResult = viewerResult;
    }
}
