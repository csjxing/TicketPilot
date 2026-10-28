package cn.local.tickethelper.checkout;

import cn.local.tickethelper.core.UiItem;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import static cn.local.tickethelper.checkout.CheckoutProfile.Role;
import static cn.local.tickethelper.checkout.CheckoutParseResult.Status;

/** Pure native-page parsing. No configuration backfill, screenshot, input, storage, or network. */
public final class CheckoutPageParser {
    public interface ViewerResolver {
        ViewerMatcher.Result resolve(List<ViewerMatcher.ViewerRow> rows, boolean completeNativeRows);
    }
    private static final long MAX_AGE_MS = 3000;
    private static final DateTimeFormatter EXACT_TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private CheckoutPageParser() { }

    public static CheckoutParseResult parse(CheckoutFrame frame, CheckoutProfile profile, long nowMs,
                                            ViewerResolver resolver) {
        return parse(frame, profile, nowMs, resolver, "");
    }

    /** relatedAttemptId is local routing only; it does not prove an official order was just made. */
    public static CheckoutParseResult parse(CheckoutFrame frame, CheckoutProfile profile, long nowMs,
                                            ViewerResolver resolver, String relatedAttemptId) {
        if (profile == null) return failed(frame, Status.UNSUPPORTED, "checkout_profile_unavailable", relatedAttemptId);
        try {
            Tree tree = new Tree(frame, profile, nowMs);
            PageMatch page = tree.page(profile);
            Reader reader = new Reader(tree, profile, page);
            String title = reader.value(Role.EVENT_TITLE), city = reader.value(Role.CITY);
            if (!fullText(title, 5, 300) || !fullText(city, 1, 40)) throw incomplete("public_identity_incomplete");
            String date, time;
            if (profile.fields.containsKey(Role.SESSION_DATETIME)) {
                String[] session = sessionDateTime(reader.value(Role.SESSION_DATETIME));
                date = session[0]; time = session[1];
            } else {
                date = date(reader.value(Role.SESSION_DATE));
                time = time(reader.value(Role.SESSION_TIME));
            }
            String[] ticketLine = profile.fields.containsKey(Role.UNIT_PRICE_QUANTITY) ? reader.priceQuantity() : null;
            long unit = money(ticketLine == null ? reader.value(Role.UNIT_PRICE) : ticketLine[0], false);
            int quantity = integer(ticketLine == null ? reader.value(Role.QUANTITY) : ticketLine[1], 1, 100);
            long subtotal = money(reader.value(Role.TICKET_SUBTOTAL), false);
            long payable = money(reader.value(Role.PAYABLE), false);
            if (Math.multiplyExact(unit, quantity) != subtotal || payable != subtotal)
                throw incomplete("amount_relationship_unverified");
            reader.exact(Role.EXTRAS_STATUS);
            if (money(reader.value(Role.EXTRA_FEE), true) != 0) throw incomplete("additional_charge_present");

            List<ViewerMatcher.ViewerRow> rows = reader.viewers();
            int declaredRows = integer(reader.value(Role.VIEWER_COUNT), 1, 128);
            boolean allRows = declaredRows == rows.size();
            ViewerMatcher.Result viewer = resolver == null ? null : resolver.resolve(List.copyOf(rows), allRows);
            boolean matchingCount = viewer != null && viewer.viewerCount == quantity;
            boolean selected = allRows && matchingCount && viewer.state == ViewerMatcher.State.VERIFIED_SELECTED;
            boolean matched = allRows && matchingCount && (selected || viewer.state == ViewerMatcher.State.SELECT_PROPOSAL);
            CheckoutObservation.Builder observation = new CheckoutObservation.Builder(page.kind, frame.observedAtMs)
                    .frame(frame.packageName, frame.windowId, frame.generationAfter, true, true)
                    .relatedAttempt(relatedAttemptId).target(title, city, date, time)
                    .amounts(unit, quantity, subtotal, payable).extras(true)
                    .viewer(matched ? viewer.bindingHash : "", selected);
            if (page.kind == CheckoutObservation.Kind.CONFIRMATION) {
                CheckoutFrame.Node agreement = reader.exact(Role.AGREEMENT);
                CheckoutFrame.Node submit = reader.exact(Role.SUBMIT);
                if (!CheckoutProfile.isSubmitLabel(submit.item.text)) throw incomplete("native_submit_label_unverified");
                // Disabled submit / unchecked terms are genuine facts useful for selecting a viewer.
                // They are not repaired; the order engine independently requires all final gates.
                observation.agreements(agreement.checkable && agreement.checked).submit(submit.item, true, CheckoutSubmitBinding.from(profile));
            } else {
                String reference = reader.value(Role.ORDER_REFERENCE);
                if (!reference.matches("[A-Za-z0-9_-]{4,200}")) throw incomplete("order_reference_invalid");
                Long createdAt = null;
                if (reader.present(Role.ORDER_CREATED_AT)) {
                    createdAt = timestamp(reader.value(Role.ORDER_CREATED_AT), profile);
                    if (createdAt > frame.observedAtMs) throw incomplete("order_creation_time_invalid");
                }
                Long deadline = null; String deadlineText = "";
                if (reader.present(Role.PAYMENT_DEADLINE)) {
                    deadlineText = reader.value(Role.PAYMENT_DEADLINE);
                    deadline = timestamp(deadlineText, profile);
                } else if (reader.present(Role.PAYMENT_COUNTDOWN)) {
                    deadlineText = reader.value(Role.PAYMENT_COUNTDOWN);
                    deadline = Math.addExact(frame.observedAtMs, countdown(deadlineText));
                }
                observation.pendingOrder(reference, true, deadline, deadlineText)
                        .orderCreatedAt(createdAt, createdAt != null);
            }
            // A candidate checkbox click is never promoted to a verified attendee selection.
            return new CheckoutParseResult(selected ? Status.PARSED : Status.INCOMPLETE, observation.build(),
                    selected ? "native_checkout_facts_parsed" : "viewer_selection_unverified",
                    matchingCount || (viewer != null && viewer.state == ViewerMatcher.State.UNCERTAIN) ? viewer : null);
        } catch (ParseFailure failure) {
            return failed(frame, failure.status, failure.code, relatedAttemptId);
        } catch (ArithmeticException | DateTimeException invalid) {
            return failed(frame, Status.INCOMPLETE, "numeric_or_calendar_value_invalid", relatedAttemptId);
        } catch (RuntimeException invalid) {
            // Includes an injected resolver failing. Never include exception messages or node text.
            return failed(frame, Status.INCOMPLETE, "checkout_parse_failed", relatedAttemptId);
        }
    }

    private static CheckoutParseResult failed(CheckoutFrame frame, Status status, String reason, String attempt) {
        return new CheckoutParseResult(status, new CheckoutObservation.Builder(CheckoutObservation.Kind.UNKNOWN,
                frame == null ? 0 : frame.observedAtMs).relatedAttempt(attempt).build(), reason, null);
    }
    private static boolean fullText(String text, int min, int max) {
        return text.length() >= min && text.length() <= max && !text.contains("…") && !text.contains("...")
                && text.chars().noneMatch(Character::isISOControl);
    }
    private static long money(String text, boolean allowZero) {
        if (!text.matches("(?:0|[1-9][0-9]{0,6})(?:\\.[0-9]{1,2})?")) throw incomplete("money_value_invalid");
        long result = new BigDecimal(text).movePointRight(2).longValueExact();
        if (result < (allowZero ? 0 : 1) || result > 1_000_000_000L) throw incomplete("money_value_invalid");
        return result;
    }
    private static int integer(String text, int min, int max) {
        if (!text.matches("[0-9]{1,3}")) throw incomplete("integer_value_invalid");
        int value = Integer.parseInt(text);
        if (value < min || value > max) throw incomplete("integer_value_invalid");
        return value;
    }
    private static String date(String text) {
        if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !LocalDate.parse(text).toString().equals(text))
            throw incomplete("session_date_invalid");
        return text;
    }
    private static String time(String text) {
        if (!text.matches("[0-9]{2}:[0-9]{2}")) throw incomplete("session_time_invalid");
        LocalTime value = LocalTime.parse(text);
        if (!String.format(java.util.Locale.ROOT, "%02d:%02d", value.getHour(), value.getMinute()).equals(text))
            throw incomplete("session_time_invalid");
        return text;
    }
    private static String[] sessionDateTime(String text) {
        if (!text.matches("(?:[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}|[0-9]{4}-[0-9]{2}-[0-9]{2}) [0-9]{2}:[0-9]{2}"))
            throw incomplete("session_datetime_invalid");
        String[] parts = text.split(" ", -1);
        return new String[]{date(parts[0].replace('.', '-')), time(parts[1])};
    }
    private static long timestamp(String text, CheckoutProfile profile) {
        if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"))
            throw incomplete("official_timestamp_incomplete");
        LocalDateTime local = LocalDateTime.parse(text, EXACT_TIMESTAMP);
        // Ambiguous or nonexistent local time is not repaired using a timezone heuristic.
        List<java.time.ZoneOffset> offsets = profile.zoneId.getRules().getValidOffsets(local);
        if (offsets.size() != 1) throw incomplete("official_timestamp_ambiguous");
        long result = local.toInstant(offsets.get(0)).toEpochMilli();
        if (result <= 0) throw incomplete("official_timestamp_invalid");
        return result;
    }
    private static long countdown(String text) {
        if (!text.matches("[0-9]{1,2}:[0-5][0-9](?::[0-5][0-9])?")) throw incomplete("payment_countdown_invalid");
        String[] parts = text.split(":");
        long seconds = parts.length == 2 ? Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])
                : Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2]);
        if (seconds > 86400) throw incomplete("payment_countdown_invalid");
        return seconds * 1000;
    }
    private static ParseFailure incomplete(String code) { return new ParseFailure(Status.INCOMPLETE, code); }
    private static final class ParseFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Status status; final String code;
        ParseFailure(Status status, String code) { super(code); this.status = status; this.code = code; }
    }
    private static final class PageMatch {
        final CheckoutObservation.Kind kind; final CheckoutFrame.Node root, marker;
        PageMatch(CheckoutObservation.Kind kind, CheckoutFrame.Node root, CheckoutFrame.Node marker) {
            this.kind = kind; this.root = root; this.marker = marker;
        }
    }
    private static final class Tree {
        final CheckoutFrame frame;
        final Map<String, CheckoutFrame.Node> nodes = new HashMap<>();
        Tree(CheckoutFrame frame, CheckoutProfile profile, long nowMs) {
            this.frame = frame;
            if (frame == null || !frame.complete || frame.windowId < 0 || frame.displayId != 0
                    || frame.generationBefore < 0 || frame.generationBefore != frame.generationAfter
                    || nowMs <= 0 || frame.observedAtMs <= 0 || frame.observedAtMs > nowMs
                    || nowMs - frame.observedAtMs > MAX_AGE_MS || frame.nodes.isEmpty() || frame.nodes.size() > 500)
                throw new ParseFailure(Status.INVALID_FRAME, "native_frame_incomplete_or_stale");
            if (!profile.packageName.equals(frame.packageName) || !profile.appVersion.equals(frame.appVersion))
                throw new ParseFailure(Status.UNSUPPORTED, "native_app_version_unprofiled");
            int roots = 0;
            for (CheckoutFrame.Node node : frame.nodes) {
                if (node == null || node.key.isEmpty() || node.item == null || node.item.source != UiItem.Source.NODE
                        || node.windowId != frame.windowId || nodes.put(node.key, node) != null)
                    throw new ParseFailure(Status.INVALID_FRAME, "native_node_identity_invalid");
                if (node.parentKey.isEmpty()) roots++;
            }
            if (roots != 1) throw new ParseFailure(Status.INVALID_FRAME, "native_root_ambiguous");
            for (CheckoutFrame.Node node : frame.nodes) {
                Set<String> seen = new HashSet<>(); CheckoutFrame.Node current = node;
                while (current != null) {
                    if (!seen.add(current.key) || seen.size() > 41)
                        throw new ParseFailure(Status.INVALID_FRAME, "native_tree_invalid");
                    if (current.parentKey.isEmpty()) break;
                    current = nodes.get(current.parentKey);
                    if (current == null) throw new ParseFailure(Status.INVALID_FRAME, "native_parent_missing");
                }
            }
        }
        PageMatch page(CheckoutProfile profile) {
            PageMatch match = null;
            for (CheckoutProfile.Page page : profile.pages.values()) {
                List<CheckoutFrame.Node> roots = new ArrayList<>();
                for (CheckoutFrame.Node node : frame.nodes)
                    if (node.visible && node.item.resourceId.equals(page.rootResourceId)) roots.add(node);
                if (roots.size() > 1) throw new ParseFailure(Status.AMBIGUOUS, "page_root_duplicate");
                if (roots.isEmpty()) continue;
                CheckoutFrame.Node root = roots.get(0); usable(root);
                List<CheckoutFrame.Node> markers = matching(page.marker, root, true);
                if (markers.size() != 1) throw new ParseFailure(markers.isEmpty() ? Status.INCOMPLETE : Status.AMBIGUOUS,
                        "page_marker_unverified");
                usable(markers.get(0));
                // Different checkout pages may share the same native root and title ID.
                // Only the exact reviewed title claims this kind; another title cannot
                // make a second profile appear to match the current page.
                if (!markers.get(0).item.text.equals(page.marker.exactLabel)) continue;
                if (match != null) throw new ParseFailure(Status.AMBIGUOUS, "mixed_checkout_pages");
                match = new PageMatch(page.kind, root, markers.get(0));
            }
            if (match == null) throw new ParseFailure(Status.UNSUPPORTED, "checkout_page_unrecognized");
            return match;
        }
        List<CheckoutFrame.Node> matching(CheckoutProfile.Selector selector, CheckoutFrame.Node scope, boolean visibleOnly) {
            List<CheckoutFrame.Node> result = new ArrayList<>();
            if (selector.isAnchored()) {
                List<CheckoutFrame.Node> anchors = new ArrayList<>();
                for (CheckoutFrame.Node node : frame.nodes)
                    if (node.visible && node.item.resourceId.equals(selector.ancestorScopeId) && descendant(node, scope)) anchors.add(node);
                if (anchors.size() > 1) throw new ParseFailure(Status.AMBIGUOUS, "structural_anchor_duplicate");
                if (anchors.isEmpty()) return result;
                CheckoutFrame.Node current = anchors.get(0); usable(current);
                for (String index : selector.relativeChildPath.split("/")) {
                    CheckoutFrame.Node next = nodes.get(current.key + "/" + index);
                    if (next == null || !next.parentKey.equals(current.key)) return result;
                    current = next;
                }
                // A changed ID or ancestry is a new layout, not permission to relax the contract.
                if (current.item.resourceId.isEmpty() && (!visibleOnly || current.visible) && descendant(current, scope)) result.add(current);
                return result;
            }
            for (CheckoutFrame.Node node : frame.nodes) {
                if ((!visibleOnly || node.visible) && node.item.resourceId.equals(selector.resourceId)
                        && descendant(node, scope) && hasAncestor(node, selector.ancestorScopeId, scope)) result.add(node);
            }
            return result;
        }
        boolean descendant(CheckoutFrame.Node node, CheckoutFrame.Node scope) {
            CheckoutFrame.Node current = node;
            while (current != null) {
                if (current.key.equals(scope.key)) return true;
                current = nodes.get(current.parentKey);
            }
            return false;
        }
        boolean hasAncestor(CheckoutFrame.Node node, String id, CheckoutFrame.Node scope) {
            CheckoutFrame.Node current = nodes.get(node.parentKey);
            while (current != null) {
                if (current.item.resourceId.equals(id)) return true;
                if (current.key.equals(scope.key)) break;
                current = nodes.get(current.parentKey);
            }
            return false;
        }
        void usable(CheckoutFrame.Node node) {
            if (!node.visible || !node.item.hasBounds()) throw incomplete("native_field_not_visible");
            CheckoutFrame.Node current = node;
            while (current != null) {
                if (current.sensitive || !current.visible) throw incomplete("native_field_protected_or_hidden");
                current = nodes.get(current.parentKey);
            }
        }
    }
    private static final class Reader {
        final Tree tree; final CheckoutProfile profile; final CheckoutFrame.Node page;
        final Set<String> claimed = new HashSet<>();
        Reader(Tree tree, CheckoutProfile profile, PageMatch page) {
            this.tree = tree; this.profile = profile; this.page = page.root;
            claimed.add(page.root.key); claimed.add(page.marker.key);
        }
        CheckoutProfile.Selector selector(Role role) {
            CheckoutProfile.Selector selector = profile.fields.get(role);
            if (selector == null) throw incomplete("required_role_unprofiled");
            return selector;
        }
        boolean present(Role role) {
            CheckoutProfile.Selector selector = profile.fields.get(role);
            return selector != null && !tree.matching(selector, page, true).isEmpty();
        }
        CheckoutFrame.Node one(Role role, CheckoutFrame.Node scope, boolean visibleOnly) {
            List<CheckoutFrame.Node> found = tree.matching(selector(role), scope, visibleOnly);
            if (found.size() != 1) throw new ParseFailure(found.isEmpty() ? Status.INCOMPLETE : Status.AMBIGUOUS,
                    found.isEmpty() ? "required_native_field_missing" : "duplicate_native_field");
            CheckoutFrame.Node node = found.get(0); tree.usable(node);
            if (!claimed.add(node.key)) throw new ParseFailure(Status.AMBIGUOUS, "node_reused_across_roles");
            return node;
        }
        String value(Role role) { return value(role, page); }
        String[] priceQuantity() {
            CheckoutProfile.Selector selector = selector(Role.UNIT_PRICE_QUANTITY);
            if (selector.match != CheckoutProfile.Selector.Match.PRICE_QUANTITY)
                throw incomplete("price_quantity_contract_unverified");
            Matcher matcher = selector.extraction.matcher(one(Role.UNIT_PRICE_QUANTITY, page, true).item.text);
            if (!matcher.matches() || matcher.group(1) == null || matcher.group(2) == null
                    || matcher.group(1).trim().isEmpty() || matcher.group(2).trim().isEmpty())
                throw incomplete("native_ticket_line_changed");
            return new String[]{matcher.group(1).trim(), matcher.group(2).trim()};
        }
        String value(Role role, CheckoutFrame.Node scope) {
            CheckoutProfile.Selector selector = selector(role);
            if (selector.match != CheckoutProfile.Selector.Match.VALUE) throw incomplete("value_role_not_explicit");
            Matcher matcher = selector.extraction.matcher(one(role, scope, true).item.text);
            if (!matcher.matches() || matcher.group(1) == null || matcher.group(1).trim().isEmpty())
                throw incomplete("native_field_label_or_value_changed");
            return matcher.group(1).trim();
        }
        CheckoutFrame.Node exact(Role role) {
            CheckoutProfile.Selector selector = selector(role);
            if ((selector.match != CheckoutProfile.Selector.Match.EXACT && selector.match != CheckoutProfile.Selector.Match.PUBLIC_LABEL)
                    || selector.exactLabel.isEmpty())
                throw incomplete("explicit_field_label_required");
            CheckoutFrame.Node node = one(role, page, true);
            if (!node.item.text.equals(selector.exactLabel)) throw incomplete("explicit_field_label_changed");
            return node;
        }
        List<ViewerMatcher.ViewerRow> viewers() {
            CheckoutProfile.Selector rowSelector = selector(Role.VIEWER_ROW);
            if (rowSelector.match != CheckoutProfile.Selector.Match.NODE
                    || selector(Role.VIEWER_SELECTION).match != CheckoutProfile.Selector.Match.NODE)
                throw incomplete("native_viewer_structure_unprofiled");
            List<CheckoutFrame.Node> rows = tree.matching(rowSelector, page, false);
            if (rows.isEmpty() || rows.size() > 128) throw incomplete("viewer_scope_incomplete");
            List<ViewerMatcher.ViewerRow> parsed = new ArrayList<>();
            for (CheckoutFrame.Node row : rows) {
                tree.usable(row);
                if (!claimed.add(row.key)) throw new ParseFailure(Status.AMBIGUOUS, "node_reused_across_roles");
                String name = value(Role.VIEWER_NAME, row);
                String document = profile.fields.containsKey(Role.VIEWER_DOCUMENT) ? value(Role.VIEWER_DOCUMENT, row) : "";
                CheckoutFrame.Node control = one(Role.VIEWER_SELECTION, row, false);
                // Native isSelected is a different property; only this native checkbox's checked flag counts.
                UiItem source = control.item;
                UiItem selection = new UiItem("", source.left, source.top, source.right, source.bottom,
                        source.enabled, source.clickable, control.checked, UiItem.Source.NODE, source.resourceId);
                parsed.add(new ViewerMatcher.ViewerRow(row.key, name, document, selection, control.checked,
                        control.checkable, control.sensitive));
            }
            return parsed;
        }
    }
}
