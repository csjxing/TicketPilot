package cn.local.tickethelper.core;

/** Presentation only: the service still independently validates every start. */
public enum HomeStep {
    RUNNING("请切回大麦，保持页面可见", "暂停监控"),
    SELECT("先选一场想看的演出", "选择演出"),
    REVIEW_IMPORTED("请先核对这份目标", "重新选择并核对"),
    REPAIR_TARGET("这份目标还不能开始", "检查目标"),
    SETUP("还有必要设置未完成", "完成设置"),
    CHANGE_MODE("自动锁单当前尚未开放", "选择可用方式"),
    READY("准备好了，可以开始", "开始监控"),
    SAVE_AND_START("有修改待保存，开始前会核对", "保存并开始");

    public final String title, button;
    HomeStep(String title, String button) { this.title = title; this.button = button; }

    public static HomeStep resolve(boolean running, boolean hasTarget, boolean imported,
                                   boolean targetValid, boolean setupReady, boolean unsupportedMode,
                                   boolean unsaved) {
        if (running) return RUNNING;
        if (imported) return REVIEW_IMPORTED;
        if (!hasTarget) return setupReady ? SELECT : SETUP;
        // Unsaved values are validated by the ordinary start form, never inferred from saved data.
        if (!targetValid && !unsaved) return REPAIR_TARGET;
        if (!setupReady) return SETUP;
        if (unsupportedMode) return CHANGE_MODE;
        return unsaved ? SAVE_AND_START : READY;
    }
}
