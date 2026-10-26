package cn.local.tickethelper.catalog;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class CatalogOverlayDraftTest {
    @Test public void aNewReadInvalidatesEarlierChoicesAndReview() {
        CatalogOverlayDraft draft = new CatalogOverlayDraft(2);
        draft.replace(7, 1000, List.of("1680")); draft.select("1680", true);
        assertTrue(draft.beginReview(2000, 7));
        draft.replace(8, 2000, List.of("1680"));
        assertTrue(draft.keys().isEmpty()); assertFalse(draft.canSave(2100, 8));
        assertEquals(2, draft.quantity());
    }
    @Test public void staleOrChangedSnapshotCannotBeReviewedOrSaved() {
        CatalogOverlayDraft draft = chosen();
        assertFalse(draft.beginReview(2000, 2));
        assertFalse(draft.beginReview(301001, 1));
        assertFalse(draft.beginReview(999, 1));
        assertTrue(draft.beginReview(301000, 1));
        assertFalse(draft.canSave(301001, 1));
    }
    @Test public void quantityOrPriceChangesRequireReviewAgain() {
        CatalogOverlayDraft draft = chosen(); assertTrue(draft.beginReview(1500, 1));
        draft.setQuantity(2); assertFalse(draft.canSave(1500, 1));
        assertTrue(draft.beginReview(1500, 1));
        draft.select("980", true); assertFalse(draft.canSave(1500, 1));
    }
    @Test public void unavailableKeysAndEmptySelectionNeverProduceAReview() {
        CatalogOverlayDraft draft = new CatalogOverlayDraft(1); draft.replace(1, 1000, List.of("980"));
        draft.select("forged", true); assertTrue(draft.keys().isEmpty());
        assertFalse(draft.beginReview(1500, 1));
    }
    @Test public void clearingBeforeReadRemovesTheEarlierSavePermission() {
        CatalogOverlayDraft draft = chosen(); draft.beginReview(1500, 1); draft.clear();
        assertFalse(draft.canSave(1600, 1)); assertTrue(draft.keys().isEmpty());
    }
    @Test public void changedAllowedPricesDiscardAnEarlierSelection() {
        CatalogOverlayDraft draft = chosen(); draft.beginReview(1500, 1);
        draft.replace(1, 1000, List.of("980"));
        assertTrue(draft.keys().isEmpty()); assertFalse(draft.canSave(1600, 1));
    }
    @Test(expected = IllegalArgumentException.class) public void unsupportedQuantityIsRejected() {
        new CatalogOverlayDraft(1).setQuantity(3);
    }
    private CatalogOverlayDraft chosen() {
        CatalogOverlayDraft draft = new CatalogOverlayDraft(1); draft.replace(1, 1000, List.of("1680", "980"));
        draft.select("1680", true); return draft;
    }
}
