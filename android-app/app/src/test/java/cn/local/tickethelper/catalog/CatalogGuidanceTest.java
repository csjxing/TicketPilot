package cn.local.tickethelper.catalog;

import org.junit.Test;
import static org.junit.Assert.*;

public class CatalogGuidanceTest {
    @Test public void reservationEntryDoesNotAskToSubmitReservation() {
        CatalogGuidance guide = CatalogGuidance.busy("请在大麦点一次“预约抢票”打开场次页；助手会继续读取。");
        assertEquals("点一下「预约抢票」", guide.instruction);
        assertTrue(guide.after.contains("无需提交"));
    }
    @Test public void unknownEntryDoesNotInventABookingButtonLabel() {
        CatalogGuidance guide = CatalogGuidance.busy("请在大麦点一次页面底部的选票按钮，打开场次页。");
        assertEquals("点页面底部的选票按钮", guide.instruction);
        assertFalse(guide.instruction.contains("立即预订"));
    }
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
    @Test public void automaticReturnDoesNotClaimOriginalPageWasRestored() {
        CatalogGuidance guide = CatalogGuidance.busy("正在切回大麦，准备读取所选场次票档…");
        assertEquals("等待大麦页面出现", guide.instruction);
        assertTrue(guide.after.contains("原页面未保留"));
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
