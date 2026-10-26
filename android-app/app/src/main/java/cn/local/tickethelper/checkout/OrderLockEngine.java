package cn.local.tickethelper.checkout;

import cn.local.tickethelper.core.MonitorConfig;
import cn.local.tickethelper.core.UiItem;
import java.time.LocalDate;
import java.time.LocalTime;

/** Pure proposals and result classification. It has no input, storage, retry, or payment capability. */
public final class OrderLockEngine {
    public static final long RESULT_TIMEOUT_MS = 15_000;

    /**
     * A viewer choice is not a submit permit. Both arguments must come from the same native read;
     * the dispatcher must repeat that read and match the exact control before one selection input.
     */
    public boolean canSelectViewer(MonitorConfig config,LockAuthorization authorization,CheckoutObservation observation,
                                   ViewerMatcher.Result viewer,OrderAttempt attempt,long nowMs) {
        if (attempt != null || authorization == null || !authorization.validFor(config,nowMs)
                || !fresh(observation,nowMs) || observation.kind != CheckoutObservation.Kind.CONFIRMATION
                || observation.viewerSelectionVerified || !matchesAuthorization(authorization,observation,false)
                || viewer == null || viewer.state != ViewerMatcher.State.SELECT_PROPOSAL
                || viewer.viewerCount != config.quantity || !viewer.selectionIdentityHash.matches("[0-9a-f]{64}")
                || !authorization.viewerBindingHash.equals(viewer.bindingHash)
                || !observation.selectedViewerBindingHash.equals(viewer.bindingHash)) return false;
        UiItem target=viewer.selectionTarget;
        return target != null && target.source == UiItem.Source.NODE && target.hasBounds()
                && target.enabled && target.clickable && !target.selected && target.text.isEmpty()
                && target.resourceId.startsWith("cn.damai:id/") && target.resourceId.length() > "cn.damai:id/".length();
    }

    /** Final read-only guard after consuming the durable permit; it never issues another permit. */
    public boolean revalidateForDispatch(MonitorConfig config,LockAuthorization authorization,CheckoutObservation current,
                                         VerifiedSubmitProposal proposal,OrderAttempt attempt,long nowMs) {
        if (proposal == null || !validAttempt(attempt) || attempt.state != OrderAttempt.State.AWAITING_RESULT
                || !attempt.submissionAuthorized || nowMs < attempt.updatedAtMs
                || authorization == null || !authorization.validFor(config,nowMs)
                || !proposal.grantId.equals(authorization.grantId) || !proposal.grantId.equals(attempt.grantId)
                || !proposal.configFingerprint.equals(authorization.configFingerprint)
                || !proposal.configFingerprint.equals(attempt.configFingerprint)
                || !proposal.targetFingerprint.equals(attempt.targetFingerprint)
                || !fresh(current,nowMs) || current.kind != CheckoutObservation.Kind.CONFIRMATION
                || current.observedAtMs < proposal.observedAtMs || nowMs-proposal.observedAtMs > 3000
                || current.windowId != proposal.windowId || current.frameGeneration != proposal.frameGeneration
                || !matchesAuthorization(authorization,current) || !current.agreementsAccepted
                || !current.uniqueNativeSubmitTarget || !nativeSubmit(current.submitTarget,current.submitBinding)
                || !java.util.Objects.equals(proposal.submitBinding,current.submitBinding)
                || !sameControl(proposal.submitTarget,current.submitTarget)) return false;
        return proposal.targetFingerprint.equals(target(current).fingerprint());
    }

    public CheckoutDecision evaluate(MonitorConfig config,LockAuthorization authorization,CheckoutObservation observation,
                                     OrderAttempt attempt,long nowMs) {
        // The durable barrier has priority over configuration, grants, and page contents.
        if (attempt != null) return observeAttempt(attempt,observation,nowMs);
        if (authorization == null || !authorization.validFor(config,nowMs))
            return result(CheckoutDecision.State.NEEDS_REVIEW,"live_authorization_missing_or_changed");
        if (!fresh(observation,nowMs)) return result(CheckoutDecision.State.NEEDS_REVIEW,"checkout_frame_unverified");
        if (observation.kind != CheckoutObservation.Kind.CONFIRMATION)
            return result(CheckoutDecision.State.NEEDS_REVIEW,"not_verified_confirmation_page");
        if (!matchesAuthorization(authorization,observation))
            return result(CheckoutDecision.State.NEEDS_REVIEW,"checkout_target_viewer_or_amount_mismatch");
        if (!observation.agreementsAccepted)
            return result(CheckoutDecision.State.NEEDS_REVIEW,"checkout_agreements_unverified");
        UiItem submit = observation.submitTarget;
        if (!observation.uniqueNativeSubmitTarget || !nativeSubmit(submit,observation.submitBinding))
            return result(CheckoutDecision.State.NEEDS_REVIEW,"native_order_submit_unverified");
        OrderTarget target = target(observation);
        return new CheckoutDecision(CheckoutDecision.State.READY_TO_RESERVE,"checkout_facts_verified",
                new VerifiedSubmitProposal(authorization,target,observation),null);
    }

    private CheckoutDecision observeAttempt(OrderAttempt attempt,CheckoutObservation observation,long nowMs) {
        if (!validAttempt(attempt)) return result(CheckoutDecision.State.OUTCOME_UNKNOWN,"persisted_attempt_unverified");
        if (nowMs <= 0 || nowMs < attempt.updatedAtMs)
            return result(CheckoutDecision.State.OUTCOME_UNKNOWN,"attempt_clock_changed");
        if (attempt.state == OrderAttempt.State.CONFIRMED_PENDING) {
            if (!attempt.orderReferenceHash.matches("[0-9a-f]{64}"))
                return result(CheckoutDecision.State.OUTCOME_UNKNOWN,"persisted_order_reference_unverified");
            // Expiry never clears the attempt or permits another submission. This is the last
            // confirmed result, not a claim that its payment window is still currently open.
            return new CheckoutDecision(CheckoutDecision.State.MATCHING_PENDING_REVIEW,
                    attempt.paymentDeadlineMs != null && attempt.paymentDeadlineMs <= nowMs
                            ? "persisted_pending_payment_window_expired" : "persisted_pending_order_barrier",null,
                    new VerifiedPendingOrder(attempt.target,attempt.orderReferenceHash,attempt.paymentDeadlineMs,
                            attempt.paymentDeadlineText,attempt.updatedAtMs));
        }
        if (attempt.state == OrderAttempt.State.INVENTORY_REJECTED)
            return result(CheckoutDecision.State.INVENTORY_REJECTED,"persisted_inventory_rejection_barrier");
        if (observation == null) return waiting(attempt,nowMs,"awaiting_order_result");
        if (observation.kind == CheckoutObservation.Kind.VERIFICATION
                || observation.kind == CheckoutObservation.Kind.PAYMENT_OR_PAID
                || !"cn.damai".equals(observation.packageName))
            return result(CheckoutDecision.State.OUTCOME_UNKNOWN,"submission_result_requires_user_review");
        if (!fresh(observation,nowMs) || observation.observedAtMs < attempt.reservedAtMs)
            return waiting(attempt,nowMs,"result_frame_unverified");
        if (!attempt.attemptId.equals(observation.relatedAttemptId))
            return result(CheckoutDecision.State.OUTCOME_UNKNOWN,"result_attempt_unverified");
        if (observation.kind == CheckoutObservation.Kind.PAYMENT_PENDING) {
            if (!matchesAttempt(attempt,observation) || !observation.orderReferenceVerified
                    || observation.orderReference.length() < 4 || observation.orderReference.length() > 200
                    || observation.orderReference.chars().anyMatch(Character::isISOControl))
                return result(CheckoutDecision.State.OUTCOME_UNKNOWN,"pending_order_facts_incomplete");
            VerifiedPendingOrder order = new VerifiedPendingOrder(attempt.target,CheckoutIdentity.hash(observation.orderReference),
                    observation.paymentDeadlineMs,observation.paymentDeadlineText,observation.observedAtMs,
                    observation.orderCreatedAtMs,observation.orderCreationTimeVerified);
            if (observation.orderCreationTimeVerified && observation.orderCreatedAtMs != null
                    && (observation.orderCreatedAtMs <= 0 || observation.orderCreatedAtMs > observation.observedAtMs))
                return result(CheckoutDecision.State.OUTCOME_UNKNOWN,"order_creation_time_invalid");
            if (observation.paymentDeadlineMs != null && (observation.paymentDeadlineMs <= 0
                    || (observation.orderCreationTimeVerified && observation.orderCreatedAtMs != null
                    && observation.paymentDeadlineMs <= observation.orderCreatedAtMs)))
                return result(CheckoutDecision.State.OUTCOME_UNKNOWN,"payment_deadline_invalid");
            if (observation.paymentDeadlineMs != null && observation.paymentDeadlineMs <= nowMs)
                return new CheckoutDecision(CheckoutDecision.State.MATCHING_PENDING_REVIEW,"matching_pending_payment_window_expired",null,order);
            if (!attempt.submissionAuthorized || attempt.state == OrderAttempt.State.RESERVED)
                return new CheckoutDecision(CheckoutDecision.State.MATCHING_PENDING_REVIEW,"matching_pending_submission_not_authorized",null,order);
            // The locally supplied attempt id and matching target cannot establish new-order
            // provenance. A visible, exact official creation time must also place it after reserve.
            if (!observation.orderCreationTimeVerified || observation.orderCreatedAtMs == null
                    || observation.orderCreatedAtMs < attempt.reservedAtMs)
                return new CheckoutDecision(CheckoutDecision.State.MATCHING_PENDING_REVIEW,"matching_pending_creation_unverified",null,order);
            return new CheckoutDecision(CheckoutDecision.State.CONFIRMED_PENDING,"official_pending_order_verified",null,order);
        }
        if (observation.kind == CheckoutObservation.Kind.INVENTORY_REJECTED) {
            if (attempt.submissionAuthorized && observation.authoritativeInventoryRejection && matchesAttempt(attempt,observation))
                return result(CheckoutDecision.State.INVENTORY_REJECTED,"official_inventory_rejection_verified");
            return result(CheckoutDecision.State.OUTCOME_UNKNOWN,"inventory_rejection_unverified");
        }
        return waiting(attempt,nowMs,"submission_result_not_yet_verified");
    }
    private static CheckoutDecision waiting(OrderAttempt attempt,long nowMs,String reason) {
        return result(!attempt.submissionAuthorized || attempt.state == OrderAttempt.State.OUTCOME_UNKNOWN
                || nowMs-attempt.reservedAtMs >= RESULT_TIMEOUT_MS
                ? CheckoutDecision.State.OUTCOME_UNKNOWN : CheckoutDecision.State.WAIT_RESULT,reason);
    }
    private static boolean fresh(CheckoutObservation observation,long nowMs) {
        return observation != null && nowMs > 0 && observation.observedAtMs > 0
                && (observation.observedAtMs > nowMs ? observation.observedAtMs-nowMs <= 1000 : nowMs-observation.observedAtMs <= 3000)
                && "cn.damai".equals(observation.packageName)
                && observation.sameFrame && observation.nativeWindowVerified && observation.windowId >= 0
                && observation.frameGeneration >= 0;
    }
    private static boolean matchesAuthorization(LockAuthorization authorization,CheckoutObservation observation) {
        return matchesAuthorization(authorization,observation,true);
    }
    private static boolean matchesAuthorization(LockAuthorization authorization,CheckoutObservation observation,boolean requireSelected) {
        MonitorConfig config=authorization.config;
        if (!completeFacts(observation,requireSelected) || !config.city.equals(observation.city) || !config.date.equals(observation.date)
                || !config.time.equals(observation.time) || !authorization.viewerBindingHash.equals(observation.selectedViewerBindingHash)
                || !CheckoutIdentity.title(authorization.eventTitle,config.city).equals(CheckoutIdentity.title(observation.eventTitle,observation.city))
                || config.quantity != observation.quantity || observation.payableFen > (long)config.maxTotal*100) return false;
        return config.prices.stream().anyMatch(price -> (long)price*100 == observation.unitPriceFen);
    }
    private static boolean matchesAttempt(OrderAttempt attempt,CheckoutObservation observation) {
        return completeFacts(observation) && attempt.targetFingerprint.equals(target(observation).fingerprint());
    }
    private static boolean completeFacts(CheckoutObservation observation) {
        return completeFacts(observation,true);
    }
    private static boolean completeFacts(CheckoutObservation observation,boolean requireSelected) {
        if (!validPublicTarget(observation.eventTitle,observation.city,observation.date,observation.time)
                || observation.unitPriceFen == null || observation.quantity == null || observation.ticketSubtotalFen == null
                || observation.payableFen == null || observation.unitPriceFen <= 0 || observation.quantity < 1 || observation.quantity > 2
                || observation.unitPriceFen > 10_000_000 || (requireSelected && !observation.viewerSelectionVerified)
                || observation.ticketSubtotalFen > 10_000_000
                || !observation.selectedViewerBindingHash.matches("[0-9a-f]{64}") || !observation.noUnapprovedExtras) return false;
        // The first lock mode accepts no additional fee or silently applied price change.
        return observation.ticketSubtotalFen == observation.unitPriceFen*observation.quantity
                && observation.payableFen.longValue() == observation.ticketSubtotalFen.longValue();
    }
    private static OrderTarget target(CheckoutObservation observation) {
        return new OrderTarget(observation.eventTitle,observation.city,observation.date,observation.time,
                observation.unitPriceFen,observation.quantity,observation.ticketSubtotalFen,observation.payableFen,
                observation.selectedViewerBindingHash);
    }
    private static boolean validAttempt(OrderAttempt attempt) {
        if (attempt == null || !attempt.attemptId.matches("[A-Za-z0-9_.:-]{1,128}")
                || !attempt.grantId.matches("[A-Za-z0-9_.:-]{1,128}") || !attempt.configFingerprint.matches("[0-9a-f]{64}")
                || attempt.state == null || attempt.target == null || attempt.reservedAtMs <= 0 || attempt.updatedAtMs < attempt.reservedAtMs)
            return false;
        OrderTarget target=attempt.target;
        if (!validPublicTarget(target.eventTitle,target.city,target.date,target.time) || target.quantity < 1 || target.quantity > 2
                || target.unitPriceFen <= 0 || target.unitPriceFen > 10_000_000
                || target.ticketSubtotalFen != target.unitPriceFen * target.quantity || target.ticketSubtotalFen > 10_000_000
                || target.payableFen != target.ticketSubtotalFen || !target.viewerBindingHash.matches("[0-9a-f]{64}")
                || !attempt.targetFingerprint.equals(target.fingerprint())) return false;
        if (attempt.state == OrderAttempt.State.RESERVED && attempt.submissionAuthorized) return false;
        if ((attempt.state == OrderAttempt.State.AWAITING_RESULT || attempt.state == OrderAttempt.State.CONFIRMED_PENDING
                || attempt.state == OrderAttempt.State.INVENTORY_REJECTED)
                && !attempt.submissionAuthorized) return false;
        if (attempt.state == OrderAttempt.State.CONFIRMED_PENDING)
            return attempt.orderReferenceHash.matches("[0-9a-f]{64}") && (attempt.paymentDeadlineMs == null || attempt.paymentDeadlineMs > 0);
        return attempt.orderReferenceHash.isEmpty() && attempt.paymentDeadlineMs == null && attempt.paymentDeadlineText.isEmpty();
    }
    private static boolean validPublicTarget(String title,String city,String date,String time) {
        if (title.isEmpty() || title.length() > 300 || city.isEmpty() || city.length() > 40
                || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !time.matches("[0-9]{2}:[0-9]{2}")) return false;
        try { return LocalDate.parse(date).toString().equals(date) && LocalTime.parse(time).toString().equals(time); }
        catch (java.time.DateTimeException invalid) { return false; }
    }
    private static boolean nativeSubmit(UiItem target,CheckoutSubmitBinding binding) {
        return target != null && target.source == UiItem.Source.NODE && target.hasBounds() && target.enabled
                && target.clickable && CheckoutProfile.isSubmitLabel(target.text.trim())
                && (binding == null ? target.resourceId.startsWith("cn.damai:id/") : binding.matchesTarget(target));
    }
    private static boolean sameControl(UiItem expected,UiItem current) {
        return expected != null && current != null && expected.source == current.source
                && expected.resourceId.equals(current.resourceId) && expected.text.equals(current.text)
                && expected.left == current.left && expected.top == current.top
                && expected.right == current.right && expected.bottom == current.bottom;
    }
    private static CheckoutDecision result(CheckoutDecision.State state,String reason) {
        return new CheckoutDecision(state,reason,null,null);
    }
}
