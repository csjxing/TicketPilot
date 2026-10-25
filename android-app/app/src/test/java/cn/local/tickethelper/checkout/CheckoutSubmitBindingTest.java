package cn.local.tickethelper.checkout;

import cn.local.tickethelper.core.UiItem;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic structural fixtures only; they are never registered as a live app profile. */
public final class CheckoutSubmitBindingTest {
    private static final String VERSION="synthetic-submit-1";
    private static String id(String name) { return "cn.damai:id/synthetic_"+name; }
    private static CheckoutProfile profile(String version,String path) {
        return new CheckoutProfile.Builder("cn.damai",version,ZoneId.of("Asia/Shanghai"))
                .page(CheckoutObservation.Kind.CONFIRMATION,id("page"),new CheckoutProfile.Selector(id("marker"),id("page"),"确认购买"))
                .field(CheckoutProfile.Role.SUBMIT,CheckoutProfile.Selector.anchoredSubmit(id("actions"),path)).build();
    }
    private static UiItem target() { return new UiItem("立即提交",700,2500,1100,2650,true,true,false,UiItem.Source.NODE,""); }
    private static CheckoutFrame.Node node(String key,String parent,String resource,String text,boolean clickable) {
        return new CheckoutFrame.Node(key,parent,new UiItem(text,700,2500,1100,2650,true,clickable,false,UiItem.Source.NODE,resource),
                true,false,false,false,7);
    }
    private static List<CheckoutFrame.Node> nodes() {
        return new ArrayList<>(List.of(node("root","",id("window"),"",false),node("root/0","root",id("page"),"",false),
                node("root/0/0","root/0",id("marker"),"确认购买",false),node("root/0/1","root/0",id("actions"),"",false),
                node("root/0/1/0","root/0/1","","",false),node("root/0/1/0/4","root/0/1/0","","立即提交",true)));
    }
    private static CheckoutFrame frame(List<CheckoutFrame.Node> nodes) { return new CheckoutFrame("cn.damai",VERSION,7,0,4,4,1,true,nodes); }
    private static CheckoutSubmitBinding binding() { return CheckoutSubmitBinding.from(profile(VERSION,"0/4")); }

    @Test public void exactProfilePathResolvesOnlyItsLiveNoIdControl() {
        assertEquals("root/0/1/0/4",binding().resolve(frame(nodes()),target()));
        assertTrue(binding().matchesProfile(profile(VERSION,"0/4")));
        assertFalse(binding().matchesProfile(profile("new-version","0/4")));
        assertFalse(binding().matchesProfile(profile(VERSION,"0/3")));
    }
    @Test public void wrongVersionGenerationAndIncompleteFrameCannotResolve() {
        for (CheckoutFrame invalid:List.of(new CheckoutFrame("cn.damai","new-version",7,0,4,4,1,true,nodes()),
                new CheckoutFrame("cn.damai",VERSION,7,0,4,5,1,true,nodes()),new CheckoutFrame("cn.damai",VERSION,7,0,4,4,1,false,nodes()),
                new CheckoutFrame("other.app",VERSION,7,0,4,4,1,true,nodes()),new CheckoutFrame("cn.damai",VERSION,8,0,4,4,1,true,nodes())))
            assertEquals("",binding().resolve(invalid,target()));
    }
    @Test public void duplicatePageMarkerOrAnchorFailClosed() {
        for (String duplicate:List.of("page","marker","actions")) {
            List<CheckoutFrame.Node> nodes=nodes();
            nodes.add(node("root/0/9","root/0",id(duplicate),duplicate.equals("marker")?"确认购买":"",false));
            assertEquals("",binding().resolve(frame(nodes),target()));
        }
    }
    @Test public void changedTextIdOrAncestorCannotUseTheSameCoordinates() {
        for (CheckoutFrame.Node replacement:List.of(node("root/0/1/0/4","root/0/1/0","","立即支付",true),
                node("root/0/1/0/4","root/0/1/0",id("new_submit"),"立即提交",true),
                node("root/0/1/0/4","root/0","","立即提交",true),
                node("root/0/1/0/4","root/0/1/0","","立即提交",false))) {
            List<CheckoutFrame.Node> nodes=nodes(); nodes.set(5,replacement);
            assertEquals("",binding().resolve(frame(nodes),target()));
        }
        List<CheckoutFrame.Node> nodes=nodes(); nodes.set(2,node("root/0/0","root/0",id("marker"),"待付款",false));
        assertEquals("",binding().resolve(frame(nodes),target()));
    }
    @Test public void hiddenProtectedOrMovedControlIsNeverReturned() {
        for (int scenario=0;scenario<3;scenario++) {
            List<CheckoutFrame.Node> nodes=nodes(); CheckoutFrame.Node old=nodes.get(3);
            nodes.set(3,new CheckoutFrame.Node(old.key,old.parentKey,old.item,scenario != 0,false,false,scenario == 1,7));
            UiItem expected=scenario == 2?new UiItem("立即提交",701,2500,1100,2650,true,true,false,UiItem.Source.NODE,""):target();
            assertEquals("",binding().resolve(frame(nodes),expected));
        }
    }
    @Test public void structuralSubmitCannotBeAssignedToAnotherRoleOrNativeIdContract() {
        try { new CheckoutProfile.Builder("cn.damai",VERSION,ZoneId.of("Asia/Shanghai"))
                .field(CheckoutProfile.Role.AGREEMENT,CheckoutProfile.Selector.anchoredSubmit(id("actions"),"0/4")); fail(); }
        catch (IllegalArgumentException expected) { assertEquals("anchored_action_submit_only",expected.getMessage()); }
        CheckoutProfile nativeId=new CheckoutProfile.Builder("cn.damai",VERSION,ZoneId.of("Asia/Shanghai"))
                .page(CheckoutObservation.Kind.CONFIRMATION,id("page"),new CheckoutProfile.Selector(id("marker"),id("page"),"确认购买"))
                .field(CheckoutProfile.Role.SUBMIT,new CheckoutProfile.Selector(id("submit"),id("actions"),"立即提交")).build();
        assertNull(CheckoutSubmitBinding.from(nativeId));
        assertFalse(binding().matchesTarget(new UiItem("立即提交",700,2500,1100,2650,true,true,false,UiItem.Source.OCR,"")));
    }
}
