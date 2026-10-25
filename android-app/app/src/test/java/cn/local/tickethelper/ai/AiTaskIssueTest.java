package cn.local.tickethelper.ai;

import org.junit.Test;
import static org.junit.Assert.*;

public class AiTaskIssueTest {
    @Test public void privateStatusCannotBeForwarded() {
        String result = AiTaskIssue.describe("订单测试姓名，手机号13800138000，证件123456789012345678，已暂停");
        assertTrue(result.contains("订单相关")); assertFalse(result.contains("测试姓名")); assertFalse(result.contains("13800138000"));
        assertFalse(result.contains("123456789012345678"));
    }
    @Test public void knownBlockMapsToUsefulConstant() {
        assertTrue(AiTaskIssue.describe("手机已锁屏或熄屏，任务已暂停。").contains("锁屏"));
        assertTrue(AiTaskIssue.describe("当前不在大麦页面，任务已暂停。").contains("前台"));
        assertTrue(AiTaskIssue.describe("手机本地调试输入已断开").contains("点击服务"));
        assertTrue(AiTaskIssue.describe("订单提交结果无法确认").contains("不能重复提交"));
    }
    @Test public void unknownTextIsNotIncludedAsContext() {
        assertEquals("", AiTaskIssue.describe(null));
        assertEquals("", AiTaskIssue.describe("unclassified arbitrary secret"));
    }
}
