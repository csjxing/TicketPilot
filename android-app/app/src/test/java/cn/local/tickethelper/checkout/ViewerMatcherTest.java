package cn.local.tickethelper.checkout;

import cn.local.tickethelper.core.UiItem;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** All rows, names, document fragments and resource IDs here are synthetic fixtures, not device evidence. */
public final class ViewerMatcherTest {
    private static final String NAME = "合成测试甲", OTHER = "合成测试乙";
    private static final String SUFFIX = "1234";

    private UiItem node(int index, boolean checked, boolean enabled, boolean clickable, UiItem.Source source) {
        return new UiItem("合成敏感文字 **1234", 800, 100 + index * 100, 850, 150 + index * 100,
                enabled, clickable, checked, source, "synthetic:id/viewer_checkbox");
    }
    private ViewerMatcher.ViewerRow row(int index, String name, String document, boolean checked) {
        return row(index, name, document, checked, true, false, node(index, checked, true, true, UiItem.Source.NODE));
    }
    private ViewerMatcher.ViewerRow row(int index, String name, String document, boolean checked,
                                        boolean known, boolean protectedControl, UiItem node) {
        return new ViewerMatcher.ViewerRow("synthetic-row-" + index, name, document, node, checked, known, protectedControl);
    }
    private ViewerMatcher.Result match(ViewerMatcher.ViewerRow... rows) {
        return ViewerMatcher.match(NAME, SUFFIX, Arrays.asList(rows), true);
    }
    private void uncertain(String reason, ViewerMatcher.Result result) {
        assertEquals(ViewerMatcher.State.UNCERTAIN, result.state);
        assertEquals(reason, result.reason);
        assertEquals("", result.bindingHash);
        assertNull(result.selectionTarget);
    }

    @Test public void selectedExactNativeNameAndVisibleSuffixVerifyObservedBinding() {
        ViewerMatcher.Result result = match(row(0, NAME, "身份证：33********1234", true), row(1, OTHER, "**5678", false));
        assertEquals(ViewerMatcher.State.VERIFIED_SELECTED, result.state);
        assertEquals(LockAuthorization.viewerBindingHash(NAME, SUFFIX), result.bindingHash);
        assertNull(result.selectionTarget);
    }
    @Test public void uniqueUnselectedRowOnlyProposesRedactedControlWithoutClaimingSelection() {
        ViewerMatcher.Result result = match(row(0, NAME, "**1234", false), row(1, OTHER, "**5678", false));
        assertEquals(ViewerMatcher.State.SELECT_PROPOSAL, result.state);
        assertEquals("unique_unselected_viewer", result.reason);
        assertEquals(LockAuthorization.viewerBindingHash(NAME, SUFFIX), result.bindingHash);
        assertEquals("", result.selectionTarget.text);
        assertEquals("synthetic:id/viewer_checkbox", result.selectionTarget.resourceId);
        assertEquals(100, result.selectionTarget.top);
        assertFalse(result.selectionTarget.selected);
    }
    @Test public void selectionProposalRequiresNewObservedSelectedStateForVerification() {
        assertEquals(ViewerMatcher.State.SELECT_PROPOSAL, match(row(0, NAME, "**1234", false)).state);
        assertEquals(ViewerMatcher.State.VERIFIED_SELECTED, match(row(0, NAME, "**1234", true)).state);
    }
    @Test public void uniqueNameCanMatchWithoutOptionalSuffixButDoesNotInventOne() {
        ViewerMatcher.Result result = ViewerMatcher.match(NAME, "", List.of(row(0, NAME, "", true)), true);
        assertEquals(ViewerMatcher.State.VERIFIED_SELECTED, result.state);
        assertEquals(LockAuthorization.viewerBindingHash(NAME, ""), result.bindingHash);
        assertNotEquals(LockAuthorization.viewerBindingHash(NAME, SUFFIX), result.bindingHash);
    }
    @Test public void sameNameWithoutConfiguredSuffixIsAmbiguousEvenWhenDocumentsDiffer() {
        uncertain("ambiguous_viewer_match", ViewerMatcher.match(NAME, "", List.of(
                row(0, NAME, "**1234", true), row(1, NAME, "**5678", false)), true));
    }
    @Test public void visibleDifferentSuffixesMayDisambiguateSameFullName() {
        ViewerMatcher.Result result = match(row(0, NAME, "**5678", false), row(1, NAME, "**1234", true));
        assertEquals(ViewerMatcher.State.VERIFIED_SELECTED, result.state);
    }
    @Test public void sameNameWithSameTailNeverDisambiguates() {
        uncertain("ambiguous_viewer_match", match(row(0, NAME, "11**1234", true), row(1, NAME, "22**1234", false)));
    }
    @Test public void undisclosedTailOnAnotherSameNameRowCannotExcludeDuplicate() {
        uncertain("viewer_suffix_not_visible", match(row(0, NAME, "**1234", true), row(1, NAME, "", false)));
    }
    @Test public void requiredTailMustActuallyBeVisibleAndLongEnough() {
        for (String document : List.of("", "身份证", "********", "**34", "1234****", "尾号未显示"))
            uncertain("viewer_suffix_not_visible", match(row(0, NAME, document, true)));
    }
    @Test public void substringOrOtherDocumentTextIsNotTailProof() {
        for (String document : List.of("**123456", "1234", "**0000")) {
            String expected = document.equals("1234") ? "1235" : SUFFIX;
            uncertain("viewer_suffix_mismatch", ViewerMatcher.match(NAME, expected,
                    List.of(row(0, NAME, document, true)), true));
        }
        for (String document : List.of("姓名1234", "**1234 其他号码", "**12 34", "尾号1234/5678"))
            uncertain("viewer_suffix_not_visible", match(row(0, NAME, document, true)));
    }
    @Test public void supportedVisibleTailLabelsAndMaskCharactersHaveExactSuffixMeaning() {
        for (String document : List.of("1234", "尾号1234", "证件尾号：1234", "护照:AB**1234", "＊＊1234", "●●1234", "••1234", "··1234", "××1234"))
            assertEquals(document, ViewerMatcher.State.VERIFIED_SELECTED, match(row(0, NAME, document, true)).state);
    }
    @Test public void LetterTailIsCaseNormalizedButNamesAreNotFuzzilyMatched() {
        ViewerMatcher.Result result = ViewerMatcher.match(NAME, "12x", List.of(row(0, NAME, "**12x", true)), true);
        assertEquals(ViewerMatcher.State.VERIFIED_SELECTED, result.state);
        assertEquals(LockAuthorization.viewerBindingHash(NAME, "12X"), result.bindingHash);
        uncertain("viewer_not_found", ViewerMatcher.match("SYNTHETIC", "", List.of(row(0, "Synthetic", "", true)), true));
    }
    @Test public void trimsOuterWhitespaceButPreservesMeaningfulInternalNameCharacters() {
        ViewerMatcher.Result result = ViewerMatcher.match(" " + NAME + " ", " 1234 ", List.of(row(0, " " + NAME + " ", " **1234 ", true)), true);
        assertEquals(ViewerMatcher.State.VERIFIED_SELECTED, result.state);
        uncertain("viewer_not_found", ViewerMatcher.match("合成 测试甲", SUFFIX, List.of(row(0, NAME, "**1234", true)), true));
        assertEquals(ViewerMatcher.State.VERIFIED_SELECTED, ViewerMatcher.match("合成·测试", "", List.of(row(0, "合成·测试", "", true)), true).state);
    }
    @Test public void maskedOrAbsentNamesNeverMatchEvenWhenTailMatches() {
        for (String name : List.of("", "合成*甲", "合成＊甲", "合成●甲", "合成•甲", "合成?甲", "合成…甲"))
            uncertain("viewer_name_incomplete", match(row(0, name, "**1234", true)));
    }
    @Test public void unknownMaskedAdditionalRowStopsUniquenessClaim() {
        uncertain("viewer_name_incomplete", match(row(0, NAME, "**1234", true), row(1, "合成*", "**5678", false)));
    }
    @Test public void duplicatedRowsOrCheckboxesStopRatherThanDeduplicateEvidence() {
        ViewerMatcher.ViewerRow first = row(0, NAME, "**1234", true);
        uncertain("duplicate_viewer_row", match(first, first));
        uncertain("duplicate_viewer_control", match(first,
                row(1, OTHER, "**5678", true, true, false, node(0, true, true, true, UiItem.Source.NODE))));
    }
    @Test public void selectedOtherViewerNeverProducesAutomaticDeselectionOrProposal() {
        uncertain("different_viewer_selected", match(row(0, NAME, "**1234", false), row(1, OTHER, "**5678", true)));
        uncertain("different_viewer_selected", match(row(0, NAME, "**1234", false), row(1, NAME, "**5678", true)));
    }
    @Test public void multipleSelectedRowsNeverVerifyOrPropose() {
        uncertain("multiple_viewers_selected", match(row(0, NAME, "**1234", true), row(1, OTHER, "**5678", true)));
    }
    @Test public void unknownSelectionOnAnyRowBlocksIncludingUnrelatedRows() {
        uncertain("viewer_selection_unknown", match(row(0, NAME, "**1234", true),
                row(1, OTHER, "**5678", false, false, false, node(1, false, true, true, UiItem.Source.NODE))));
        uncertain("viewer_selection_unknown", match(row(0, NAME, "**1234", false, false, false, node(0, false, true, true, UiItem.Source.NODE))));
    }
    @Test public void checkedNodeConflictCannotBeResolvedByGuessing() {
        uncertain("viewer_selection_conflict", match(row(0, NAME, "**1234", true, true, false, node(0, false, true, true, UiItem.Source.NODE))));
    }
    @Test public void protectedControlExplicitlyBlocksButNamesAloneAreNotClassifiedProtected() {
        uncertain("protected_viewer_control", match(row(0, NAME, "**1234", false, true, true, node(0, false, true, true, UiItem.Source.NODE))));
        uncertain("protected_viewer_control", match(row(0, NAME, "**1234", true),
                row(1, OTHER, "**5678", false, true, true, node(1, false, true, true, UiItem.Source.NODE))));
    }
    @Test public void missingOcrOrUnboundedCheckboxNeverCountsAsNativeSelection() {
        uncertain("native_selection_control_missing", match(row(0, NAME, "**1234", true, true, false, null)));
        uncertain("native_selection_control_missing", match(row(0, NAME, "**1234", true, true, false, node(0, true, true, true, UiItem.Source.OCR))));
        uncertain("native_selection_control_missing", match(row(0, NAME, "**1234", true, true, false,
                new UiItem("", 0, 0, 0, 0, true, true, true, UiItem.Source.NODE))));
    }
    @Test public void proposalRequiresActionableControlButDisabledCheckedFactCanBeVerified() {
        for (UiItem node : List.of(node(0, false, false, true, UiItem.Source.NODE), node(0, false, true, false, UiItem.Source.NODE)))
            uncertain("viewer_control_not_actionable", match(row(0, NAME, "**1234", false, true, false, node)));
        assertEquals(ViewerMatcher.State.VERIFIED_SELECTED, match(row(0, NAME, "**1234", true, true, false,
                node(0, true, false, false, UiItem.Source.NODE))).state);
    }
    @Test public void incompleteScopeAndMissingRowsCannotBorrowConfiguredIdentity() {
        uncertain("incomplete_native_viewer_scope", ViewerMatcher.match(NAME, SUFFIX, List.of(row(0, NAME, "**1234", true)), false));
        uncertain("viewer_rows_missing", ViewerMatcher.match(NAME, SUFFIX, List.of(), true));
        uncertain("viewer_rows_missing", ViewerMatcher.match(NAME, SUFFIX, null, true));
        uncertain("viewer_not_found", match(row(0, OTHER, "**1234", true)));
    }
    @Test public void malformedRowsAndConfigFailClosedWithoutEchoingInputs() {
        uncertain("viewer_row_identity_missing", match((ViewerMatcher.ViewerRow) null));
        uncertain("viewer_row_identity_missing", match(new ViewerMatcher.ViewerRow("", NAME, "**1234", node(0, true, true, true, UiItem.Source.NODE), true, true, false)));
        for (String name : List.of("", "合成*甲", "合成\n甲", "123456789012345678"))
            uncertain("invalid_viewer_config", ViewerMatcher.match(name, SUFFIX, List.of(row(0, NAME, "**1234", true)), true));
        for (String suffix : List.of("1", "1234567", "**1234", "12 34", "12\n34"))
            uncertain("invalid_viewer_config", ViewerMatcher.match(NAME, suffix, List.of(row(0, NAME, "**1234", true)), true));
    }
    @Test public void exactRowRecheckRequiresTheActualFullNameAndVisibleConfiguredTail() {
        assertTrue(ViewerMatcher.matchesExactRow(NAME, SUFFIX, NAME, "身份证：33****1234"));
        assertTrue(ViewerMatcher.matchesExactRow(" " + NAME + " ", " 12x ", " " + NAME + " ", "尾号：12X"));
        assertFalse(ViewerMatcher.matchesExactRow(NAME, SUFFIX, OTHER, "**1234"));
        assertFalse(ViewerMatcher.matchesExactRow(NAME, SUFFIX, NAME, "**5678"));
        assertFalse(ViewerMatcher.matchesExactRow(NAME, SUFFIX, NAME, "**123456"));
    }
    @Test public void exactRowRecheckCannotInventMissingMaskedOrShortTailEvidence() {
        for (String document : List.of("", "****", "**34", "1234****", "证件1234/5678", "**12 34"))
            assertFalse(document, ViewerMatcher.matchesExactRow(NAME, SUFFIX, NAME, document));
        assertFalse(ViewerMatcher.matchesExactRow(NAME, SUFFIX, NAME, null));
        for (String name : List.of("", "合成*甲", "合成＊甲", "合成\n甲"))
            assertFalse(ViewerMatcher.matchesExactRow(NAME, SUFFIX, name, "**1234"));
    }
    @Test public void exactRowRecheckWithoutConfiguredSuffixOnlyAssertsExactName() {
        assertTrue(ViewerMatcher.matchesExactRow(NAME, "", NAME, ""));
        assertTrue(ViewerMatcher.matchesExactRow(NAME, null, NAME, null));
        // Each row can match individually while the complete-list matcher correctly rejects ambiguity.
        assertTrue(ViewerMatcher.matchesExactRow(NAME, "", NAME, "**5678"));
        uncertain("ambiguous_viewer_match", ViewerMatcher.match(NAME, "", List.of(
                row(0, NAME, "**1234", false), row(1, NAME, "**5678", false)), true));
    }
    @Test public void exactRowRecheckRejectsInvalidConfigAndDoesNotNormalizeNamesFuzzily() {
        for (String suffix : List.of("1", "1234567", "**1234", "12 34"))
            assertFalse(ViewerMatcher.matchesExactRow(NAME, suffix, NAME, "**1234"));
        assertFalse(ViewerMatcher.matchesExactRow(null, SUFFIX, NAME, "**1234"));
        assertFalse(ViewerMatcher.matchesExactRow("合成*甲", SUFFIX, "合成*甲", "**1234"));
        assertFalse(ViewerMatcher.matchesExactRow("合成 测试甲", SUFFIX, NAME, "**1234"));
        assertFalse(ViewerMatcher.matchesExactRow("SYNTHETIC", "", "Synthetic", ""));
    }
}
