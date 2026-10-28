package cn.local.tickethelper.core;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic public facts test negative session precedence; they are not live stock evidence. */
public final class SessionUnavailableTest {
    private final long now = Instant.parse("2026-10-29T06:00:00Z").toEpochMilli();
    private final UiItem confirm = node("确定", 1000, true, false);

    private MonitorConfig config() {
        return new MonitorConfig("演出测试", "长沙", "2026-11-06", "19:00", List.of(1680), 2, 3360,
                Instant.parse("2026-11-06T11:00:00Z"), 3000, 6000, MonitorConfig.Mode.PREPARE);
    }
    private UiItem node(String text, int y, boolean clickable, boolean selected) {
        return new UiItem(text, 30, y, 600, y + 50, true, clickable, selected, UiItem.Source.NODE);
    }
    private List<UiItem> items(String tier) {
        return new ArrayList<>(List.of(node("长沙 演出测试", 100, false, false),
                node("2026-11-06 19:00 无票", 200, false, false),
                node("2026-11-08 19:00 无票", 270, false, false), node("票档", 350, false, false),
                node(tier, 500, true, false)));
    }
    private Observation.VerifiedSelection unavailable(long observedAt, String date, String time) {
        return new Observation.VerifiedSelection(observedAt, date, time, null, null, null, null, null, true);
    }
    private Observation.VerifiedSelection unavailable() { return unavailable(now, "2026-11-06", "19:00"); }
    private Decision evaluate(List<UiItem> items, Observation.VerifiedSelection evidence, long timestamp, long last) {
        return new DecisionEngine(Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC))
                .evaluate(config(), new Observation("cn.damai", items, timestamp, evidence), last);
    }

    @Test public void selectedUnavailableSessionAmongMultipleDatesAllowsOnlyPacedReturn() {
        List<UiItem> items = items("1680元 缺货登记");
        Decision result = evaluate(items, unavailable(), now, 0);
        assertEquals(Decision.State.OUT_OF_STOCK, result.state); assertEquals(Decision.Action.BACK, result.action);
        assertEquals("selected_session_explicitly_unavailable", result.reason); assertNull(result.target);
        assertTrue(result.selectionEvidence.sessionUnavailable);
        result = evaluate(items, unavailable(), now, now - 2999);
        assertEquals(Decision.State.OUT_OF_STOCK, result.state); assertEquals(Decision.Action.NONE, result.action);
        result = evaluate(items, unavailable(), now, now - 3000);
        assertEquals(Decision.Action.BACK, result.action);
    }

    @Test public void negativeDateBlocksPositiveTrialSelectedAndConfirmPricePaths() {
        for (String tier : List.of("1680元", "1680元 有票")) {
            List<UiItem> items = items(tier);
            items.set(4, node(tier, 500, true, true));
            items.add(node("购买数量 2 张", 700, false, false));
            items.add(node("合计 ¥3360", 800, false, false)); items.add(confirm);
            for (Observation.VerifiedSelection evidence : List.of(unavailable(),
                    new Observation.VerifiedSelection(now, "2026-11-06", "19:00", 1680, 2, 3360, confirm, null, true))) {
                Decision result = evaluate(items, evidence, now, now - 1);
                assertEquals(Decision.State.OUT_OF_STOCK, result.state);
                assertEquals(Decision.Action.NONE, result.action); assertNull(result.target);
            }
        }
    }

    @Test public void unavailableSessionDoesNotRequireReadableTargetPriceButStillRequiresSkuScope() {
        List<UiItem> items = items("票价尚未识别");
        assertEquals(Decision.State.OUT_OF_STOCK, evaluate(items, unavailable(), now, 0).state);
        items.removeIf(item -> item.text.equals("票档"));
        Decision result = evaluate(items, unavailable(), now, 0);
        assertEquals(Decision.State.UNKNOWN, result.state); assertEquals(Decision.Action.NONE, result.action);
    }

    @Test public void wrongDateTimeFrameAndStaleObservationRemainUnknown() {
        for (Observation.VerifiedSelection evidence : List.of(unavailable(now, "2026-11-08", "19:00"),
                unavailable(now, "2026-11-06", "19:30"), unavailable(now - 1, "2026-11-06", "19:00"))) {
            Decision result = evaluate(items("1680元 有票"), evidence, now, 0);
            assertEquals(Decision.State.UNKNOWN, result.state); assertEquals(Decision.Action.NONE, result.action);
            assertEquals("adapter_session_evidence_mismatch", result.reason);
        }
        Decision stale = evaluate(items("1680元 有票"), unavailable(now - 3001, "2026-11-06", "19:00"), now - 3001, 0);
        assertEquals(Decision.State.UNKNOWN, stale.state); assertEquals(Decision.Action.NONE, stale.action);
        assertEquals("stale_or_future_observation", stale.reason);
    }

    @Test public void conflictingNativeSelectionsCannotBeOverruledByNegativeTypedEvidence() {
        List<UiItem> items = items("1680元 有票");
        items.set(1, node("2026-11-06 19:00 无票", 200, false, true));
        items.set(2, node("2026-11-08 19:00 无票", 270, false, true));
        Decision result = evaluate(items, unavailable(), now, 0);
        assertEquals(Decision.State.UNKNOWN, result.state); assertEquals(Decision.Action.NONE, result.action);
        assertEquals("selected_session_not_verified", result.reason);
    }

    @Test public void wrongEventOrCityCannotBecomeStockObservationOrNavigation() {
        for (String title : List.of("长沙 另一个演唱会", "杭州 演出测试")) {
            List<UiItem> items = items("1680元 有票"); items.set(0, node(title, 100, false, false));
            Decision result = evaluate(items, unavailable(), now, 0);
            assertEquals(Decision.State.UNKNOWN, result.state); assertEquals(Decision.Action.NONE, result.action);
        }
    }

    @Test public void oldConstructorsRetainNormalSelectionSemantics() {
        assertFalse(new Observation.VerifiedSelection(now, "2026-11-06", "19:00", null, null, null, null).sessionUnavailable);
        assertFalse(new Observation.VerifiedSelection(now, "2026-11-06", "19:00", null, null, null, null, null).sessionUnavailable);
    }
}
