package cn.local.tickethelper.catalog;

import org.junit.Test;
import static org.junit.Assert.*;

public class CatalogGuidanceTest {
    @Test public void manualEntryNamesTheOneRequiredTap() {
        CatalogGuidance guide = CatalogGuidance.busy("请在大麦点一次“立即预订”打开场次页；助手会继续读取。");
        assertEquals("点一下「立即预订」", guide.instruction);
        assertTrue(guide.after.contains("继续读取"));
    }
    @Test public void waitingForAppDoesNotClaimReadingAlreadySucceeded() {
        CatalogGuidance guide = CatalogGuidance.busy("请返回大麦；助手将在 20 秒内读取当前页面。");
        assertTrue(guide.instruction.contains("切回"));
        assertTrue(guide.after.contains("尝试读取"));
        assertFalse(guide.title.contains("已完成"));
    }
    @Test public void completedEntryDetectionDoesNotKeepAskingForBookingTap() {
        CatalogGuidance guide = CatalogGuidance.busy("已检测到所选演出的场次页，正在核实场次…");
        assertFalse(guide.instruction.contains("立即预订"));
        assertTrue(guide.instruction.contains("保持"));
    }
    @Test public void unrecognizedPhaseUsesGenericInstructionsWithoutEchoingText() {
        CatalogGuidance guide = CatalogGuidance.busy("unknown private status");
        assertFalse((guide.title + guide.instruction + guide.after + guide.button).contains("private"));
        assertTrue(guide.instruction.contains("切回大麦"));
    }
}
