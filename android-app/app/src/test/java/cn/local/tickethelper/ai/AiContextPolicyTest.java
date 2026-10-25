package cn.local.tickethelper.ai;
import cn.local.tickethelper.core.*;
import java.time.Instant;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class AiContextPolicyTest {
    private MonitorConfig old() { return new MonitorConfig("旧演出", "旧城市", "2030-01-01", "19:30", List.of(1680),1,1680,Instant.parse("2030-01-01T11:30:00Z"),5000,10000,MonitorConfig.Mode.LOCK_ORDER); }
    private AiDraft parse(String body) { return AiDraft.fromResponse("建议先核对\n```json\n{\"ticketpilot_draft\":"+body+"}\n```",old()); }
    @Test public void incompleteRequirementNeverInheritsOldTargetOrOrderMode() {
        AiDraft draft=parse("{\"event\":\"测试演出\"}");assertNotNull(draft);
        assertEquals("",draft.config.city);assertEquals("",draft.config.date);assertTrue(draft.config.prices.isEmpty());
        assertNull(draft.config.deadline);assertEquals(MonitorConfig.Mode.ALERT,draft.config.mode);
    }
    @Test public void reviewedDraftKeepsOnlySupportedFields() {
        AiDraft draft=parse("{\"event\":\"测试演出\",\"city\":\"杭州\",\"date\":\"2030-11-15\",\"time\":\"19:00\",\"prices\":[980,1380],\"quantity\":1,\"maxTotal\":1380}");
        assertNotNull(draft);assertEquals(Instant.parse("2030-11-15T11:00:00Z"),draft.config.deadline);
        assertEquals(List.of(980,1380),draft.config.prices);
    }
    @Test public void draftingAnotherTargetPreservesUserSelectedSubSecondIntervals() {
        MonitorConfig c = old();
        MonitorConfig fast = new MonitorConfig(c.eventKeyword,c.city,c.date,c.time,c.prices,c.quantity,c.maxTotal,
                c.deadline,500,1501,MonitorConfig.Mode.PREPARE);
        AiDraft draft = AiDraft.fromResponse("{\"ticketpilot_draft\":{\"event\":\"新演出\"}}",fast);
        assertNotNull(draft); assertEquals(500,draft.config.minIntervalMs); assertEquals(1501,draft.config.maxIntervalMs);
        assertEquals(MonitorConfig.Mode.PREPARE,draft.config.mode);
    }
    @Test public void untrustedDraftCannotSetActionsIdentityOrAuthorizeOrders() {
        for(String suffix:List.of(",\"mode\":\"LOCK_ORDER\"",",\"armed\":true",",\"viewer\":\"测试人\"",",\"tap\":[1,2]",",\"deadline\":\"2030-01-01\""))
            assertNull(parse("{\"event\":\"测试演出\""+suffix+"}"));
    }
    @Test public void rejectsAmbiguousCoercedOrUnaffordableDrafts() {
        for(String body:List.of("{\"event\":\"测试\",\"event\":\"另一场\"}","{\"event\":\"测试\",\"quantity\":2}",
                "{\"event\":\"测试\",\"quantity\":\"1\"}","{\"event\":\"测试\",\"prices\":[980,980]}",
                "{\"event\":\"测试\",\"prices\":[1380],\"maxTotal\":980}","{\"event\":\"测试\",\"date\":\"2030-02-30\"}")) assertNull(parse(body));
    }
    @Test public void redactsBeforeTruncationAndKeepsPublicPrices() {
        String text=AiRedactor.clean("测试人 13800138000 sk-secret-test-value 邮箱 me@example.com 980元 2026-11-15",List.of("测试人"),300);
        assertFalse(text.contains("测试人"));assertFalse(text.contains("13800138000"));assertFalse(text.contains("sk-secret"));assertFalse(text.contains("me@example.com"));
        assertTrue(text.contains("980元"));assertTrue(text.contains("2026-11-15"));
        assertFalse(AiRedactor.clean("prefix-very-secret-value",List.of("very-secret-value"),12).contains("very-secret"));
    }
    @Test public void expandedOpaqueTextKeepsItsTailAndRedactsWholeEmailTokens() {
        String opaque="x".repeat(AiSettings.MAX_QUESTION_CHARS);
        String input=opaque+" END 说明：first.last+tag@example.co.uk；[next@test.example] API Key: fixture-secret-value";
        String clean=AiRedactor.clean(input,List.of(),Integer.MAX_VALUE);
        assertTrue(clean.startsWith(opaque+" END"));
        assertTrue(clean.contains("[邮箱已隐藏]"));
        assertFalse(clean.contains("first.last"));assertFalse(clean.contains("next@test"));
        assertFalse(clean.contains("fixture-secret-value"));assertFalse(clean.contains("已截短"));
        assertEquals("[邮箱已隐藏]",AiRedactor.clean(opaque+"@example.com",List.of(),Integer.MAX_VALUE));
    }
    @Test public void escapedMemberNamesCannotHideDuplicateFieldsOnAndroid() {
        assertNull(parse("{\"event\":\"测试\",\"" + "\\" + "u0065vent\":\"另一场\"}"));
        assertNull(parse("{\"" + "\\" + "u0065vent\":\"另一场\"}"));
        assertNull(AiDraft.fromResponse("{\"ticketpilot_draft\":{\"event\":\"测试\"}} trailing",old()));
    }
    private UiItem item(String text) { return new UiItem(text,1,2,30,40,true,true,false,UiItem.Source.NODE,"private/resource-id"); }
    @Test public void pageSummaryIsAnAllowlistNotAWholeScreenExport() {
        Observation frame=new Observation("cn.damai",List.of(item("场次"),item("票档"),item("内场980元"),item("缺货"),item("私人姓名 未知备注"),item("13800138000")),123);
        String text=AiPageSummary.summarize(frame);assertTrue(text.contains("980元"));assertFalse(text.contains("私人姓名"));assertFalse(text.contains("13800138000"));assertFalse(text.contains("resource-id"));
        assertTrue(text.contains("不能将独立缺货标签对应到某个票价"));
    }
    @Test public void checkoutAndCaptchaNeverIncludePublicOrPrivatePageLabels() {
        for(String protectedLabel:List.of("确认购买","观演人","验证码")) {
            String text=AiPageSummary.summarize(new Observation("cn.damai",List.of(item("场次"),item("票档"),item("980元"),item(protectedLabel),item("测试人")),123));
            assertFalse(text.contains("980元"));assertFalse(text.contains("测试人"));
        }
        assertTrue(AiPageSummary.summarize(new Observation("other.app",List.of(item("980元")),123)).contains("未读取到大麦"));
    }
}
