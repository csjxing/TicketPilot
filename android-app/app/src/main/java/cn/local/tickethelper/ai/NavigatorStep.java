package cn.local.tickethelper.ai;

import cn.local.tickethelper.core.MonitorStopState;

/** Navigation is selected from verified local state, never from model-generated instructions. */
public enum NavigatorStep {
    SETUP("手机准备", "先把手机准备好", "允许读取大麦页面并连接点击服务。完成后，就能在这里选择演出。", "去完成手机准备", "setup"),
    EVENT("选演出", "先找到想看的演出", "搜索艺人或演出名，再从真实大麦结果里选择城市和项目。", "去选演出", "catalog"),
    SESSION("选场次", "选一个方便的时间", "点选日期和开演时间，下一步会读出这一场的票价。", "去选场次", "catalog"),
    PRICE("选票价", "告诉我哪些票价可以", "勾选你接受的票价，可以多选；核对后保存到计划。", "去选票价", "catalog"),
    HANDOFF("读取中", "继续刚才的选择", "回到选择页面查看当前提示。需要切到大麦时，按页面提示完成后再回来。", "继续当前选择", "catalog"),
    PLAN("核对计划", "选择已保存，核对后开始", "查看演出、日期、票价和有票后的动作；按计划页提示打开大麦后再开始。", "去核对并开始", "review"),
    CATALOG_ISSUE("选票遇到问题", "先完成这次页面读取", "回到选择页面查看具体提示，按提示处理后重新读取。已保存的计划不会被替换。", "回到选择，处理问题", "catalog"),
    CONDITIONS("购票方式", "先选择可用的购票方式", "自动锁票尚未就绪。先选择有票提醒或帮你选到确认页，再核对开始。", "去选择购票方式", "preferences"),
    VERIFY_DAMAI("核实大麦页面", "上次结果需要你核实", "曾记录到有票信号或确认页，这不代表现在仍有票或已锁票。回到计划，再打开大麦核实当前页面。", "去核实这次结果", "plan"),
    VERIFY_ORDER("核查订单", "先确认是否已有订单", "存在订单相关记录，结果需要在大麦核实。不会通过这里重新开始或重复提交。", "去核查已有订单", "plan"),
    ISSUE("处理暂停", "先处理这次暂停", "查看具体原因与处理步骤，处理完成后由你核对并重新开始，不会自动恢复。", "查看原因与处理步骤", "recovery");

    public final String phase, title, instruction, actionLabel, destination;
    NavigatorStep(String phase, String title, String instruction, String actionLabel, String destination) {
        this.phase = phase; this.title = title; this.instruction = instruction;
        this.actionLabel = actionLabel; this.destination = destination;
    }

    public static NavigatorStep resolve(boolean phoneReady, boolean targetReady, boolean targetNeedsReview,
                                        String catalogStage, boolean catalogFresh, boolean unsupportedMode,
                                        MonitorStopState stopState) {
        MonitorStopState stopped = stopState == null ? MonitorStopState.NEEDS_ATTENTION : stopState;
        // Past signals are navigation warnings, never evidence of current stock or successful checkout.
        if (stopped == MonitorStopState.REVIEW_ORDER) return VERIFY_ORDER;
        if (stopped == MonitorStopState.REVIEW_DAMAI) return VERIFY_DAMAI;
        if (!phoneReady) return SETUP;
        if (catalogFresh) {
            if ("BUSY".equals(catalogStage)) return HANDOFF;
            if ("BLOCKED".equals(catalogStage)) return CATALOG_ISSUE;
            if ("PROJECTS".equals(catalogStage)) return EVENT;
            if ("SESSIONS".equals(catalogStage)) return SESSION;
            if ("TIERS".equals(catalogStage)) return PRICE;
        }
        // A pending AI draft may progress through real catalog steps, but never skip them to start.
        if (targetNeedsReview) return EVENT;
        if (targetReady) {
            if (unsupportedMode) return CONDITIONS;
            return stopped == MonitorStopState.NEEDS_ATTENTION ? ISSUE : PLAN;
        }
        return EVENT;
    }
}
