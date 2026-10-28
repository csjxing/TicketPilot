package cn.local.tickethelper.checkout;

import cn.local.tickethelper.core.UiItem;
import cn.local.tickethelper.core.MonitorConfig;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;
import static cn.local.tickethelper.checkout.CheckoutProfile.Role;

/** All IDs and attendee fields in this test are synthetic; none are production Damai profiles. */
public class CheckoutPageParserTest {
    private static final String VERSION = "synthetic-fixture-1", PERSON = "示例观演人";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long NOW = LocalDateTime.of(2026, 10, 25, 10, 0, 2).atZone(ZONE).toInstant().toEpochMilli();
    private static String id(String name) { return "cn.damai:id/synthetic_" + name; }

    @Test public void confirmationParsesIndependentNativeFactsAndDirectCheckedFlag() {
        Fixture f = new Fixture(false);
        assertFalse(f.node("selection").item.selected);
        assertTrue(f.node("selection").checked);
        CheckoutParseResult result = f.parse();
        assertEquals(CheckoutParseResult.Status.PARSED, result.status);
        CheckoutObservation o = result.observation;
        assertEquals(CheckoutObservation.Kind.CONFIRMATION, o.kind);
        assertEquals("杭州·合成演唱会-杭州站", o.eventTitle);
        assertEquals("2026-11-15", o.date);
        assertEquals("19:00", o.time);
        assertEquals(Long.valueOf(98000), o.unitPriceFen);
        assertEquals(Integer.valueOf(1), o.quantity);
        assertEquals(Long.valueOf(98000), o.ticketSubtotalFen);
        assertEquals(Long.valueOf(98000), o.payableFen);
        assertEquals(LockAuthorization.viewerBindingHash(PERSON, "1234"), o.selectedViewerBindingHash);
        assertTrue(o.viewerSelectionVerified);
        assertTrue(o.agreementsAccepted);
        assertTrue(o.noUnapprovedExtras);
        assertTrue(o.uniqueNativeSubmitTarget);
        assertEquals("提交订单", o.submitTarget.text);
        assertEquals("local-routing-only", o.relatedAttemptId);
    }

    @Test public void pendingRequiresIndependentlyLabeledOrderEvidence() {
        Fixture f = new Fixture(true);
        CheckoutParseResult result = f.parse();
        assertEquals(CheckoutParseResult.Status.PARSED, result.status);
        CheckoutObservation o = result.observation;
        assertEquals(CheckoutObservation.Kind.PAYMENT_PENDING, o.kind);
        assertTrue(o.orderReferenceVerified);
        assertEquals("TESTORDER12345", o.orderReference);
        assertEquals(Long.valueOf(NOW - 2000), o.orderCreatedAtMs);
        assertTrue(o.orderCreationTimeVerified);
        assertEquals(Long.valueOf(NOW + 598000), o.paymentDeadlineMs);
        assertNull(o.submitTarget);
        assertFalse(o.uniqueNativeSubmitTarget);
    }

    @Test public void missingCreationTimeIsNeverDerivedFromDeadlineOrLocalAttempt() {
        Fixture f = new Fixture(true); f.remove("created");
        CheckoutParseResult result = f.parse();
        assertEquals(CheckoutParseResult.Status.PARSED, result.status);
        assertNull(result.observation.orderCreatedAtMs);
        assertFalse(result.observation.orderCreationTimeVerified);
        assertNotNull(result.observation.paymentDeadlineMs);
    }

    @Test public void missingDeadlineDoesNotInventOne() {
        Fixture f = new Fixture(true); f.remove("deadline");
        CheckoutParseResult result = f.parse();
        assertEquals(CheckoutParseResult.Status.PARSED, result.status);
        assertNull(result.observation.paymentDeadlineMs);
        assertEquals("", result.observation.paymentDeadlineText);
    }

    @Test public void explicitCountdownIsSeparateFromCreationTime() {
        Fixture f = new Fixture(true); f.fields.remove(Role.PAYMENT_DEADLINE);
        f.fields.put(Role.PAYMENT_COUNTDOWN, value("deadline", "order", "付款剩余：", ""));
        f.text("deadline", "付款剩余：09:58"); f.remove("created");
        CheckoutParseResult result = f.parse();
        assertEquals(CheckoutParseResult.Status.PARSED, result.status);
        assertEquals(Long.valueOf(NOW + 598000), result.observation.paymentDeadlineMs);
        assertNull(result.observation.orderCreatedAtMs);
    }

    @Test public void pendingWordAloneDoesNotEstablishOrderOrTarget() {
        Fixture f = new Fixture(true); f.remove("title"); f.remove("reference");
        assertNotParsed(f.parse());
    }

    @Test public void productionProfileAbsentAlwaysUnavailable() {
        Fixture f = new Fixture(false);
        CheckoutParseResult result = CheckoutPageParser.parse(f.frame(), null, NOW, resolver());
        assertEquals(CheckoutParseResult.Status.UNSUPPORTED, result.status);
        assertEquals(CheckoutObservation.Kind.UNKNOWN, result.observation.kind);
    }

    @Test public void combinedNativeTicketLineUsesIndependentPriceAndQuantityCaptures() {
        Fixture f = new Fixture(false);
        f.combinedTicketLine("980元 × 1张");
        CheckoutParseResult result = f.parse();
        assertEquals(CheckoutParseResult.Status.PARSED, result.status);
        assertEquals(Long.valueOf(98000), result.observation.unitPriceFen);
        assertEquals(Integer.valueOf(1), result.observation.quantity);
    }

    @Test public void changedCombinedTicketLineNeverBackfillsConfiguredAmountOrCount() {
        for (String text : List.of("980元 × 2张", "980元 × 0张", "980元 × 1张 附加服务", "980元 × 张", "980.001元 × 1张")) {
            Fixture f = new Fixture(false); f.combinedTicketLine(text);
            assertNotParsed(f.parse());
        }
    }

    @Test public void combinedTicketLineCannotReplaceASecondIndependentRoleOrReuseItsNode() {
        Fixture f = new Fixture(false); f.combinedTicketLine("980元 × 1张");
        f.fields.put(Role.PAYABLE, CheckoutProfile.Selector.anchoredValue(id("amounts"), "0", "(980)元 × 1张"));
        assertEquals("node_reused_across_roles", f.parse().reason);
    }

    @Test public void combinedTicketLineContractRequiresTwoCapturesAndOneRepresentation() {
        for (String pattern : List.of("(.+)", "(.+)(.+)(.+)")) {
            try { CheckoutProfile.Selector.anchoredPriceQuantity(id("amounts"), "0", pattern); fail(); }
            catch (IllegalArgumentException expected) { assertEquals("price_quantity_two_captures_required", expected.getMessage()); }
        }
        Fixture f = new Fixture(false); f.combinedTicketLine("980元 × 1张");
        f.fields.put(Role.UNIT_PRICE, CheckoutProfile.Selector.value(id("unit"), id("amounts"), "(.+)"));
        try { f.profile(); fail(); }
        catch (IllegalArgumentException expected) { assertEquals("choose_one_price_quantity_representation", expected.getMessage()); }
    }

    @Test public void combinedTicketLineSelectorCannotReadIdentityOrSubmitFields() {
        for (Role role : List.of(Role.EVENT_TITLE, Role.VIEWER_NAME, Role.AGREEMENT, Role.SUBMIT)) {
            try {
                new CheckoutProfile.Builder("cn.damai", VERSION, ZONE).field(role,
                        CheckoutProfile.Selector.priceQuantity(id("line"), id("amounts"), "(.+) × (.+)"));
                fail();
            } catch (IllegalArgumentException expected) { assertEquals("price_quantity_role_required", expected.getMessage()); }
        }
    }

    @Test public void anchoredPublicStateStillRequiresZeroFeeAndNativeAgreementCheckedFlag() {
        Fixture f = new Fixture(false);
        f.anchoredLabel(Role.EXTRAS_STATUS, "extras_status", "extras", "0", "无附加项目");
        f.anchoredLabel(Role.AGREEMENT, "agreement", "actions", "0", "已阅读并同意购票协议");
        assertEquals(CheckoutParseResult.Status.PARSED, f.parse().status);
        f.flags("actions/0", true, false, false, true);
        assertFalse(f.parse().observation.agreementsAccepted);
        f.text("fee", "附加费用：1元");
        assertNotParsed(f.parse());
    }

    @Test public void changedOrMovedAnchoredStateDoesNotSatisfyPublicContract() {
        Fixture f = new Fixture(false);
        f.anchoredLabel(Role.EXTRAS_STATUS, "extras_status", "extras", "0", "无附加项目");
        f.text("extras/0", "有附加项目");
        assertNotParsed(f.parse());
        Fixture moved = new Fixture(false);
        moved.anchoredLabel(Role.EXTRAS_STATUS, "extras_status", "extras", "1", "无附加项目");
        moved.fields.put(Role.EXTRAS_STATUS, CheckoutProfile.Selector.anchoredPublicLabel(id("extras"), "0", "无附加项目"));
        assertNotParsed(moved.parse());
    }

    @Test public void exactAppVersionAndPackageRequired() {
        Fixture f = new Fixture(false);
        CheckoutFrame frame = f.frame();
        for (CheckoutFrame invalid : List.of(
                copy(frame, "cn.other", VERSION, 7, 0, 4, 4, NOW, true, frame.nodes),
                copy(frame, "cn.damai", "new-app-version", 7, 0, 4, 4, NOW, true, frame.nodes))) {
            assertEquals(CheckoutParseResult.Status.UNSUPPORTED,
                    CheckoutPageParser.parse(invalid, f.profile(), NOW, resolver()).status);
        }
    }

    @Test public void mixedGenerationIncompleteNonDefaultDisplayAndOldFrameRejected() {
        Fixture f = new Fixture(false); CheckoutFrame frame = f.frame();
        for (CheckoutFrame invalid : List.of(
                copy(frame, "cn.damai", VERSION, 7, 0, 4, 5, NOW, true, frame.nodes),
                copy(frame, "cn.damai", VERSION, 7, 0, 4, 4, NOW, false, frame.nodes),
                copy(frame, "cn.damai", VERSION, 7, 1, 4, 4, NOW, true, frame.nodes),
                copy(frame, "cn.damai", VERSION, 7, 0, 4, 4, NOW - 3001, true, frame.nodes),
                copy(frame, "cn.damai", VERSION, 7, 0, 4, 4, NOW + 1, true, frame.nodes))) {
            assertEquals(CheckoutParseResult.Status.INVALID_FRAME,
                    CheckoutPageParser.parse(invalid, f.profile(), NOW, resolver()).status);
        }
    }

    @Test public void wrongNativeWindowIsNotSilentlyIgnored() {
        Fixture f = new Fixture(false); CheckoutFrame.Node node = f.node("title");
        f.replace("title", new CheckoutFrame.Node(node.key, node.parentKey, node.item, true, false, false, false, 8));
        assertEquals(CheckoutParseResult.Status.INVALID_FRAME, f.parse().status);
    }

    @Test public void ocrFactsCannotJoinNativeFrame() {
        Fixture f = new Fixture(false); CheckoutFrame.Node node = f.node("title");
        UiItem item = node.item;
        f.replace("title", new CheckoutFrame.Node(node.key, node.parentKey,
                new UiItem(item.text, 1, 1, 50, 50, true, false, false, UiItem.Source.OCR, item.resourceId),
                true, false, false, false, 7));
        assertEquals(CheckoutParseResult.Status.INVALID_FRAME, f.parse().status);
    }

    @Test public void duplicateKeysMissingParentsAndCyclesInvalidateWholeTree() {
        Fixture duplicate = new Fixture(false); duplicate.nodes.add(duplicate.node("title"));
        assertEquals(CheckoutParseResult.Status.INVALID_FRAME, duplicate.parse().status);
        Fixture orphan = new Fixture(false); orphan.parent("title", "absent");
        assertEquals(CheckoutParseResult.Status.INVALID_FRAME, orphan.parse().status);
        Fixture cycle = new Fixture(false); cycle.parent("identity", "title");
        assertEquals(CheckoutParseResult.Status.INVALID_FRAME, cycle.parse().status);
    }

    @Test public void duplicateFieldRemainsAmbiguousEvenWhenSecondTextDoesNotMatch() {
        Fixture f = new Fixture(false); f.add("title2", "identity", "title", "推荐其他演出", false, false, false);
        assertEquals(CheckoutParseResult.Status.AMBIGUOUS, f.parse().status);
    }

    @Test public void duplicateMarkerOrRootRejected() {
        Fixture marker = new Fixture(false);
        marker.add("marker2", "page", "confirmation_marker", "确认购买", false, false, false);
        assertEquals(CheckoutParseResult.Status.AMBIGUOUS, marker.parse().status);
        Fixture root = new Fixture(false);
        root.add("page2", "window", "confirmation", "", false, false, false);
        assertEquals(CheckoutParseResult.Status.AMBIGUOUS, root.parse().status);
    }

    @Test public void mixedConfirmationAndPendingPagesRejected() {
        Fixture f = new Fixture(false);
        f.add("pending", "window", "pending", "", false, false, false);
        f.add("pending_marker", "pending", "pending_marker", "待付款", false, false, false);
        assertEquals(CheckoutParseResult.Status.AMBIGUOUS, f.parse().status);
    }

    @Test public void sharedRootAndTitleIdAreRoutedOnlyByExactConfirmationMarker() {
        Fixture f = new Fixture(false); f.sharedCheckoutRoot();
        CheckoutParseResult result = f.parse();
        assertEquals(CheckoutParseResult.Status.PARSED, result.status);
        assertEquals(CheckoutObservation.Kind.CONFIRMATION, result.observation.kind);
    }

    @Test public void sharedRootAndTitleIdAreRoutedOnlyByExactPendingMarker() {
        Fixture f = new Fixture(true); f.sharedCheckoutRoot();
        CheckoutParseResult result = f.parse();
        assertEquals(CheckoutParseResult.Status.PARSED, result.status);
        assertEquals(CheckoutObservation.Kind.PAYMENT_PENDING, result.observation.kind);
        assertTrue(result.observation.orderReferenceVerified);
        assertNull(result.observation.submitTarget);
    }

    @Test public void separateSharedRootProfilesDoNotBothClaimTheSamePage() {
        Fixture f = new Fixture(false); f.sharedCheckoutRoot();
        CheckoutProfile both = f.profile();
        for (CheckoutObservation.Kind kind : List.of(CheckoutObservation.Kind.CONFIRMATION, CheckoutObservation.Kind.PAYMENT_PENDING)) {
            CheckoutProfile.Page page = both.pages.get(kind);
            CheckoutProfile.Builder builder = new CheckoutProfile.Builder("cn.damai", VERSION, ZONE)
                    .page(kind, page.rootResourceId, page.marker);
            both.fields.forEach(builder::field);
            CheckoutParseResult result = CheckoutPageParser.parse(f.frame(), builder.build(), NOW, resolver());
            assertEquals(kind == CheckoutObservation.Kind.CONFIRMATION ? CheckoutParseResult.Status.PARSED
                    : CheckoutParseResult.Status.UNSUPPORTED, result.status);
        }
    }

    @Test public void unknownSharedRootTitleDoesNotClaimEitherCheckoutPage() {
        for (String label : List.of("", "确认购…", "待付", "支付成功", "待付款 其他文字")) {
            Fixture f = new Fixture(false); f.sharedCheckoutRoot(); f.text("marker", label);
            CheckoutParseResult result = f.parse();
            assertEquals(CheckoutParseResult.Status.UNSUPPORTED, result.status);
            assertEquals(CheckoutObservation.Kind.UNKNOWN, result.observation.kind);
            assertNull(result.observation.submitTarget);
        }
    }

    @Test public void sharedRootDuplicateMarkerIsAmbiguousEvenIfOnlyOneLabelMatches() {
        Fixture f = new Fixture(false); f.sharedCheckoutRoot();
        f.add("other_marker", "page", "shared_checkout_marker", "待付款", false, false, false);
        assertEquals(CheckoutParseResult.Status.AMBIGUOUS, f.parse().status);
    }

    @Test public void sharedRootMissingHiddenOrProtectedMarkerStillFailsClosed() {
        Fixture missing = new Fixture(false); missing.sharedCheckoutRoot(); missing.remove("marker");
        assertEquals(CheckoutParseResult.Status.INCOMPLETE, missing.parse().status);
        Fixture hidden = new Fixture(false); hidden.sharedCheckoutRoot(); hidden.flags("marker", false, false, false, false);
        assertEquals(CheckoutParseResult.Status.INCOMPLETE, hidden.parse().status);
        Fixture protectedMarker = new Fixture(false); protectedMarker.sharedCheckoutRoot(); protectedMarker.sensitive("marker");
        assertEquals(CheckoutParseResult.Status.INCOMPLETE, protectedMarker.parse().status);
    }

    @Test public void fieldFromOtherAncestorScopeCannotFillMissingFact() {
        Fixture f = new Fixture(false); f.parent("unit", "identity");
        assertNotParsed(f.parse());
        Fixture external = new Fixture(false); external.parent("title", "window");
        assertNotParsed(external.parse());
    }

    @Test public void sameNodeCannotCoverSubtotalAndPayableRoles() {
        Fixture f = new Fixture(false);
        f.fields.put(Role.PAYABLE, value("subtotal", "amounts", "票款小计：", "元"));
        assertEquals(CheckoutParseResult.Status.AMBIGUOUS, f.parse().status);
    }

    @Test public void pageMarkerCannotAlsoServeAsFullProjectTitle() {
        Fixture f = new Fixture(false);
        f.fields.put(Role.EVENT_TITLE, CheckoutProfile.Selector.value(id("confirmation_marker"), id("confirmation"), "(.+)"));
        assertEquals(CheckoutParseResult.Status.AMBIGUOUS, f.parse().status);
    }

    @Test public void unknownLabelsAndTruncatedTitlesAreNotAccepted() {
        Fixture label = new Fixture(false); label.text("unit", "参考最低价：980元");
        assertNotParsed(label.parse());
        Fixture truncated = new Fixture(false); truncated.text("title", "演出：杭州·合成演唱会…");
        assertNotParsed(truncated.parse());
        Fixture status = new Fixture(false); status.text("extras_status", "已添加附加项目");
        assertNotParsed(status.parse());
    }

    @Test public void strictMoneyDoesNotAcceptRangeNegativePrecisionOrThousands() {
        for (String value : List.of("980-1680", "-980", "980.001", "9,800", "0980", "NaN")) {
            Fixture f = new Fixture(false); f.text("unit", "单价：" + value + "元"); assertNotParsed(f.parse());
        }
    }

    @Test public void centsParsedExactlyWithoutFloatRounding() {
        Fixture f = new Fixture(false);
        f.text("unit", "单价：980.01元"); f.text("subtotal", "票款小计：980.01元"); f.text("payable", "应付：980.01元");
        assertEquals(Long.valueOf(98001), f.parse().observation.payableFen);
    }

    @Test public void amountMismatchFeeOrDiscountStopsParsing() {
        Fixture subtotal = new Fixture(false); subtotal.text("subtotal", "票款小计：1960元"); assertNotParsed(subtotal.parse());
        Fixture fee = new Fixture(false); fee.text("fee", "附加费用：1元"); assertNotParsed(fee.parse());
        Fixture discount = new Fixture(false); discount.text("payable", "应付：979元"); assertNotParsed(discount.parse());
    }

    @Test public void absentExtrasStatusOrZeroFeeDoesNotMeanNoExtras() {
        Fixture status = new Fixture(false); status.remove("extras_status"); assertNotParsed(status.parse());
        Fixture fee = new Fixture(false); fee.remove("fee"); assertNotParsed(fee.parse());
    }

    @Test public void dateMustContainActualYearAndBeCalendarValid() {
        for (String date : List.of("11-15", "2026-02-30", "2026-1-15")) {
            Fixture f = new Fixture(false); f.text("date", "日期：" + date); assertNotParsed(f.parse());
        }
        Fixture time = new Fixture(false); time.text("time", "时间：24:00"); assertNotParsed(time.parse());
    }

    @Test public void unknownCheckboxAndUncheckedAgreementNeverInheritSelectedFlag() {
        Fixture viewer = new Fixture(false); viewer.flags("selection", true, false, true, false);
        assertNotParsed(viewer.parse());
        Fixture agreement = new Fixture(false); agreement.flags("agreement", true, true, false, true);
        CheckoutParseResult result = agreement.parse();
        assertFalse(result.observation.agreementsAccepted);
        assertNull(new OrderLockEngine().evaluate(config(),grant(),result.observation,null,NOW).proposal);
    }

    @Test public void uniqueUnselectedViewerProducesProposalButNotVerifiedSelection() {
        Fixture f = new Fixture(false); f.flags("selection", true, true, false, false);
        CheckoutParseResult result = f.parse();
        assertEquals(CheckoutParseResult.Status.INCOMPLETE, result.status);
        assertEquals(ViewerMatcher.State.SELECT_PROPOSAL, result.viewerResult.state);
        assertFalse(result.observation.viewerSelectionVerified);
        assertEquals(LockAuthorization.viewerBindingHash(PERSON,"1234"), result.observation.selectedViewerBindingHash);
        assertEquals("", result.viewerResult.selectionTarget.text);
    }

    @Test public void declaredViewerCountMustEqualAllNativeRows() {
        Fixture f = new Fixture(false); f.text("viewer_count", "观演人共：2人");
        CheckoutParseResult result = f.parse();
        assertEquals(CheckoutParseResult.Status.INCOMPLETE, result.status);
        assertFalse(result.observation.viewerSelectionVerified);
    }

    @Test public void missingCountAndHiddenRowsAreNotCompleteViewerScope() {
        Fixture count = new Fixture(false); count.remove("viewer_count"); assertNotParsed(count.parse());
        Fixture hidden = new Fixture(false); hidden.flags("row", false, false, false, false); assertNotParsed(hidden.parse());
    }

    @Test public void protectedAncestorAndSensitiveSelectionStop() {
        Fixture ancestor = new Fixture(false); ancestor.sensitive("viewers"); assertNotParsed(ancestor.parse());
        Fixture node = new Fixture(false); node.sensitive("selection"); assertNotParsed(node.parse());
    }

    @Test public void duplicateCheckboxOrIncompleteViewerNameRejected() {
        Fixture duplicate = new Fixture(false); duplicate.add("selection2", "row", "selection", "", true, true, true);
        assertNotParsed(duplicate.parse());
        Fixture name = new Fixture(false); name.text("name", "姓名：示例*人"); assertNotParsed(name.parse());
    }

    @Test public void disabledSubmitAndChangedSubmitLabelStop() {
        Fixture disabled = new Fixture(false); CheckoutFrame.Node n = disabled.node("submit");
        UiItem i = n.item;
        disabled.replace("submit", new CheckoutFrame.Node(n.key, n.parentKey,
                new UiItem(i.text,i.left,i.top,i.right,i.bottom,false,true,false,UiItem.Source.NODE,i.resourceId),true,false,false,false,7));
        CheckoutParseResult result = disabled.parse();
        assertEquals(CheckoutParseResult.Status.PARSED,result.status);
        assertFalse(result.observation.submitTarget.enabled);
        assertNull(new OrderLockEngine().evaluate(config(),grant(),result.observation,null,NOW).proposal);
        Fixture changed = new Fixture(false); changed.text("submit", "立即支付"); assertNotParsed(changed.parse());
    }

    @Test public void unselectedViewerWithUncheckedAgreementAndDisabledSubmitCanOnlySelectViewer() {
        Fixture f = new Fixture(false);
        f.flags("selection",true,true,false,false);
        f.flags("agreement",true,true,false,false);
        CheckoutFrame.Node n=f.node("submit"); UiItem i=n.item;
        f.replace("submit",new CheckoutFrame.Node(n.key,n.parentKey,new UiItem(i.text,i.left,i.top,i.right,i.bottom,
                false,false,false,UiItem.Source.NODE,i.resourceId),true,false,false,false,7));
        CheckoutParseResult result=f.parse(); OrderLockEngine engine=new OrderLockEngine();
        assertEquals(CheckoutParseResult.Status.INCOMPLETE,result.status);
        assertEquals(ViewerMatcher.State.SELECT_PROPOSAL,result.viewerResult.state);
        assertFalse(result.observation.agreementsAccepted);
        assertFalse(result.observation.viewerSelectionVerified);
        assertFalse(result.observation.submitTarget.enabled);
        assertTrue(engine.canSelectViewer(config(),grant(),result.observation,result.viewerResult,null,NOW));
        assertNull(engine.evaluate(config(),grant(),result.observation,null,NOW).proposal);
    }

    @Test public void creationTimeMustBeExplicitFullTimestampNotDateOrCountdown() {
        for (String value : List.of("2026-10-25", "10:00:00", "09:58", "2026-10-25 10:00", "2026-10-25 10:00:03")) {
            Fixture f = new Fixture(true); f.text("created", "创建时间：" + value); assertNotParsed(f.parse());
        }
    }

    @Test public void creationTimestampCannotBeReadFromUnrelatedScope() {
        Fixture f = new Fixture(true); f.parent("created", "identity");
        CheckoutParseResult result = f.parse();
        assertEquals(CheckoutParseResult.Status.PARSED, result.status);
        assertNull(result.observation.orderCreatedAtMs);
        assertFalse(result.observation.orderCreationTimeVerified);
    }

    @Test public void countdownRejectsUnlabeledNumbersAndInvalidSeconds() {
        for (String text : List.of("598", "09:99", "100:00:00", "2026-11-15")) {
            Fixture f = new Fixture(true); f.fields.remove(Role.PAYMENT_DEADLINE);
            f.fields.put(Role.PAYMENT_COUNTDOWN, value("deadline", "order", "付款剩余：", ""));
            f.text("deadline", "付款剩余：" + text); assertNotParsed(f.parse());
        }
    }

    @Test public void ambiguousTimezoneTimestampIsNotGuessed() {
        Fixture f = new Fixture(true); f.zone = ZoneId.of("America/New_York");
        f.text("created", "创建时间：2026-11-01 01:30:00");
        assertNotParsed(f.parse());
    }

    @Test public void malformedResolverNeverLeaksExceptionOrViewerText() {
        Fixture f = new Fixture(false);
        CheckoutParseResult result = CheckoutPageParser.parse(f.frame(),f.profile(),NOW,(rows,complete)->{
            throw new IllegalStateException(PERSON + " secret");
        });
        assertEquals("checkout_parse_failed",result.reason);
        assertFalse(result.reason.contains(PERSON));
        assertEquals(CheckoutObservation.Kind.UNKNOWN,result.observation.kind);
    }

    @Test public void profileRequiresOneCaptureAndExactPackageIds() {
        try { CheckoutProfile.Selector.value(id("v"),id("scope"),"(x)(y)"); fail(); }
        catch (IllegalArgumentException expected) { assertEquals("one_capture_required",expected.getMessage()); }
        try { new CheckoutProfile.Builder("cn.damai",VERSION,ZONE).field(Role.CITY,
                CheckoutProfile.Selector.value("cn.other:id/city",id("scope"),"(.+)")); fail(); }
        catch (IllegalArgumentException expected) { assertEquals("explicit_package_resource_id_required",expected.getMessage()); }
    }

    @Test public void reviewedNoIdPublicTextNeedsUniqueAnchorExactPathAndWholeLabel() {
        Fixture f = new Fixture(false);
        f.anchoredValue(Role.EVENT_TITLE, "title", "identity", "0", "演出：(杭州·合成演唱会-杭州站)");
        assertEquals(CheckoutParseResult.Status.PARSED, f.parse().status);
        f.text("identity/0", "推荐演出：杭州·合成演唱会-杭州站");
        assertNotParsed(f.parse());
    }

    @Test public void structuralPathNeverFallsBackToNearbyOrChangedNodes() {
        Fixture moved = new Fixture(false);
        moved.anchoredValue(Role.EVENT_TITLE, "title", "identity", "0", "演出：(.+)");
        moved.parent("identity/0", "amounts");
        assertNotParsed(moved.parse());
        Fixture changedId = new Fixture(false);
        changedId.anchoredValue(Role.EVENT_TITLE, "title", "identity", "0", "演出：(.+)");
        CheckoutFrame.Node n=changedId.node("identity/0"); UiItem old=n.item;
        changedId.replace(n.key,new CheckoutFrame.Node(n.key,n.parentKey,new UiItem(old.text,old.left,old.top,old.right,old.bottom,
                old.enabled,old.clickable,old.selected,old.source,id("new_title")),true,false,false,false,7));
        assertNotParsed(changedId.parse());
    }

    @Test public void duplicateStructuralAnchorsAreAmbiguousEvenWithOneMatchingValue() {
        Fixture f = new Fixture(false);
        f.anchoredValue(Role.EVENT_TITLE, "title", "identity", "0", "演出：(.+)");
        f.add("other_identity","page","identity","",false,false,false);
        assertEquals(CheckoutParseResult.Status.AMBIGUOUS, f.parse().status);
    }

    @Test public void structuralPublicTextCannotReadProtectedAncestor() {
        Fixture f = new Fixture(false);
        f.anchoredValue(Role.EVENT_TITLE, "title", "identity", "0", "演出：(.+)");
        f.sensitive("identity");
        assertNotParsed(f.parse());
    }

    @Test public void structuralSelectorIsNotAvailableForIdentityOrActionRoles() {
        for (Role role : List.of(Role.SUBMIT, Role.AGREEMENT, Role.VIEWER_NAME, Role.VIEWER_DOCUMENT, Role.VIEWER_SELECTION, Role.ORDER_REFERENCE)) {
            try { new CheckoutProfile.Builder("cn.damai",VERSION,ZONE).field(role,
                    CheckoutProfile.Selector.anchoredValue(id("scope"),"0","(.+)")); fail("Protected/action structural role accepted"); }
            catch (IllegalArgumentException expected) { assertEquals("anchored_selector_public_value_only",expected.getMessage()); }
        }
        for (String path : List.of("", "../0", "0//1", "-1", "01", "1000", "0/")) {
            try { CheckoutProfile.Selector.anchoredValue(id("scope"),path,"(.+)"); fail("Invalid path accepted"); }
            catch (IllegalArgumentException expected) { assertEquals("explicit_child_path_required",expected.getMessage()); }
        }
    }

    @Test public void combinedSessionUsesOneNativeValueAndNormalizesObservedDotFormat() {
        Fixture f = new Fixture(false); f.combinedSession("2026.11.15 19:00");
        CheckoutParseResult result=f.parse();
        assertEquals(CheckoutParseResult.Status.PARSED,result.status);
        assertEquals("2026-11-15",result.observation.date); assertEquals("19:00",result.observation.time);
        f.text("identity/3","2026-11-15 19:00");
        assertEquals(CheckoutParseResult.Status.PARSED,f.parse().status);
    }

    @Test public void combinedSessionDoesNotRepairAmbiguousOrInvalidValues() {
        for (String value : List.of("2026.11-15 19:00","2026.02.30 19:00","2026.11.15 25:00","11.15 19:00","2026.11.15 19:00 第二场")) {
            Fixture f=new Fixture(false); f.combinedSession(value); assertNotParsed(f.parse());
        }
        Fixture mixed=new Fixture(false); mixed.combinedSession("2026.11.15 19:00");
        mixed.fields.put(Role.SESSION_DATE,value("date","identity","日期：",""));
        try { mixed.profile(); fail("Two session sources accepted"); }
        catch (IllegalArgumentException expected) { assertEquals("choose_one_session_representation",expected.getMessage()); }
    }

    @Test public void submitLabelMustMatchTheSpecificProfileAndCannotBePayment() {
        Fixture f=new Fixture(false); f.text("submit","立即提交");
        assertNotParsed(f.parse());
        f.fields.put(Role.SUBMIT,new CheckoutProfile.Selector(id("submit"),id("actions"),"立即提交"));
        assertEquals(CheckoutParseResult.Status.PARSED,f.parse().status);
        assertEquals("立即提交",f.parse().observation.submitTarget.text);
        try { new CheckoutProfile.Builder("cn.damai",VERSION,ZONE).field(Role.SUBMIT,
                new CheckoutProfile.Selector(id("submit"),id("actions"),"立即支付")); fail("Payment action accepted"); }
        catch (IllegalArgumentException expected) { assertEquals("explicit_submit_label_required",expected.getMessage()); }
    }

    private static CheckoutPageParser.ViewerResolver resolver() {
        return (rows,complete) -> ViewerMatcher.match(PERSON,"1234",rows,complete);
    }
    @Test public void twoTicketsNeedBothRequestedPeopleSelectedInTheSameNativeScope() {
        Fixture f = twoPeopleFixture();
        List<ViewerMatcher.RequestedViewer> requested = List.of(new ViewerMatcher.RequestedViewer(PERSON,"1234"),
                new ViewerMatcher.RequestedViewer("第二位合成观演人","5678"));
        CheckoutParseResult result = CheckoutPageParser.parse(f.frame(),f.profile(),NOW,
                (rows,complete) -> ViewerMatcher.matchMany(requested,rows,complete));
        assertEquals(CheckoutParseResult.Status.PARSED,result.status);
        assertEquals(Integer.valueOf(2),result.observation.quantity);
        assertTrue(result.observation.viewerSelectionVerified);
        assertEquals(2,result.viewerResult.viewerCount);
        assertEquals(LockAuthorization.viewerBindingsHash(requested),result.observation.selectedViewerBindingHash);
        f.flags("selection2",true,true,false,false);
        result = CheckoutPageParser.parse(f.frame(),f.profile(),NOW,
                (rows,complete) -> ViewerMatcher.matchMany(requested,rows,complete));
        assertEquals(CheckoutParseResult.Status.INCOMPLETE,result.status);
        assertFalse(result.observation.viewerSelectionVerified);
        assertEquals(ViewerMatcher.State.SELECT_PROPOSAL,result.viewerResult.state);
    }
    @Test public void matchedViewerCountMustEqualTicketQuantityBeforeAnySelectionProposalOrVerification() {
        Fixture twoTickets = new Fixture(false);
        twoTickets.text("quantity","数量：2张"); twoTickets.text("subtotal","票款小计：1960元"); twoTickets.text("payable","应付：1960元");
        CheckoutParseResult result = twoTickets.parse();
        assertNotParsed(result); assertEquals("",result.observation.selectedViewerBindingHash); assertNull(result.viewerResult);
        Fixture oneTicket = twoPeopleFixture();
        oneTicket.text("quantity","数量：1张"); oneTicket.text("subtotal","票款小计：980元"); oneTicket.text("payable","应付：980元");
        List<ViewerMatcher.RequestedViewer> requested = List.of(new ViewerMatcher.RequestedViewer(PERSON,"1234"),
                new ViewerMatcher.RequestedViewer("第二位合成观演人","5678"));
        result = CheckoutPageParser.parse(oneTicket.frame(),oneTicket.profile(),NOW,
                (rows,complete) -> ViewerMatcher.matchMany(requested,rows,complete));
        assertNotParsed(result); assertEquals("",result.observation.selectedViewerBindingHash); assertNull(result.viewerResult);
    }
    private static Fixture twoPeopleFixture() {
        Fixture f = new Fixture(false);
        f.text("quantity","数量：2张"); f.text("subtotal","票款小计：1960元"); f.text("payable","应付：1960元");
        f.text("viewer_count","观演人共：2人");
        f.add("row2","viewers","row","",false,false,false);
        f.add("name2","row2","name","姓名：第二位合成观演人",false,false,false);
        f.add("document2","row2","document","证件：****5678",false,false,false);
        f.add("selection2","row2","selection","",true,true,true);
        return f;
    }
    @Test public void structuralSubmitProducesAProfileBindingOnlyAfterExactParsing() {
        Fixture f=new Fixture(false); f.remove("submit");
        f.add("actions/0","actions","submit_group","",false,false,false);
        f.nodes.add(new CheckoutFrame.Node("actions/0/4","actions/0",new UiItem("立即提交",700,2300,1100,2450,true,true,false,UiItem.Source.NODE,""),
                true,false,false,false,7));
        f.fields.put(Role.SUBMIT,CheckoutProfile.Selector.anchoredSubmit(id("actions"),"0/4"));
        CheckoutParseResult result=f.parse();
        assertEquals(CheckoutParseResult.Status.PARSED,result.status);
        assertNotNull(result.observation.submitBinding);
        assertTrue(result.observation.submitBinding.matchesProfile(f.profile()));
        assertEquals("",result.observation.submitTarget.resourceId);
        f.text("actions/0/4","立即支付"); assertNotParsed(f.parse());
    }
    private static MonitorConfig config() {
        return new MonitorConfig("合成演唱会","杭州","2026-11-15","19:00",List.of(980),1,980,
                Instant.ofEpochMilli(NOW+3_600_000),3000,6000,MonitorConfig.Mode.LOCK_ORDER);
    }
    private static LockAuthorization grant() {
        return new LockAuthorization("synthetic-explicit-grant",config(),"杭州·合成演唱会-杭州站",
                LockAuthorization.viewerBindingHash(PERSON,"1234"),NOW-1000,NOW+600_000);
    }
    private static void assertNotParsed(CheckoutParseResult result) {
        assertNotEquals(CheckoutParseResult.Status.PARSED,result.status);
        assertFalse(result.observation.viewerSelectionVerified);
    }
    private static CheckoutProfile.Selector value(String field,String scope,String prefix,String suffix) {
        return CheckoutProfile.Selector.value(id(field),id(scope),java.util.regex.Pattern.quote(prefix)+"(.+)"+java.util.regex.Pattern.quote(suffix));
    }
    private static CheckoutFrame copy(CheckoutFrame ignored,String pkg,String version,int window,int display,
                                      long before,long after,long at,boolean complete,List<CheckoutFrame.Node> nodes) {
        return new CheckoutFrame(pkg,version,window,display,before,after,at,complete,nodes);
    }
    private static final class Fixture {
        final List<CheckoutFrame.Node> nodes = new ArrayList<>();
        final Map<Role,CheckoutProfile.Selector> fields = new EnumMap<>(Role.class);
        ZoneId zone = ZONE;
        boolean sharedPageRoot;
        Fixture(boolean pending) {
            add("window","","window","",false,false,false);
            add("page","window",pending?"pending":"confirmation","",false,false,false);
            add("marker","page",pending?"pending_marker":"confirmation_marker",pending?"待付款":"确认购买",false,false,false);
            for (String group : List.of("identity","amounts","viewers","extras","actions","order"))
                add(group,"page",group,"",false,false,false);
            addValue(Role.EVENT_TITLE,"title","identity","演出：","杭州·合成演唱会-杭州站","");
            addValue(Role.CITY,"city","identity","城市：","杭州","");
            addValue(Role.SESSION_DATE,"date","identity","日期：","2026-11-15","");
            addValue(Role.SESSION_TIME,"time","identity","时间：","19:00","");
            addValue(Role.UNIT_PRICE,"unit","amounts","单价：","980","元");
            addValue(Role.QUANTITY,"quantity","amounts","数量：","1","张");
            addValue(Role.TICKET_SUBTOTAL,"subtotal","amounts","票款小计：","980","元");
            addValue(Role.PAYABLE,"payable","amounts","应付：","980","元");
            addValue(Role.VIEWER_COUNT,"viewer_count","viewers","观演人共：","1","人");
            add("row","viewers","row","",false,false,false);
            fields.put(Role.VIEWER_ROW,CheckoutProfile.Selector.node(id("row"),id("viewers")));
            addValue(Role.VIEWER_NAME,"name","row","姓名：",PERSON,"");
            addValue(Role.VIEWER_DOCUMENT,"document","row","证件：","****1234","");
            add("selection","row","selection","",true,true,true);
            fields.put(Role.VIEWER_SELECTION,CheckoutProfile.Selector.node(id("selection"),id("row")));
            add("extras_status","extras","extras_status","无附加项目",false,false,false);
            fields.put(Role.EXTRAS_STATUS,new CheckoutProfile.Selector(id("extras_status"),id("extras"),"无附加项目"));
            addValue(Role.EXTRA_FEE,"fee","extras","附加费用：","0","元");
            fields.put(Role.AGREEMENT,new CheckoutProfile.Selector(id("agreement"),id("actions"),"已阅读并同意购票协议"));
            fields.put(Role.SUBMIT,new CheckoutProfile.Selector(id("submit"),id("actions"),"提交订单"));
            fields.put(Role.ORDER_REFERENCE,value("reference","order","订单号：",""));
            fields.put(Role.ORDER_CREATED_AT,value("created","order","创建时间：",""));
            fields.put(Role.PAYMENT_DEADLINE,value("deadline","order","支付截止：",""));
            if (pending) {
                add("reference","order","reference","订单号：TESTORDER12345",false,false,false);
                add("created","order","created","创建时间：2026-10-25 10:00:00",false,false,false);
                add("deadline","order","deadline","支付截止：2026-10-25 10:10:00",false,false,false);
            } else {
                add("agreement","actions","agreement","已阅读并同意购票协议",true,true,true);
                add("submit","actions","submit","提交订单",true,false,false);
            }
        }
        void addValue(Role role,String key,String scope,String prefix,String val,String suffix) {
            add(key,scope,key,prefix+val+suffix,false,false,false); fields.put(role,value(key,scope,prefix,suffix));
        }
        void anchoredValue(Role role,String oldKey,String anchor,String path,String regex) {
            CheckoutFrame.Node old=node(oldKey); remove(oldKey); UiItem i=old.item;
            nodes.add(new CheckoutFrame.Node(anchor+"/"+path,anchor,new UiItem(i.text,i.left,i.top,i.right,i.bottom,
                    i.enabled,i.clickable,i.selected,i.source,""),old.visible,old.checked,old.checkable,old.sensitive,old.windowId));
            fields.put(role,CheckoutProfile.Selector.anchoredValue(id(anchor),path,regex));
        }
        void combinedSession(String text) {
            fields.remove(Role.SESSION_DATE); fields.remove(Role.SESSION_TIME); remove("date"); remove("time");
            nodes.add(new CheckoutFrame.Node("identity/3","identity",new UiItem(text,1,100,300,130,true,false,false,UiItem.Source.NODE,""),
                    true,false,false,false,7));
            fields.put(Role.SESSION_DATETIME,CheckoutProfile.Selector.anchoredValue(id("identity"),"3","(.+)"));
        }
        void combinedTicketLine(String text) {
            fields.remove(Role.UNIT_PRICE); fields.remove(Role.QUANTITY); remove("unit"); remove("quantity");
            nodes.add(new CheckoutFrame.Node("amounts/0", "amounts",
                    new UiItem(text, 1, 100, 300, 130, true, false, false, UiItem.Source.NODE, ""),
                    true, false, false, false, 7));
            fields.put(Role.UNIT_PRICE_QUANTITY, CheckoutProfile.Selector.anchoredPriceQuantity(id("amounts"), "0",
                    "([0-9.]+)元 × ([0-9]+)张"));
        }
        void anchoredLabel(Role role, String oldKey, String anchor, String path, String label) {
            CheckoutFrame.Node old = node(oldKey); remove(oldKey); UiItem i = old.item;
            nodes.add(new CheckoutFrame.Node(anchor + "/" + path, anchor,
                    new UiItem(i.text, i.left, i.top, i.right, i.bottom, i.enabled, i.clickable, i.selected, i.source, ""),
                    old.visible, old.checked, old.checkable, old.sensitive, old.windowId));
            fields.put(role, CheckoutProfile.Selector.anchoredPublicLabel(id(anchor), path, label));
        }
        void add(String key,String parent,String resource,String text,boolean clickable,boolean checkable,boolean checked) {
            int y=nodes.size()*20+1;
            nodes.add(new CheckoutFrame.Node(key,parent,new UiItem(text,1,y,101,y+19,true,clickable,false,UiItem.Source.NODE,id(resource)),
                    true,checked,checkable,false,7));
        }
        CheckoutFrame.Node node(String key) { return nodes.stream().filter(n->n.key.equals(key)).findFirst().orElseThrow(); }
        void remove(String key) { nodes.removeIf(n->n.key.equals(key)); }
        void replace(String key,CheckoutFrame.Node replacement) { nodes.set(nodes.indexOf(node(key)),replacement); }
        void text(String key,String text) {
            CheckoutFrame.Node n=node(key); UiItem i=n.item;
            replace(key,new CheckoutFrame.Node(n.key,n.parentKey,new UiItem(text,i.left,i.top,i.right,i.bottom,i.enabled,i.clickable,i.selected,i.source,i.resourceId),
                    n.visible,n.checked,n.checkable,n.sensitive,n.windowId));
        }
        void parent(String key,String parent) {
            CheckoutFrame.Node n=node(key); replace(key,new CheckoutFrame.Node(n.key,parent,n.item,n.visible,n.checked,n.checkable,n.sensitive,n.windowId));
        }
        void flags(String key,boolean visible,boolean checkable,boolean checked,boolean selected) {
            CheckoutFrame.Node n=node(key); UiItem i=n.item;
            replace(key,new CheckoutFrame.Node(n.key,n.parentKey,new UiItem(i.text,i.left,i.top,i.right,i.bottom,i.enabled,i.clickable,selected,i.source,i.resourceId),
                    visible,checked,checkable,n.sensitive,n.windowId));
        }
        void sensitive(String key) { CheckoutFrame.Node n=node(key);replace(key,new CheckoutFrame.Node(n.key,n.parentKey,n.item,n.visible,n.checked,n.checkable,true,n.windowId)); }
        void sharedCheckoutRoot() {
            sharedPageRoot = true;
            for (String key : List.of("page", "marker")) {
                CheckoutFrame.Node n = node(key); UiItem i = n.item;
                String resource = key.equals("page") ? "shared_checkout_root" : "shared_checkout_marker";
                replace(key, new CheckoutFrame.Node(n.key, n.parentKey,
                        new UiItem(i.text, i.left, i.top, i.right, i.bottom, i.enabled, i.clickable, i.selected, i.source, id(resource)),
                        n.visible, n.checked, n.checkable, n.sensitive, n.windowId));
            }
        }
        CheckoutFrame frame() { return new CheckoutFrame("cn.damai",VERSION,7,0,4,4,NOW,true,nodes); }
        CheckoutProfile profile() {
            CheckoutProfile.Builder b=new CheckoutProfile.Builder("cn.damai",VERSION,zone);
            if (sharedPageRoot) {
                b.page(CheckoutObservation.Kind.CONFIRMATION, id("shared_checkout_root"),
                        new CheckoutProfile.Selector(id("shared_checkout_marker"), id("shared_checkout_root"), "确认购买"));
                b.page(CheckoutObservation.Kind.PAYMENT_PENDING, id("shared_checkout_root"),
                        new CheckoutProfile.Selector(id("shared_checkout_marker"), id("shared_checkout_root"), "待付款"));
            } else {
                b.page(CheckoutObservation.Kind.CONFIRMATION,id("confirmation"),new CheckoutProfile.Selector(id("confirmation_marker"),id("confirmation"),"确认购买"))
                        .page(CheckoutObservation.Kind.PAYMENT_PENDING,id("pending"),new CheckoutProfile.Selector(id("pending_marker"),id("pending"),"待付款"));
            }
            for (Map.Entry<Role,CheckoutProfile.Selector> entry:fields.entrySet()) b.field(entry.getKey(),entry.getValue());
            return b.build();
        }
        CheckoutParseResult parse() { return CheckoutPageParser.parse(frame(),profile(),NOW,resolver(),"local-routing-only"); }
    }
}
