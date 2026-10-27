package cn.local.tickethelper.core;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class ReadStabilityTest {
    private UiItem node(String text, int left, boolean enabled, boolean selected) {
        return new UiItem(text, left, 20, left + 80, 60, enabled, true, selected,
                UiItem.Source.NODE, "cn.damai:id/ll_perform_item");
    }
    @Test public void onlyContentNoiseInSameWindowCanRequestVisualRecheck() {
        assertTrue(ReadStability.mayRecheck(10, 15, 3, 3, 8, 8, 800));
        assertFalse(ReadStability.mayRecheck(10, 10, 3, 3, 8, 8, 800));
        assertFalse(ReadStability.mayRecheck(10, 15, 3, 4, 8, 8, 800));
        assertFalse(ReadStability.mayRecheck(10, 15, 3, 3, 8, 9, 800));
        assertFalse(ReadStability.mayRecheck(10, 15, 3, 3, -1, -1, 800));
    }
    @Test public void visualRecheckNeverRenewsOriginalLifetime() {
        assertTrue(ReadStability.mayRecheck(10, 11, 1, 1, 2, 2, 2999));
        assertFalse(ReadStability.mayRecheck(10, 11, 1, 1, 2, 2, 3000));
        assertFalse(ReadStability.mayRecheck(10, 11, 1, 1, 2, 2, -1));
    }
    @Test public void allNativeFactsMustMatchIncludingStockPriceGeometryAndSelection() {
        List<UiItem> initial = List.of(node("1680元 缺货", 20, true, false));
        assertTrue(ReadStability.sameNative(initial, List.of(node("1680元 缺货", 20, true, false))));
        assertFalse(ReadStability.sameNative(initial, List.of(node("1680元 有票", 20, true, false))));
        assertFalse(ReadStability.sameNative(initial, List.of(node("1380元 缺货", 20, true, false))));
        assertFalse(ReadStability.sameNative(initial, List.of(node("1680元 缺货", 21, true, false))));
        assertFalse(ReadStability.sameNative(initial, List.of(node("1680元 缺货", 20, false, false))));
        assertFalse(ReadStability.sameNative(initial, List.of(node("1680元 缺货", 20, true, true))));
    }
    @Test public void newVerificationNodeAndMissingOrTruncatedTreesRejectRecovery() {
        UiItem price = node("1680元", 20, true, false);
        assertFalse(ReadStability.sameNative(List.of(price), List.of(price, node("安全验证", 40, true, false))));
        assertFalse(ReadStability.sameNative(List.of(), List.of()));
        assertFalse(ReadStability.sameNative(null, List.of(price)));
        List<UiItem> truncated = new ArrayList<>();
        for (int i = 0; i < 500; i++) truncated.add(price);
        assertFalse(ReadStability.sameNative(truncated, truncated));
    }
    @Test public void retriesAreBoundedByAttemptsAndElapsedTimeAndResetAfterVerifiedRead() {
        ReadStability policy = new ReadStability();
        for (int i = 0; i < 11; i++) assertTrue(policy.retry(1000 + i * 500));
        assertFalse(policy.retry(6500));
        assertEquals(12, policy.attempts());
        assertTrue(policy.delayMs() <= 1500);
        policy.reset(); assertEquals(0, policy.attempts());
        assertTrue(policy.retry(20_000)); assertFalse(policy.retry(35_000));
        policy.reset(); assertTrue(policy.retry(50_000)); assertFalse(policy.retry(49_999));
    }
}
