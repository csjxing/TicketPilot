package cn.local.tickethelper.core;

/** Pure local scheduling only. It never clicks, refreshes a server, or authorizes an order. */
public final class SaleTiming {
    public static final long EVENT_READ_DELAY_MS = 80, OPENING_READ_DELAY_MS = 120,
            LIVE_READ_DELAY_MS = 250, FORWARD_READ_DELAY_MS = 100, OPENING_BURST_MS = 10_000;
    public enum Phase { WAITING_FOR_SALE, OPENING, MONITORING, STOPPED }
    public enum StopReason { NONE, INVALID_CONFIGURATION, DEADLINE_REACHED }
    public static final class Tick {
        public final Phase phase;
        public final StopReason stopReason;
        public final long untilSaleMs, remainingMs, nextWakeMs, stopAtMs;
        private Tick(Phase phase, StopReason reason, long untilSaleMs, long remainingMs, long wake, long stopAtMs) {
            this.phase=phase;stopReason=reason;this.untilSaleMs=untilSaleMs;this.remainingMs=remainingMs;
            nextWakeMs=wake;this.stopAtMs=stopAtMs;
        }
        public boolean mayReadOfficialPage() { return phase==Phase.OPENING || phase==Phase.MONITORING; }
        public String statusText() {
            if (phase==Phase.STOPPED) return stopReason==StopReason.INVALID_CONFIGURATION
                    ? "时间设置无效，监控未启动。" : "持续监控时间已到，任务已停止。";
            if (phase==Phase.WAITING_FOR_SALE) return "等待设定开售时间 · 还有 "+duration(untilSaleMs)+"；开售后开始核查。";
            return (phase==Phase.OPENING?"已到设定开售时间，正在核查官方页面。":"持续关注回流票。")
                    +"剩余 "+duration(remainingMs)+"；有票后按已授权流程继续。";
        }
    }

    private final MonitorConfig config;
    private final long startedElapsedMs, stopAtMs, stopAfterElapsedMs;
    private final boolean valid;

    public SaleTiming(MonitorConfig config, long startedWallMs, long startedElapsedMs) {
        this.config=config;this.startedElapsedMs=startedElapsedMs;
        long stop=0, elapsedStop=0;boolean valid=false;
        try {
            if (config!=null && startedWallMs>0 && startedElapsedMs>=0 && config.validate().isEmpty()) {
                stop=effectiveStopAtMs(config,startedWallMs);
                elapsedStop=Math.addExact(startedElapsedMs,Math.max(0,Math.subtractExact(stop,startedWallMs)));
                valid=true;
            }
        } catch (RuntimeException invalid) { /* Invalid times never become an unbounded watch. */ }
        this.valid=valid;stopAtMs=stop;stopAfterElapsedMs=elapsedStop;
    }

    /** Schedule starts at sale time if waiting, otherwise at this explicit monitoring start. */
    public static long effectiveStopAtMs(MonitorConfig config,long startedWallMs) {
        if (config==null || config.deadline==null || config.eventStartsAt()==null || startedWallMs<=0)
            throw new IllegalArgumentException("Valid configured times required");
        long stop=Math.min(config.deadline.toEpochMilli(),config.eventStartsAt().toEpochMilli());
        if (config.followUpMinutes>0) {
            long start=Math.max(startedWallMs,config.saleAt==null?startedWallMs:config.saleAt.toEpochMilli());
            long windowEnd=Math.addExact(start,Math.multiplyExact((long)config.followUpMinutes,60_000L));
            stop=Math.min(stop,windowEnd);
        }
        return stop;
    }

    public Tick tick(long nowWallMs,long nowElapsedMs) {
        if (!valid || nowElapsedMs<startedElapsedMs || nowWallMs<=0)
            return new Tick(Phase.STOPPED,StopReason.INVALID_CONFIGURATION,0,0,0,stopAtMs);
        long wallRemaining=positiveDifference(stopAtMs,nowWallMs),
                elapsedRemaining=positiveDifference(stopAfterElapsedMs,nowElapsedMs);
        long remaining=Math.min(wallRemaining,elapsedRemaining);
        if (remaining==0) return new Tick(Phase.STOPPED,StopReason.DEADLINE_REACHED,0,0,0,stopAtMs);
        long until=config.saleAt==null?0:positiveDifference(config.saleAt.toEpochMilli(),nowWallMs);
        if (until>0) return new Tick(Phase.WAITING_FOR_SALE,StopReason.NONE,until,remaining,
                Math.min(remaining,waitWakeMs(until)),stopAtMs);
        boolean opening=config.saleAt!=null && nowWallMs-config.saleAt.toEpochMilli()>=0
                && nowWallMs-config.saleAt.toEpochMilli()<OPENING_BURST_MS;
        Phase phase=opening?Phase.OPENING:Phase.MONITORING;
        return new Tick(phase,StopReason.NONE,0,remaining,
                Math.min(remaining,opening?OPENING_READ_DELAY_MS:LIVE_READ_DELAY_MS),stopAtMs);
    }

    /** A timer can wake exactly at the boundary; these waits perform no screenshots or requests. */
    public static long waitWakeMs(long untilSaleMs) {
        if (untilSaleMs<=0) return 0;
        long interval=untilSaleMs>60_000?1000:untilSaleMs>5000?250:untilSaleMs>1000?100:untilSaleMs>100?50:10;
        return Math.min(untilSaleMs,interval);
    }
    public static long eventReadDelayMs() { return EVENT_READ_DELAY_MS; }
    public static long afterForwardInputReadDelayMs() { return FORWARD_READ_DELAY_MS; }
    /** ActionPacing must already have verified a fresh forward step; this adds no new authority. */
    public static long actionWaitMs(boolean mayDispatchUnderExistingGuards,long nowElapsedMs,long nextActionAtMs) {
        return mayDispatchUnderExistingGuards?0:Math.max(1,positiveDifference(nextActionAtMs,nowElapsedMs));
    }
    private static long positiveDifference(long future,long now) {
        if (future<=now)return 0;
        try {return Math.subtractExact(future,now);}catch(ArithmeticException overflow){return Long.MAX_VALUE;}
    }
    private static String duration(long millis) {
        long seconds=millis/1000+(millis%1000==0?0:1);
        if(seconds>=86400)return seconds/86400+"天"+seconds%86400/3600+"时";
        if(seconds>=3600)return seconds/3600+"时"+seconds%3600/60+"分";
        return seconds>=60?seconds/60+"分"+seconds%60+"秒":seconds+"秒";
    }
}
