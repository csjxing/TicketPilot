package cn.local.tickethelper.core;

import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import cn.local.tickethelper.catalog.CatalogParser;
import cn.local.tickethelper.catalog.CatalogProject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative decisions using visible UI only. Ambiguity never means availability. */
public final class DecisionEngine {
    private static final Pattern PRICE = Pattern.compile("(?:[¥￥]\\s*(\\d{2,6})|(?<![\\d.])(\\d{2,6})\\s*元)");
    private static final Pattern NEGATIVE = Pattern.compile("缺货|售罄|无票|已售完|无库存|暂不可购|暂时无票|不可选|不可购买");
    private static final Pattern BLOCK = Pattern.compile("验证码|滑块验证|安全验证|人机验证|操作频繁|访问频繁|系统繁忙|网络异常|网络错误|网络连接失败|加载失败|请求失败|请登录|登录后|风险验证|拖动滑块|向右滑动|完成验证|身份验证|账户异常|账号异常");
    private final Clock clock;
    private String pendingFingerprint = "", contextFingerprint = "";
    private String initialDetailFingerprint = "";
    private Decision lastDecision;
    private long contextUntilMs, observedAtMs;
    private static final long RETURN_CONTEXT_MS = 30_000;
    private String pendingReturnFingerprint = "", returnFingerprint = "";
    private CatalogProject pendingReturnProject, returnProject;
    private long returnFromMs, returnUntilMs;
    private boolean pendingReentry;
    private boolean detailScrollAvailable;
    private Decision pendingDetailScroll, consumedDetailScroll;
    private int detailScrollUnsentRetries;
    private long detailScrollReobserveAfterMs;

    private final OpeningPriceStrategy openingPrices=new OpeningPriceStrategy();
    public synchronized void beginOpeningStrategy(MonitorConfig config,long now){openingPrices.begin(config,now);}
    /** The service calls this only after its fresh, complete observation passes validation. */
    public synchronized boolean expandAfterOpeningShortage(MonitorConfig config,Decision decision){
        return decision==lastDecision && openingPrices.shortage(config,decision);
    }

    public DecisionEngine() { this(Clock.systemUTC()); }
    public DecisionEngine(Clock clock) { this.clock = clock == null ? Clock.systemUTC() : clock; }

    /** Explicit start grants navigation only; SKU must independently verify the full session. */
    public synchronized void allowInitialDetailNavigation(MonitorConfig config) {
        initialDetailFingerprint = config != null && config.validate().isEmpty() ? fingerprint(config) : "";
    }

    public synchronized void reset() {
        initialDetailFingerprint = "";
        pendingFingerprint = ""; contextFingerprint = ""; contextUntilMs = 0; lastDecision = null;
        pendingReturnFingerprint = ""; pendingReturnProject = null; pendingReentry = false; clearReturnContext();
    }

    /** Call only after the proposed normal navigation/input was actually dispatched. */
    public synchronized void recordAction(Decision decision, long timestampMs) {
        if (decision == lastDecision && decision.action == Decision.Action.OPEN_TICKETS && !pendingFingerprint.isEmpty()) {
            // A return permit opens only this selector; it cannot authorize subsequent SKU facts.
            if (!pendingReentry && !"verified_initial_detail_entry".equals(decision.reason)) {
                contextFingerprint = pendingFingerprint;
                contextUntilMs = timestampMs + 120_000;
            }
            initialDetailFingerprint = "";
            clearReturnContext();
        }
        if (decision != null && decision.action == Decision.Action.BACK) {
            contextFingerprint = ""; contextUntilMs = 0;
            clearReturnContext();
            if (decision == lastDecision && decision.state == Decision.State.OUT_OF_STOCK
                    && pendingReturnProject != null && !pendingReturnFingerprint.isEmpty()) {
                returnFingerprint = pendingReturnFingerprint; returnProject = pendingReturnProject;
                returnFromMs = timestampMs; returnUntilMs = timestampMs + RETURN_CONTEXT_MS;
                detailScrollAvailable = true;
            }
        }
    }

    /**
     * A one-purpose read of a complete public detail identity. The caller separately validates
     * content-only page rebasing; none of this OCR is accepted as click, stock or order evidence.
     */
    public synchronized Decision prepareDetailScroll(MonitorConfig c, Observation o) {
        if (!returnPermitCurrent(c) || !detailScrollAvailable || o == null || !"cn.damai".equals(o.packageName)
                || o.timestampMs < returnFromMs || clock.millis() - o.timestampMs < 0
                || o.timestampMs <= detailScrollReobserveAfterMs
                || clock.millis() - o.timestampMs >= 3000 || o.verifiedSelection != null
                || DetailScrollPolicy.unsafePage(o.items)) return null;
        String all = join(o.items);
        if (BLOCK.matcher(all).find() || !titleMatches(c, o.items) || !hasDate(c, all, o.timestampMs)
                || !CatalogParser.sameProject(returnProject, CatalogParser.projectFromPage(o.items, c.eventKeyword))) return null;
        pendingDetailScroll = new Decision(Decision.State.READY, Decision.Action.SCROLL_DETAIL, null,
                "verified_return_detail_settle", o.timestampMs);
        return pendingDetailScroll;
    }

    public synchronized boolean consumeDetailScroll(Decision decision, MonitorConfig c) {
        if (decision == null || decision != pendingDetailScroll || !detailScrollAvailable
                || !returnPermitCurrent(c) || clock.millis() - decision.observedAtMs < 0
                || clock.millis() - decision.observedAtMs >= 3000) return false;
        detailScrollAvailable = false; consumedDetailScroll = decision; pendingDetailScroll = null;
        return true;
    }

    /** Does not mint/renew a permit or observation, and cannot authorize any other action. */
    public synchronized boolean detailScrollCurrent(Decision decision, MonitorConfig c) {
        return decision != null && decision == consumedDetailScroll && decision.action == Decision.Action.SCROLL_DETAIL
                && returnPermitCurrent(c) && clock.millis() - decision.observedAtMs >= 0
                && clock.millis() - decision.observedAtMs < 3000;
    }

    public synchronized void finishDetailScroll(Decision decision) {
        if (decision == consumedDetailScroll) consumedDetailScroll = null;
    }

    public synchronized boolean detailScrollMayPrepare(MonitorConfig c) {
        return returnPermitCurrent(c) && detailScrollAvailable;
    }

    /** Release only a certainly unexecuted reservation; every retry requires wholly new OCR. */
    public synchronized boolean retryUnsentDetailScroll(Decision decision, MonitorConfig c, boolean success,
                                                        boolean outcomeUnknown, String resultCode,
                                                        InputDispatchPolicy.Rejection rejection, DetailScrollPolicy.FrameCheck frame) {
        if (decision == null || decision != consumedDetailScroll || !returnPermitCurrent(c)
                || detailScrollUnsentRetries >= DetailScrollPolicy.MAX_UNSENT_RETRIES
                || !InputDispatchPolicy.mayReobserveDetailScroll(decision.action, success, outcomeUnknown,
                    resultCode, rejection, frame)) return false;
        detailScrollUnsentRetries++; consumedDetailScroll = null; pendingDetailScroll = null;
        detailScrollReobserveAfterMs = Math.max(clock.millis(), decision.observedAtMs);
        detailScrollAvailable = true; return true;
    }

    private boolean returnPermitCurrent(MonitorConfig c) {
        return c != null && c.validate().isEmpty() && fingerprint(c).equals(returnFingerprint)
                && returnProject != null && clock.millis() >= returnFromMs && clock.millis() <= returnUntilMs
                && clock.millis() < c.deadline.toEpochMilli();
    }

    public synchronized Decision evaluate(MonitorConfig c, Observation o, long lastNavigationMs) {
        pendingFingerprint = ""; pendingReturnFingerprint = ""; pendingReturnProject = null; pendingReentry = false;
        observedAtMs = o == null ? 0 : o.timestampMs;
        if (c == null || !c.validate().isEmpty()) {
            clearReturnContext(); return result(Decision.State.CONFIG_INVALID, "invalid_configuration");
        }
        if (o == null || o.timestampMs <= 0) return result(Decision.State.UNKNOWN, "missing_observation");
        long currentMs = clock.millis();
        if (Math.max(currentMs, o.timestampMs) >= c.deadline.toEpochMilli()) { reset(); return result(Decision.State.EXPIRED, "deadline_reached"); }
        if (currentMs - o.timestampMs > 3000 || o.timestampMs - currentMs > 1000)
            return result(Decision.State.UNKNOWN, "stale_or_future_observation");
        if (LocalDate.parse(c.date).isBefore(Instant.ofEpochMilli(currentMs).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate())) {
            reset(); return result(Decision.State.EXPIRED, "event_date_passed");
        }
        if (!"cn.damai".equals(o.packageName)) { reset(); return result(Decision.State.UNRELATED, "other_application"); }
        String all = join(o.items);
        if (PageFailureSignals.queueBusy(o.items)) { reset(); return result(Decision.State.BLOCKED, "checkout_queue_busy"); }
        if (PageFailureSignals.nativeErrorPage(o.items)) { reset(); return result(Decision.State.BLOCKED, "native_error_page"); }
        if (all.isEmpty()) return result(Decision.State.UNKNOWN, "empty_page");
        if (BLOCK.matcher(all).find()) { reset(); return result(Decision.State.BLOCKED, "verification_login_or_network"); }
        if (contains(all, "提交订单", "立即提交", "待付款", "立即支付", "付款剩余", "支付成功")) {
            reset(); return result(Decision.State.CHECKOUT, "manual_checkout_required");
        }
        // A loading/error shell can expose the order title before any checkout content exists.
        // Do not let leftover SKU evidence make this shell actionable either.
        if (contains(all, "确认购买", "确认订单")) {
            reset(); return result(Decision.State.UNKNOWN, "checkout_content_unverified");
        }
        // Reservation preference pages are catalog-only. Even a complete-looking
        // price/count/amount combination cannot turn their controls into purchase input.
        if (o.items.stream().anyMatch(i -> i.source == UiItem.Source.NODE
                && i.resourceId.equals("cn.damai:id/tv_perform_name") && i.text.trim().equals("预约想看场次"))) {
            reset(); return result(Decision.State.UNKNOWN, "reservation_selection_only");
        }
        Observation.VerifiedSelection verified = o.verifiedSelection;
        if (verified != null && (verified.observedAtMs != o.timestampMs
                || !c.date.equals(verified.date) || !c.time.equals(verified.time)))
            return result(Decision.State.UNKNOWN, "adapter_session_evidence_mismatch");
        String fingerprint = fingerprint(c);
        List<Integer> wantedPrices=openingPrices.prices(c);
        if (!fingerprint.equals(returnFingerprint) || currentMs > returnUntilMs) clearReturnContext();
        boolean titleMatches = titleMatches(c, o.items);
        boolean dateMatches = hasDate(c, all, o.timestampMs);
        boolean timeMatches = all.contains(c.time);
        boolean scopeVisible = titleMatches && dateMatches && timeMatches;
        List<Candidate> allCandidates = candidates(o.items);
        List<Candidate> candidates = new ArrayList<>();
        for (Candidate candidate : allCandidates) if (wantedPrices.contains(candidate.price)) candidates.add(candidate);
        boolean sku = uniqueRegion(o.items, "project_detail_perform_flowlayout") != null || contains(all, "票档", "选择票价", "选择票档") ||
                (!candidates.isEmpty() && contains(all, "购买数量", "数量") && exactButton(o.items, "确定") != null);
        if (!sku) { contextFingerprint = ""; contextUntilMs = 0; }
        if (sku && o.items.stream().anyMatch(i -> contains(i.text, "演唱会", "音乐节", "演出名称")
                && !i.text.contains(c.eventKeyword) && !belongsToVerifiedTitle(c, i, o.items))) {
            reset(); return result(Decision.State.UNKNOWN, "conflicting_event_title");
        }
        boolean inheritedScope = sku && dateMatches && timeMatches && fingerprint.equals(contextFingerprint)
                && o.timestampMs <= contextUntilMs;
        boolean returnScope = !sku && titleMatches && dateMatches
                && (timeMatches || !Pattern.compile("(?<!\\d)[0-2]\\d:[0-5]\\d(?!\\d)").matcher(all).find())
                && fingerprint.equals(returnFingerprint) && returnProject != null
                && o.timestampMs >= returnFromMs && currentMs >= returnFromMs && o.timestampMs <= returnUntilMs
                && CatalogParser.sameProject(returnProject, CatalogParser.projectFromPage(o.items, c.eventKeyword));
        CatalogProject initialProject = !sku && fingerprint.equals(initialDetailFingerprint)
                ? CatalogParser.projectFromPage(o.items, c.eventKeyword) : null;
        boolean initialScope = !sku && titleMatches && dateMatches && initialProject != null
                && c.city.equals(initialProject.city) && !DetailScrollPolicy.unsafePage(o.items)
                && (timeMatches || !Pattern.compile("(?<!\\d)[0-2]\\d:[0-5]\\d(?!\\d)").matcher(all).find());
        if (!scopeVisible && !inheritedScope && !returnScope && !initialScope) {
            // Native observation precedes OCR. Missing text must not destroy a still-live permit
            // before that same frame is fully read; an actual contradictory project/time does.
            CatalogProject currentProject = !sku && returnProject != null
                    ? CatalogParser.projectFromPage(o.items, c.eventKeyword) : null;
            if (currentProject != null && !CatalogParser.sameProject(returnProject, currentProject)) clearReturnContext();
            else if (!sku && titleMatches && dateMatches && !timeMatches
                    && Pattern.compile("(?<!\\d)[0-2]\\d:[0-5]\\d(?!\\d)").matcher(all).find()) clearReturnContext();
            return result(Decision.State.UNKNOWN, "event_city_date_time_not_verified");
        }
        if (!sku) {
            UiItem opener = exactButton(o.items, "立即购买", "立即预订", "选座购买", "缺货登记", "购买");
            if ((returnScope || initialScope) && opener != null && "缺货登记".equals(opener.text.trim()))
                opener = DetailOcrNavigation.uniqueEntry(o.items);
            if (opener == null) return result(Decision.State.UNKNOWN, "ticket_entry_not_verified");
            // BACK and re-entry form one refresh round. The user interval gates the next BACK,
            // while this one-time, identity-bound re-entry only waits for normal page settling.
            if (returnScope ? o.timestampMs - returnFromMs < 150 : !initialScope && !intervalDue(c, o, lastNavigationMs))
                return result(Decision.State.READY, "waiting_for_interval");
            pendingFingerprint = fingerprint;
            pendingReentry = returnScope;
            return result(Decision.State.READY, Decision.Action.OPEN_TICKETS, opener,
                    returnScope ? "verified_return_detail_entry" : initialScope ? "verified_initial_detail_entry" : "verified_detail_entry");
        }
        if (verified == null) {
            UiItem sessionTarget = sessionSelectionTarget(c, o.items);
            if (sessionTarget != null && c.canPreparePurchase())
                return result(Decision.State.PROBING, Decision.Action.SELECT_SESSION, sessionTarget,
                        "target_session_cell_verified");
        }
        if (!dateSelectionVerified(c, o)) return result(Decision.State.UNKNOWN, "selected_session_not_verified");
        if (verified != null && verified.sessionUnavailable) {
            // Session-scoped negative evidence precedes every tier input, including contradictory
            // positive price labels. It permits only a paced return for another normal observation.
            captureReturnProject(c, o, fingerprint);
            return result(Decision.State.OUT_OF_STOCK,
                    intervalDue(c, o, lastNavigationMs) ? Decision.Action.BACK : Decision.Action.NONE,
                    null, "selected_session_explicitly_unavailable", verified);
        }
        if (candidates.isEmpty()) return result(Decision.State.UNKNOWN, "target_prices_not_readable");

        // An already selected fallback must not hide explicit primary shortage. Release only
        // the price preference; confirmation still needs the next independently fresh read.
        if(c.openingPrice>0 && c.prices.size()>1 && wantedPrices.equals(List.of(c.openingPrice))
                && verified!=null && verified.selectedPrice!=null && verified.selectedPrice!=c.openingPrice
                && candidates.stream().allMatch(x->x.negative))
            return result(Decision.State.OUT_OF_STOCK,Decision.Action.NONE,null,"all_target_tiers_explicitly_unavailable",verified);

        if (verified != null && verified.selectedPrice != null) {
            List<Candidate> matching = byPrice(candidates, verified.selectedPrice);
            boolean contraryNativeSelection = allCandidates.stream().anyMatch(x -> x.item.source == UiItem.Source.NODE
                    && x.item.selected && x.price != verified.selectedPrice);
            if (matching.size() != 1 || contraryNativeSelection || matching.get(0).negative || !matching.get(0).item.enabled)
                return result(Decision.State.UNKNOWN, "adapter_price_selection_not_verified");
            long expected = (long) verified.selectedPrice * c.quantity;
            if (c.quantity == 2 && verified.quantity != null && verified.quantity == 1
                    && verified.totalYuan != null && verified.totalYuan.equals(verified.selectedPrice)
                    && expected <= c.maxTotal && c.canPreparePurchase()
                    && (!Pattern.compile("(?:购买数量|数量|已选)\\s*[:：]?\\s*\\d").matcher(all).find() || quantityVerified(1, all))
                    && (!Pattern.compile("(?:合计|总计|票款|应付)\\s*[:：]?\\s*[¥￥]?\\s*\\d").matcher(all).find()
                        || totalVerified(1, c.maxTotal, verified.selectedPrice, all))
                    && verifiedIncreasePresent(o, verified.quantityIncreaseTarget)
                    && verifiedConfirmPresent(o, verified.confirmTarget))
                return result(Decision.State.PROBING, Decision.Action.INCREASE_QUANTITY,
                        verified.quantityIncreaseTarget, "adapter_one_ticket_needs_verified_increase", verified);
            if (verified.quantity == null || verified.quantity != c.quantity || verified.totalYuan == null
                    || verified.totalYuan != expected || expected > c.maxTotal
                    || (Pattern.compile("(?:购买数量|数量|已选)\\s*[:：]?\\s*\\d").matcher(all).find() && !quantityVerified(c, all))
                    || (Pattern.compile("(?:合计|总计|票款|应付)\\s*[:：]?\\s*[¥￥]?\\s*\\d").matcher(all).find()
                        && !totalVerified(c, verified.selectedPrice, all)))
                return result(Decision.State.UNKNOWN, Decision.Action.NONE, null, "adapter_quantity_or_total_not_verified", verified);
            UiItem confirm = verified.confirmTarget;
            if (!verifiedConfirmPresent(o, confirm))
                return result(Decision.State.UNKNOWN, "adapter_confirm_button_not_verified");
            if (c.canPreparePurchase())
                return result(Decision.State.TIER_SELECTED, Decision.Action.CONFIRM_TIER, confirm, "adapter_selected_price_quantity_total_verified", verified);
            return result(Decision.State.IN_STOCK, "adapter_selected_price_quantity_total_verified");
        }

        // A selected node alone is not stock evidence. It must coexist with an enabled confirm,
        // a visible quantity and an exact total, and must have no negative label in its own region.
        Candidate selected = uniqueSelected(allCandidates);
        if (selected != null && wantedPrices.contains(selected.price) && !selected.negative && selected.item.enabled) {
            UiItem confirm = exactButton(o.items, "确定");
            if (confirm != null && confirm.source == UiItem.Source.NODE && confirm.clickable
                    && quantityVerified(c, all) && totalVerified(c, selected.price, all)) {
                if (c.canPreparePurchase())
                    return result(Decision.State.TIER_SELECTED, Decision.Action.CONFIRM_TIER, confirm, "selected_price_quantity_total_verified");
                return result(Decision.State.IN_STOCK, "selected_price_quantity_total_verified");
            }
        }
        for (int price : wantedPrices) {
            List<Candidate> matching = byPrice(candidates, price);
            if (matching.size() == 1) {
                Candidate candidate = matching.get(0);
                if (candidate.positive && !candidate.negative && candidate.item.enabled && candidate.item.hasBounds()) {
                    if (c.canPreparePurchase() && !candidate.item.selected)
                        return result(Decision.State.IN_STOCK, Decision.Action.SELECT_TIER, candidate.item, "explicit_available_label", verified);
                    return result(Decision.State.IN_STOCK, Decision.Action.NONE, null, "explicit_available_label", verified);
                }
            }
        }
        // A bounded ordinary trial selection is a check, never an inventory signal.
        // It is allowed only after the adapter positively verified this frame's selected session.
        if (verified != null && c.canPreparePurchase() && verified.selectedPrice == null
                && uniqueRegion(o.items, "project_detail_perform_price_flowlayout") != null) {
            for (int price : wantedPrices) {
                List<Candidate> matching = byPrice(candidates, price);
                if (matching.size() == 1) {
                    Candidate candidate = matching.get(0);
                    if (!candidate.negative && candidate.item.enabled && candidate.item.hasBounds())
                        return result(Decision.State.PROBING, Decision.Action.PROBE_TIER, candidate.item, "trial_selection_inventory_unknown", verified);
                }
            }
        }
        boolean allUnavailable = true;
        for (int price : wantedPrices) {
            List<Candidate> matching = byPrice(candidates, price);
            if (matching.isEmpty() || matching.stream().anyMatch(x -> !x.negative)) allUnavailable = false;
        }
        if (allUnavailable) {
            captureReturnProject(c, o, fingerprint);
            if (intervalDue(c, o, lastNavigationMs))
                return result(Decision.State.OUT_OF_STOCK, Decision.Action.BACK, null, "all_target_tiers_explicitly_unavailable", verified);
            return result(Decision.State.OUT_OF_STOCK, Decision.Action.NONE, null, "all_target_tiers_explicitly_unavailable", verified);
        }
        return result(Decision.State.UNKNOWN, Decision.Action.NONE, null, "inventory_or_selection_ambiguous", verified);
    }

    private void clearReturnContext() {
        returnFingerprint = ""; returnProject = null; returnFromMs = 0; returnUntilMs = 0;
        detailScrollAvailable = false; pendingDetailScroll = null; consumedDetailScroll = null;
        detailScrollUnsentRetries = 0;
        detailScrollReobserveAfterMs = 0;
    }

    private void captureReturnProject(MonitorConfig c, Observation o, String fingerprint) {
        CatalogProject project = CatalogParser.projectFromPage(o.items, c.eventKeyword);
        if (project != null && c.city.equals(project.city)) {
            pendingReturnFingerprint = fingerprint; pendingReturnProject = project;
        }
    }

    private static UiItem uniqueRegion(List<UiItem> items, String id) {
        UiItem found = null;
        for (UiItem item : items) if (item.source == UiItem.Source.NODE && item.hasBounds()
                && item.resourceId.equals("cn.damai:id/" + id)) {
            if (found != null) return null; found = item;
        }
        return found;
    }

    /** Only the adapter's complete, native-cell-scoped OCR label can propose date selection. */
    private static UiItem sessionSelectionTarget(MonitorConfig c, List<UiItem> items) {
        UiItem region = uniqueRegion(items, "project_detail_perform_flowlayout");
        if (region == null || !region.enabled) return null;
        Pattern date = Pattern.compile("(?<!\\d)(20\\d{2})[年./-](\\d{1,2})[月./-](\\d{1,2})(?:日)?(?!\\d)");
        Pattern time = Pattern.compile("(?<!\\d)([0-2]\\d:[0-5]\\d)(?!\\d)");
        if (items.stream().anyMatch(item -> item.source == UiItem.Source.NODE && item.selected
                && encloses(region, item) && date.matcher(item.text).find())) return null;
        List<UiItem> cells = new ArrayList<>();
        for (UiItem item : items) if (item.source == UiItem.Source.NODE
                && item.resourceId.equals("cn.damai:id/ll_perform_item") && encloses(region, item)) {
            if (item.selected) return null;
            cells.add(item);
        }
        UiItem target = null;
        for (UiItem cell : cells) {
            if (!cell.enabled) continue;
            for (UiItem label : items) {
                if (label.source != UiItem.Source.OCR || !label.hasBounds() || !label.enabled
                        || cell.left != label.left || cell.top != label.top || cell.right != label.right || cell.bottom != label.bottom) continue;
                Matcher dates = date.matcher(label.text), times = time.matcher(label.text);
                if (!dates.find() || !times.find()) continue;
                String selectedDate;
                try { selectedDate = LocalDate.of(Integer.parseInt(dates.group(1)),
                        Integer.parseInt(dates.group(2)), Integer.parseInt(dates.group(3))).toString(); }
                catch (RuntimeException invalid) { continue; }
                String selectedTime = times.group(1);
                if (dates.find() || times.find() || !c.date.equals(selectedDate) || !c.time.equals(selectedTime)) continue;
                if (target != null) return null;
                target = label;
            }
        }
        return target;
    }

    private Decision result(Decision.State state, String reason) { return result(state, Decision.Action.NONE, null, reason); }
    private Decision result(Decision.State state, Decision.Action action, UiItem target, String reason) {
        lastDecision = new Decision(state, action, target, reason, observedAtMs); return lastDecision;
    }
    private Decision result(Decision.State state, Decision.Action action, UiItem target, String reason,
                            Observation.VerifiedSelection evidence) {
        lastDecision = new Decision(state, action, target, reason, observedAtMs, evidence); return lastDecision;
    }
    private static boolean verifiedIncreasePresent(Observation observation, UiItem plus) {
        return plus != null && plus.source == UiItem.Source.NODE && plus.resourceId.equals("cn.damai:id/img_jia")
                && plus.hasBounds() && plus.enabled && plus.clickable && plus.text.isEmpty()
                && observation.items.stream().anyMatch(item -> item == plus);
    }
    private static boolean intervalDue(MonitorConfig c, Observation o, long last) { return last <= 0 || o.timestampMs - last >= c.minIntervalMs; }
    private static String fingerprint(MonitorConfig c) { return c.eventKeyword + "|" + c.city + "|" + c.date + "|" + c.time + "|" + c.prices + "|" + c.quantity + "|" + c.maxTotal; }
    private static String join(List<UiItem> items) { StringBuilder b = new StringBuilder(); for (UiItem i : items) b.append(i.text).append('\n'); return b.toString().trim(); }
    private static boolean contains(String text, String... terms) { for (String term : terms) if (text.contains(term)) return true; return false; }
    private static boolean titleMatches(MonitorConfig c, List<UiItem> items) {
        for (UiItem i : items) {
            if (i.text.contains(c.eventKeyword) && i.text.contains(c.city)) return true;
            if (!i.text.contains(c.eventKeyword) || !i.hasBounds()) continue;
            for (UiItem j : items) if (j.hasBounds() && j.text.contains(c.city)
                    && Math.abs(i.centerY() - j.centerY()) < 160) return true;
        }
        return false;
    }
    /** OCR may split the event name from its generic continuation and city onto another line. */
    private static boolean belongsToVerifiedTitle(MonitorConfig c, UiItem line, List<UiItem> items) {
        if (!line.hasBounds()) return false;
        for (UiItem event : items) {
            if (!event.hasBounds() || !event.text.contains(c.eventKeyword)) continue;
            if (event.text.contains(c.city)) {
                // Accessibility often provides the complete title in addition to its OCR lines.
                if (event.source == UiItem.Source.NODE && event.bottom - event.top <= 240 && encloses(event, line)
                        && compact(event.text).contains(compact(line.text))) return true;
                if (adjacentTitleLine(event, line)) return true;
            }
            for (UiItem city : items) {
                if (!city.hasBounds() || !city.text.contains(c.city) || !adjacentTitleLine(event, city)) continue;
                // Both identifying pieces must belong to this local title region, not a distant city card.
                if (adjacentTitleLine(event, line) && (line == city || adjacentTitleLine(city, line))) return true;
            }
        }
        return false;
    }
    private static boolean encloses(UiItem outer, UiItem inner) {
        return outer.left <= inner.left && outer.top <= inner.top && outer.right >= inner.right && outer.bottom >= inner.bottom;
    }
    private static String compact(String text) { return text.replaceAll("\\s+", ""); }
    private static boolean adjacentTitleLine(UiItem first, UiItem second) {
        if (first.bottom - first.top > 240 || second.bottom - second.top > 240) return false;
        int overlap = Math.min(first.right, second.right) - Math.max(first.left, second.left);
        int smallerWidth = Math.min(first.right - first.left, second.right - second.left);
        int verticalGap = Math.max(0, Math.max(first.top - second.bottom, second.top - first.bottom));
        int lineHeight = Math.min(first.bottom - first.top, second.bottom - second.top);
        // A small inter-line gap plus substantial horizontal overlap distinguishes wrapping from another card.
        return overlap > 0 && overlap * 2 >= smallerWidth && verticalGap <= Math.min(48, Math.max(12, lineHeight / 2))
                && Math.abs(first.centerY() - second.centerY()) <= Math.max(first.bottom - first.top, second.bottom - second.top) + 48;
    }
    private static boolean hasDate(MonitorConfig c, String text, long timestamp) {
        LocalDate d = LocalDate.parse(c.date);
        String month = "0?" + d.getMonthValue(), day = "0?" + d.getDayOfMonth();
        String suffix = month + "[月./-]" + day + "(?:日|(?=[^0-9]|$))";
        if (Pattern.compile("(?<!\\d)" + d.getYear() + "[年./-]" + suffix).matcher(text).find()) return true;
        boolean yearIsCurrent = Instant.ofEpochMilli(timestamp).atZone(ZoneId.of("Asia/Shanghai")).getYear() == d.getYear();
        // Explicit displayed years must agree; a short date is acceptable only in the current year.
        Matcher years = Pattern.compile("(?<!\\d)(20\\d{2})[年./-]\\d{1,2}[月./-]").matcher(text);
        while (years.find()) if (Integer.parseInt(years.group(1)) != d.getYear()) return false;
        return yearIsCurrent && Pattern.compile("(?<![\\d])" + suffix).matcher(text).find();
    }
    private static boolean dateSelectionVerified(MonitorConfig c, Observation o) {
        Set<String> dates = new HashSet<>(), times = new HashSet<>(), selectedDates = new HashSet<>(), selectedTimes = new HashSet<>();
        LocalDate d = LocalDate.parse(c.date);
        Pattern p = Pattern.compile("(?<!\\d)(?:(20\\d{2})[年./-])?(\\d{1,2})[月./-](\\d{1,2})(?:日|(?=[^0-9]|$))");
        Pattern t = Pattern.compile("(?<!\\d)([0-2]\\d:[0-5]\\d)(?!\\d)");
        for (UiItem i : o.items) {
            boolean selectedSession = i.source == UiItem.Source.NODE && i.selected && p.matcher(i.text).find();
            Matcher m = p.matcher(i.text);
            while (m.find()) {
                int year = m.group(1) == null ? d.getYear() : Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2)), day = Integer.parseInt(m.group(3));
                if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                    String key = year + "-" + month + "-" + day;
                    dates.add(key); if (selectedSession) selectedDates.add(key);
                }
            }
            Matcher clock = t.matcher(i.text);
            while (clock.find()) { times.add(clock.group(1)); if (selectedSession) selectedTimes.add(clock.group(1)); }
        }
        if (!selectedDates.isEmpty()) return selectedDates.size() == 1
                && selectedDates.contains(d.getYear() + "-" + d.getMonthValue() + "-" + d.getDayOfMonth())
                && selectedTimes.size() == 1 && selectedTimes.contains(c.time);
        Observation.VerifiedSelection verified = o.verifiedSelection;
        if (verified != null && verified.observedAtMs == o.timestampMs
                && c.date.equals(verified.date) && c.time.equals(verified.time)) return true;
        return dates.size() == 1 && dates.contains(d.getYear() + "-" + d.getMonthValue() + "-" + d.getDayOfMonth())
                && times.size() == 1 && times.contains(c.time);
    }
    private static boolean verifiedConfirmPresent(Observation o, UiItem target) {
        if (target == null || target.source != UiItem.Source.NODE || !target.enabled || !target.clickable
                || !target.hasBounds() || !"cn.damai:id/btn_buy_view".equals(target.resourceId)) return false;
        return o.items.stream().anyMatch(i -> i.source == UiItem.Source.NODE && i.enabled && i.clickable
                && i.resourceId.equals(target.resourceId) && i.left == target.left && i.top == target.top
                && i.right == target.right && i.bottom == target.bottom);
    }
    private static UiItem exactButton(List<UiItem> items, String... names) {
        for (UiItem i : items) if (i.enabled && i.hasBounds() && (i.clickable || i.source == UiItem.Source.OCR))
            for (String name : names) if (i.text.trim().equals(name)) return i;
        return null;
    }
    private static Set<Integer> pricesIn(String text) {
        Set<Integer> prices = new HashSet<>();
        Matcher m = PRICE.matcher(text);
        while (m.find()) prices.add(Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2)));
        if (text.matches("\\d{2,6}")) prices.add(Integer.parseInt(text));
        return prices;
    }
    private static List<Candidate> candidates(List<UiItem> items) {
        List<Candidate> found = new ArrayList<>();
        List<UiItem> priceAreas = new ArrayList<>();
        for (UiItem item : items) if (item.source == UiItem.Source.NODE && item.hasBounds()
                && "cn.damai:id/project_detail_perform_price_flowlayout".equals(item.resourceId)) priceAreas.add(item);
        for (UiItem i : items) {
            if ("cn.damai:id/tv_price".equals(i.resourceId) || "cn.damai:id/tv_sku_price_prefix".equals(i.resourceId)) continue;
            if (!priceAreas.isEmpty() && priceAreas.stream().noneMatch(area -> encloses(area, i))) continue;
            Set<Integer> prices = pricesIn(i.text);
            if (prices.size() != 1 || contains(i.text, "合计", "总计", "票款", "实付", "应付", "支付", "原价", "服务费")) continue;
            int price = prices.iterator().next();
            if (!i.hasBounds()) continue;
            // Parent nodes may repeat child text: prefer the smaller, precise region.
            boolean hasSmaller = items.stream().anyMatch(j -> j != i && j.hasBounds() && pricesIn(j.text).contains(price)
                    && j.left >= i.left && j.top >= i.top && j.right <= i.right && j.bottom <= i.bottom
                    && (j.right-j.left) * (long)(j.bottom-j.top) < (i.right-i.left) * (long)(i.bottom-i.top));
            if (hasSmaller) continue;
            Candidate duplicate = found.stream().filter(x -> x.price == price && Math.abs(x.item.centerX()-i.centerX()) < 12
                    && Math.abs(x.item.centerY()-i.centerY()) < 12).findFirst().orElse(null);
            if (duplicate != null) {
                if (i.source == UiItem.Source.NODE && duplicate.item.source != UiItem.Source.NODE) found.remove(duplicate); else continue;
            }
            found.add(new Candidate(price, i));
        }
        for (Candidate candidate : found) {
            StringBuilder region = new StringBuilder(candidate.item.text);
            for (UiItem label : items) {
                if (label == candidate.item || !label.hasBounds() || !pricesIn(label.text).isEmpty()) continue;
                if (!(NEGATIVE.matcher(label.text).find() || explicitlyPositive(label.text))) continue;
                Candidate closest = null; double best = Double.MAX_VALUE; boolean tie = false;
                for (Candidate possible : found) {
                    double distance = distance(possible.item, label);
                    if (distance < best - 1) { closest = possible; best = distance; tie = false; }
                    else if (Math.abs(distance - best) <= 1) tie = true;
                }
                if (!tie && closest == candidate && best <= 90) region.append(' ').append(label.text);
            }
            candidate.negative = NEGATIVE.matcher(region).find();
            candidate.positive = explicitlyPositive(region.toString());
        }
        return found;
    }
    private static double distance(UiItem a, UiItem b) {
        // Associate a label only when its horizontal centre belongs to the same ticket cell.
        if (b.centerX() < a.left - 15 || b.centerX() > a.right + 15) return Double.MAX_VALUE;
        int dy = Math.max(0, Math.max(a.top - b.bottom, b.top - a.bottom));
        return dy + Math.abs(a.centerX() - b.centerX()) * .2;
    }
    private static boolean explicitlyPositive(String text) {
        String stripped = text.replace("有票提醒", "").replace("有货提醒", "").replace("不可选", "")
                .replace("可选座", "").replace("不可购买", "").replace("开售提醒", "");
        return Pattern.compile("(?:^|[\\s:：元])(?:有票|有货|可购买|库存充足|可选)(?:$|[\\s,，;；])|剩余\\s*[1-9]\\d*\\s*张").matcher(stripped).find();
    }
    private static List<Candidate> byPrice(List<Candidate> candidates, int price) {
        List<Candidate> result = new ArrayList<>(); for (Candidate c : candidates) if (c.price == price) result.add(c); return result;
    }
    private static Candidate uniqueSelected(List<Candidate> candidates) {
        Candidate selected = null;
        for (Candidate c : candidates) if (c.item.selected && c.item.source == UiItem.Source.NODE) {
            if (selected != null) return null; selected = c;
        }
        return selected;
    }
    private static boolean quantityVerified(MonitorConfig c, String all) {
        return quantityVerified(c.quantity, all);
    }
    private static boolean quantityVerified(int quantity, String all) {
        Pattern p = Pattern.compile("(?:购买数量|数量|已选)\\s*[:：]?\\s*(\\d{1,3})(?![\\d.])\\s*(?:张)?");
        Matcher m = p.matcher(all); boolean found = false;
        while (m.find()) { if (Integer.parseInt(m.group(1)) != quantity) return false; found = true; }
        return found;
    }
    private static boolean totalVerified(MonitorConfig c, int price, String all) {
        return totalVerified(c.quantity, c.maxTotal, price, all);
    }
    private static boolean totalVerified(int quantity, int maxTotal, int price, String all) {
        Pattern p = Pattern.compile("(?:合计|总计|票款|应付)\\s*[:：]?\\s*[¥￥]?\\s*(\\d{1,9})(?:\\.(\\d{1,2}))?(?![\\d.])\\s*元?");
        Matcher m = p.matcher(all); boolean found = false;
        long expected = (long) price * quantity;
        if (expected > maxTotal) return false;
        while (m.find()) {
            if (Long.parseLong(m.group(1)) != expected || (m.group(2) != null && Integer.parseInt(m.group(2)) != 0)) return false;
            found = true;
        }
        return found;
    }
    private static final class Candidate {
        final int price; final UiItem item; boolean positive, negative;
        Candidate(int price, UiItem item) { this.price = price; this.item = item; }
    }
}
