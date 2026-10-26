package cn.local.tickethelper.core;

/**
 * Plain-language presentation of the next user step. This class never grants permission,
 * validates a purchase, clicks a page, or starts a service. Callers must re-check live facts
 * and authorization when an action is invoked. Order facts must come from verified current
 * evidence, never a model reply, a price label, or a historical status string.
 */
public final class PilotJourney {
    private PilotJourney() { }

    public enum Stage {
        SETUP, SELECT_EVENT, REVIEW_TARGET, REPAIR_TARGET, CHOOSE_BEHAVIOR,
        READY, WAITING_FOR_DAMAI, RUNNING, PAUSED, DAMAI_REVIEW, CHECKOUT, ORDER_REVIEW, PENDING_PAYMENT
    }

    public enum Action {
        SETUP, SELECT_EVENT, CHANGE_MODE, START, OPEN_DAMAI, PAUSE, RESOLVE_PAUSE, REVIEW_ORDER
    }

    public enum OrderState { NONE, NEEDS_REVIEW, PENDING_PAYMENT_VERIFIED }

    /** A fresh local UI snapshot. Defaults deliberately cannot describe a ready task. */
    public static final class Input {
        public boolean running, waitingForDamai, hasTarget, targetValid;
        public boolean importedPendingReview, deviceReady, unsupportedMode, paused, checkoutReached;
        public OrderState orderState = OrderState.NONE;
        /** Persisted navigation guidance; it cannot establish current stock or order success. */
        public MonitorStopState stopState = MonitorStopState.NONE;
    }

    public static final class Result {
        public final Stage stage;
        public final Action action;
        /** 1: select a target, 2: prepare and review, 3: run or continue in Damai. */
        public final int step;
        public final String eyebrow, title, detail, button, outcome;

        private Result(Stage stage, Action action, int step, String eyebrow, String title,
                       String detail, String button, String outcome) {
            this.stage = stage; this.action = action; this.step = step;
            this.eyebrow = eyebrow; this.title = title; this.detail = detail;
            this.button = button; this.outcome = outcome;
        }
    }

    public static Result resolve(Input input) {
        Input state = input == null ? new Input() : input;
        // An unresolved submission cannot be turned into a new start by changing preferences.
        if (state.orderState == OrderState.NEEDS_REVIEW)
            return result(Stage.ORDER_REVIEW, Action.REVIEW_ORDER, 3, "先核查订单", "先看大麦里的订单状态",
                    "已有提交记录或结果尚不明确，不会重复创建订单。", "去大麦核查订单", "在大麦确认是否有待付款订单；这里不会自动重试。");
        if (state.orderState == OrderState.PENDING_PAYMENT_VERIFIED)
            return result(Stage.PENDING_PAYMENT, Action.OPEN_DAMAI, 3, "待你付款", "待付款订单已确认",
                    "请按大麦订单页显示的期限完成付款。", "去大麦查看并付款", "只打开大麦；付款由你亲自完成。");
        if (state.running && state.waitingForDamai)
            return result(Stage.WAITING_FOR_DAMAI, Action.OPEN_DAMAI, 3, "等待你切回大麦", "现在回到目标票档页",
                    "助手已启动，正在等大麦页面出现在前台；还不能说已查询库存。", "回到大麦", "保持目标票档页可见，助手才会读取和检查。");
        if (state.running)
            return result(Stage.RUNNING, Action.PAUSE, 3, "正在运行", "正在检查大麦页面",
                    "保留大麦在前台。页面有变化或需要处理时会提醒你。", "暂停本次任务", "停止继续检查和发出新操作，不会自动恢复。");
        if (state.stopState == MonitorStopState.REVIEW_ORDER)
            return result(Stage.ORDER_REVIEW, Action.REVIEW_ORDER, 3, "先核查订单", "上次订单操作需要核实",
                    "这里只保留了上次停止的记录，不能证明现在有待付款订单。", "去大麦核查订单", "在大麦核对真实订单与期限，不自动重试提交。");
        if (state.stopState == MonitorStopState.REVIEW_DAMAI)
            return result(Stage.DAMAI_REVIEW, Action.OPEN_DAMAI, 3, "需要你核实", "回到大麦看看当前状态",
                    "上次因票档信号或订单相关页面而停止；这条记录不代表现在有票或已锁票。", "去大麦核实并继续", "核对当前页面后由你决定下一步，不自动提交或付款。");
        if (state.checkoutReached)
            return result(Stage.CHECKOUT, Action.OPEN_DAMAI, 3, "轮到你确认", "选票已到确认购买页",
                    "还未证明锁票，请在大麦核对实名、金额和提交结果。", "去大麦继续", "由你继续核对并提交；进入确认页本身没有创建订单。");
        if (!state.deviceReady)
            return result(Stage.SETUP, Action.SETUP, state.hasTarget ? 2 : 1, "一次准备", "先让助手看见大麦",
                    "检查页面读取、消息提醒和点击连接；已完成的项目会自动标记。", "完成手机准备", "准备好后回到这里，继续选择演出或开始。");
        if (state.importedPendingReview)
            return result(Stage.REVIEW_TARGET, Action.SELECT_EVENT, 1, "核对真实场次", "把这份需求变成可用计划",
                    "这份内容还是草稿。需要从大麦重新点选演出、日期和票价。", "从大麦核对选择", "读取后由你确认保存，不会自动开始或下单。");
        if (!state.hasTarget)
            return result(Stage.SELECT_EVENT, Action.SELECT_EVENT, 1, "第 1 步 · 选演出", "先选一场想看的演出",
                    "输入艺人或演出名，再选城市、日期和接受的票价。", "选择演出", "从真实大麦读取选择；不需要手填一整张表。");
        if (!state.targetValid)
            return result(Stage.REPAIR_TARGET, Action.SELECT_EVENT, 1, "更新计划", "这份演出信息需要更新",
                    "可能缺少场次、票价或时间已经过期，重新选择即可核对。", "重新选择演出", "用真实页面更新目标，不沿用无效日期或旧票价。");
        if (state.unsupportedMode)
            return result(Stage.CHOOSE_BEHAVIOR, Action.CHANGE_MODE, 2, "第 2 步 · 定条件", "选择有票后怎么继续",
                    "当前自动锁票尚未完成，可选择提醒或选票到确认页。", "选择可用方式", "只保存你选的方式，稍后再由你明确开始。");
        if (state.paused || state.stopState == MonitorStopState.NEEDS_ATTENTION)
            return result(Stage.PAUSED, Action.RESOLVE_PAUSE, 2, "需要你处理", "先解决这次暂停",
                    "看清暂停原因，处理后重新核对；不会自行重复点击。", "查看处理步骤", "领航员会显示当前问题与可执行的下一步，不自动恢复任务。");
        if (state.stopState == MonitorStopState.PAUSED)
            return result(Stage.PAUSED, Action.START, 2, "已暂停", "任务已停下，随时可以重新核对",
                    "没有继续检查或发出操作。重新开始前，再核对计划和大麦页面。", "核对后重新开始", "先展示本次计划，由你确认后才启动。");
        return result(Stage.READY, Action.START, 2, "第 2 步 · 核对计划", "计划已备好，核对后开始",
                "确认场次、接受票价和有票后的动作，再去大麦打开目标票档页。", "核对并开始", "先展示本次计划，由你确认后才启动。");
    }

    private static Result result(Stage stage, Action action, int step, String eyebrow, String title,
                                 String detail, String button, String outcome) {
        return new Result(stage, action, step, eyebrow, title, detail, button, outcome);
    }
}
