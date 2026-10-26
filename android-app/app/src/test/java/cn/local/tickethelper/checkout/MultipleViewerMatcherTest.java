package cn.local.tickethelper.checkout;

import cn.local.tickethelper.core.UiItem;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic local identities and native checkbox facts; never real attendee data. */
public final class MultipleViewerMatcherTest {
    private static final String FIRST = "合成测试甲", SECOND = "合成测试乙", THIRD = "合成测试丙";
    private final List<ViewerMatcher.RequestedViewer> requested = List.of(request(FIRST, "1234"), request(SECOND, "5678"));
    private static ViewerMatcher.RequestedViewer request(String name, String suffix) {
        return new ViewerMatcher.RequestedViewer(name, suffix);
    }
    private ViewerMatcher.ViewerRow row(int index, String name, String document, boolean selected) {
        UiItem checkbox = new UiItem("合成敏感文字", 800, 100 + index * 100, 850, 150 + index * 100,
                true, true, selected, UiItem.Source.NODE, "synthetic:id/viewer_checkbox");
        return new ViewerMatcher.ViewerRow("row-" + index, name, document, checkbox, selected, true, false);
    }
    private ViewerMatcher.Result match(ViewerMatcher.ViewerRow... rows) {
        return ViewerMatcher.matchMany(requested, List.of(rows), true);
    }
    private void uncertain(String reason, ViewerMatcher.Result result) {
        assertEquals(ViewerMatcher.State.UNCERTAIN, result.state); assertEquals(reason, result.reason);
        assertEquals(0, result.viewerCount); assertEquals("", result.bindingHash); assertNull(result.selectionTarget);
        assertEquals("", result.selectionIdentityHash);
    }

    @Test public void twoRequestedSelectedPeopleVerifyOneStablePartyBinding() {
        ViewerMatcher.Result result = match(row(0, FIRST, "**1234", true), row(1, SECOND, "**5678", true));
        assertEquals(ViewerMatcher.State.VERIFIED_SELECTED, result.state); assertEquals(2, result.viewerCount);
        assertEquals(LockAuthorization.viewerBindingsHash(requested), result.bindingHash); assertNull(result.selectionTarget);
        assertEquals("", result.selectionIdentityHash);
        assertEquals(result.bindingHash, LockAuthorization.viewerBindingsHash(List.of(requested.get(1), requested.get(0))));
        assertNotEquals(result.bindingHash, LockAuthorization.viewerBindingHash(FIRST, "1234"));
    }
    @Test public void singleRequestedPersonPreservesOldHashAndMatchingBehavior() {
        ViewerMatcher.Result result = ViewerMatcher.matchMany(List.of(requested.get(0)), List.of(row(0, FIRST, "**1234", true)), true);
        assertEquals(ViewerMatcher.State.VERIFIED_SELECTED, result.state); assertEquals(1, result.viewerCount);
        assertEquals(LockAuthorization.viewerBindingHash(FIRST, "1234"), result.bindingHash);
        assertEquals(result.bindingHash, LockAuthorization.viewerBindingsHash(List.of(requested.get(0))));
        assertEquals("", result.selectionIdentityHash);
        ViewerMatcher.Result proposal = ViewerMatcher.matchMany(List.of(requested.get(0)), List.of(row(0, FIRST, "**1234", false)), true);
        assertEquals(LockAuthorization.viewerBindingHash(FIRST, "1234"), proposal.selectionIdentityHash);
    }
    @Test public void twoUnselectedPeopleProposeOnlyTheFirstRedactedCheckbox() {
        ViewerMatcher.Result result = match(row(0, FIRST, "**1234", false), row(1, SECOND, "**5678", false));
        assertEquals(ViewerMatcher.State.SELECT_PROPOSAL, result.state); assertEquals(2, result.viewerCount);
        assertEquals(100, result.selectionTarget.top); assertEquals("", result.selectionTarget.text);
        assertFalse(result.selectionTarget.selected);
    }
    @Test public void afterFirstSelectionOnlySecondCheckboxIsProposedUntilBothAreReread() {
        ViewerMatcher.Result result = match(row(0, FIRST, "**1234", true), row(1, SECOND, "**5678", false));
        assertEquals(ViewerMatcher.State.SELECT_PROPOSAL, result.state); assertEquals(200, result.selectionTarget.top);
        assertEquals(ViewerMatcher.State.VERIFIED_SELECTED,
                match(row(0, FIRST, "**1234", true), row(1, SECOND, "**5678", true)).state);
    }
    @Test public void proposedPersonIdentitySurvivesRowReorderingAndDiffersFromTheNextPerson() {
        ViewerMatcher.Result first = match(row(0, FIRST, "**1234", false), row(1, SECOND, "**5678", false));
        ViewerMatcher.Result reordered = match(row(5, SECOND, "**5678", false), row(3, FIRST, "**1234", false));
        assertEquals(first.selectionIdentityHash, reordered.selectionIdentityHash);
        assertEquals(LockAuthorization.viewerBindingHash(FIRST, "1234"), reordered.selectionIdentityHash);
        assertNotEquals(first.selectionTarget.top, reordered.selectionTarget.top);
        ViewerMatcher.Result next = match(row(5, SECOND, "**5678", false), row(3, FIRST, "**1234", true));
        assertEquals(first.bindingHash, next.bindingHash);
        assertNotEquals(first.selectionIdentityHash, next.selectionIdentityHash);
        assertEquals(LockAuthorization.viewerBindingHash(SECOND, "5678"), next.selectionIdentityHash);
    }
    @Test public void selectedExtraPersonAlwaysBlocksEvenWhenBothRequestedPeopleAreSelected() {
        uncertain("different_viewer_selected", match(row(0, FIRST, "**1234", true), row(1, SECOND, "**5678", true), row(2, THIRD, "**9012", true)));
        uncertain("different_viewer_selected", match(row(0, FIRST, "**1234", false), row(1, SECOND, "**5678", true), row(2, THIRD, "**9012", true)));
    }
    @Test public void missingSecondPersonDoesNotProduceAPartialPartyOrClick() {
        uncertain("viewer_not_found", match(row(0, FIRST, "**1234", true)));
    }
    @Test public void sameFullNameCanIdentifyTwoDistinctPeopleOnlyWithVisibleUniqueTails() {
        List<ViewerMatcher.RequestedViewer> sameName = List.of(request(FIRST, "1234"), request(FIRST, "5678"));
        ViewerMatcher.Result result = ViewerMatcher.matchMany(sameName,
                List.of(row(0, FIRST, "**1234", true), row(1, FIRST, "**5678", true)), true);
        assertEquals(ViewerMatcher.State.VERIFIED_SELECTED, result.state); assertEquals(2, result.viewerCount);
        uncertain("ambiguous_viewer_match", ViewerMatcher.matchMany(List.of(request(FIRST, ""), request(FIRST, "5678")),
                List.of(row(0, FIRST, "**1234", true), row(1, FIRST, "**5678", true)), true));
    }
    @Test public void duplicateRequestOrOverlappingSuffixCannotCountOnePersonTwice() {
        uncertain("invalid_viewer_config", ViewerMatcher.matchMany(List.of(requested.get(0), requested.get(0)), List.of(row(0, FIRST, "**1234", true)), true));
        uncertain("duplicate_requested_viewer", ViewerMatcher.matchMany(List.of(request(FIRST, "1234"), request(FIRST, "34")),
                List.of(row(0, FIRST, "**1234", true)), true));
    }
    @Test public void undisclosedTailCannotExcludeAnotherSameNameRow() {
        uncertain("viewer_suffix_not_visible", match(row(0, FIRST, "**1234", true), row(1, SECOND, "**5678", true), row(2, FIRST, "", false)));
    }
    @Test public void wrongOrMaskedSecondIdentityNeverVerifies() {
        uncertain("viewer_suffix_mismatch", match(row(0, FIRST, "**1234", true), row(1, SECOND, "**9000", true)));
        uncertain("viewer_name_incomplete", match(row(0, FIRST, "**1234", true), row(1, "合成*乙", "**5678", true)));
    }
    @Test public void completeScopeAndKnownUnprotectedSelectionAreRequiredForEveryRow() {
        List<ViewerMatcher.ViewerRow> rows = List.of(row(0, FIRST, "**1234", true), row(1, SECOND, "**5678", true));
        uncertain("incomplete_native_viewer_scope", ViewerMatcher.matchMany(requested, rows, false));
        ViewerMatcher.ViewerRow second = rows.get(1);
        uncertain("viewer_selection_unknown", match(rows.get(0), new ViewerMatcher.ViewerRow(second.rowKey, second.visibleName,
                second.visibleDocument, second.selectionTarget, true, false, false)));
        uncertain("protected_viewer_control", match(rows.get(0), new ViewerMatcher.ViewerRow(second.rowKey, second.visibleName,
                second.visibleDocument, second.selectionTarget, true, true, true)));
    }
    @Test public void duplicateNativeRowsAndControlsNeverCountAsTwoPeople() {
        ViewerMatcher.ViewerRow first = row(0, FIRST, "**1234", true);
        uncertain("duplicate_viewer_row", match(first, first));
        uncertain("duplicate_viewer_control", match(first, new ViewerMatcher.ViewerRow("other", SECOND, "**5678",
                first.selectionTarget, true, true, false)));
    }
    @Test public void allUnselectedControlsMustBeActionableBeforeAnyProposal() {
        ViewerMatcher.ViewerRow second = row(1, SECOND, "**5678", false);
        UiItem node = second.selectionTarget;
        UiItem disabled = new UiItem("", node.left, node.top, node.right, node.bottom, false, true, false, UiItem.Source.NODE, node.resourceId);
        uncertain("viewer_control_not_actionable", match(row(0, FIRST, "**1234", false),
                new ViewerMatcher.ViewerRow(second.rowKey, SECOND, "**5678", disabled, false, true, false)));
    }
    @Test public void emptyOverLimitOrInvalidRequestedPartiesHaveNoDigestOrProposal() {
        for (List<ViewerMatcher.RequestedViewer> invalid : List.of(List.<ViewerMatcher.RequestedViewer>of(),
                List.of(requested.get(0), requested.get(1), request(THIRD, "9012")), List.of(request("合成*甲", "1234"), requested.get(1)))) {
            assertEquals("", LockAuthorization.viewerBindingsHash(invalid));
            uncertain("invalid_viewer_config", ViewerMatcher.matchMany(invalid, List.of(row(0, FIRST, "**1234", true)), true));
        }
    }
}
