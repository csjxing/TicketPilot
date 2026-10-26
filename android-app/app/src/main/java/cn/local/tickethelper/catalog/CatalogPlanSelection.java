package cn.local.tickethelper.catalog;

import cn.local.tickethelper.CatalogStore;
import cn.local.tickethelper.core.MonitorConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shared public-plan validation. Catalog choices grant no monitor start or order authorization. */
public final class CatalogPlanSelection {
    public static final long CHOICE_LIFETIME_MS = 300_000;
    private static final ZoneId VENUE_ZONE = ZoneId.of("Asia/Shanghai");
    private CatalogPlanSelection() { }

    public static String problem(CatalogStore.Snapshot snapshot, Set<String> keys, int quantity, long nowMs) {
        return problem(snapshot, keys, quantity, snapshot == null ? "" : snapshot.query, nowMs);
    }

    /** The Activity may still have an explicitly edited query draft; overlay uses the read query. */
    public static String problem(CatalogStore.Snapshot snapshot, Set<String> keys, int quantity,
                                 String query, long nowMs) {
        if (snapshot == null || !"TIERS".equals(snapshot.stage) || snapshot.event == null)
            return "请先选好演出和场次，再选择接受的票价。";
        if (CatalogStore.get().revision != snapshot.revision)
            return "页面信息已更新，请重新核对场次和票价。";
        long age = nowMs - snapshot.readAtMs;
        if (snapshot.readAtMs <= 0 || nowMs < 0 || age < 0 || age > CHOICE_LIFETIME_MS)
            return "这次读取已过期，请重新读取当前场次。";
        if (clean(snapshot.event.city).isEmpty())
            return "还缺少城市。请在大麦展开演出信息，再重新读取。";
        if (snapshot.session == null || !clean(snapshot.session.date).matches("\\d{4}-\\d{2}-\\d{2}")
                || !clean(snapshot.session.time).matches("\\d{2}:\\d{2}"))
            return "还缺少完整日期或时间。请在大麦展开场次，再重新读取。";
        try {
            if (!deadline(snapshot).isAfter(Instant.ofEpochMilli(nowMs)))
                return "该场次时间已过，不能作为监控截止时间，请选择未来场次。";
        } catch (RuntimeException invalid) { return "场次日期或时间无效，请重新读取。"; }
        String requested = clean(query);
        if (requested.length() < 2 || requested.length() > 60
                || CatalogKeyword.fromQuery(snapshot.event.label, requested, snapshot.event.city) == null)
            return "搜索内容中未找到可用于识别的演出名称。请包含标题中实际出现的名称（至少 2 个字符），不要只填城市、日期或“演唱会”。";
        if (quantity < 1 || quantity > 2) return "请选择 1 至 2 张票。";
        if (keys == null || keys.isEmpty()) return "请勾选至少一个接受的票价。";
        if (keys.size() > 20) return "最多选择 20 种接受的票价。";
        Set<Integer> prices = new HashSet<>();
        for (String key : keys) {
            if (clean(key).isEmpty()) return "票价信息已变化，请重新选择。";
            CatalogTier selected = null;
            for (CatalogTier tier : snapshot.tiers) if (key.equals(tier.key)) {
                if (selected != null) return "同一票价出现多个匹配，请重新读取。";
                selected = tier;
            }
            if (selected == null || selected.price <= 0 || selected.price > 100000)
                return "票价信息已变化，请重新选择。";
            if (!prices.add(selected.price)) return "相同金额的票档无法唯一确认，请重新读取。";
        }
        return null;
    }

    public static MonitorConfig buildConfig(CatalogStore.Snapshot snapshot, Set<String> keys, int quantity,
                                            MonitorConfig oldConfig, long nowMs) {
        return buildConfig(snapshot, keys, quantity, snapshot == null ? "" : snapshot.query, oldConfig, nowMs);
    }

    public static MonitorConfig buildConfig(CatalogStore.Snapshot snapshot, Set<String> keys, int quantity,
                                            String query, MonitorConfig oldConfig, long nowMs) {
        String issue = problem(snapshot, keys, quantity, query, nowMs);
        if (issue != null) throw new IllegalArgumentException(issue);
        if (oldConfig == null || oldConfig.mode == null)
            throw new IllegalArgumentException("购票条件不完整，请重新设置。");
        List<Integer> prices = selectedPrices(snapshot, keys);
        int maximum = 0;
        for (int price : prices) maximum = Math.max(maximum, price);
        // An old final-submission grant cannot move to a newly selected target.
        MonitorConfig.Mode mode = oldConfig.mode == MonitorConfig.Mode.ALERT
                ? MonitorConfig.Mode.ALERT : MonitorConfig.Mode.PREPARE;
        MonitorConfig proposed = new MonitorConfig(
                CatalogKeyword.fromQuery(snapshot.event.label, clean(query), snapshot.event.city),
                snapshot.event.city, snapshot.session.date, snapshot.session.time, prices, quantity,
                Math.multiplyExact(maximum, quantity), deadline(snapshot),
                oldConfig.minIntervalMs, oldConfig.maxIntervalMs, mode);
        List<String> errors = proposed.validate();
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", errors));
        return proposed;
    }

    public static List<Integer> selectedPrices(CatalogStore.Snapshot snapshot, Set<String> keys) {
        List<Integer> result = new ArrayList<>();
        if (snapshot == null || keys == null) return List.of();
        for (CatalogTier tier : snapshot.tiers) if (keys.contains(tier.key)) result.add(tier.price);
        return List.copyOf(result);
    }

    public static Instant deadline(CatalogStore.Snapshot snapshot) {
        return LocalDate.parse(snapshot.session.date).atTime(LocalTime.parse(snapshot.session.time))
                .atZone(VENUE_ZONE).toInstant();
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
