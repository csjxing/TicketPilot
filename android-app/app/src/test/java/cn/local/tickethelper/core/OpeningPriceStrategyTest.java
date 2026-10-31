package cn.local.tickethelper.core;
import org.junit.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.Assert.*;
public class OpeningPriceStrategyTest {
    final Instant sale=Instant.parse("2026-11-03T06:00:00Z");
    MonitorConfig config(){return new MonitorConfig("孙燕姿","广州","2026-11-27","19:00",List.of(1380,1680),2,3360,
            Instant.parse("2026-11-27T11:00:00Z"),500,1500,MonitorConfig.Mode.PREPARE,sale,30).withOpeningPrice(1680);}
    @Test public void primaryOnlyUntilExplicitShortageThenBothInPriorityOrder(){
        MonitorConfig c=config();OpeningPriceStrategy p=new OpeningPriceStrategy();p.begin(c,sale.toEpochMilli()-1000);
        assertEquals(List.of(1680),p.prices(c));
        assertFalse(p.shortage(c,new Decision(Decision.State.UNKNOWN,Decision.Action.NONE,null,"all_target_tiers_explicitly_unavailable")));
        assertEquals(List.of(1680),p.prices(c));
        assertTrue(p.shortage(c,new Decision(Decision.State.OUT_OF_STOCK,Decision.Action.NONE,null,"all_target_tiers_explicitly_unavailable")));
        assertEquals(List.of(1680,1380),p.prices(c));
        assertFalse(p.shortage(c,new Decision(Decision.State.OUT_OF_STOCK,Decision.Action.NONE,null,"all_target_tiers_explicitly_unavailable")));
    }
    @Test public void unrelatedOrAmbiguousShortageCannotReleaseFirstPrice(){
        MonitorConfig c=config();OpeningPriceStrategy p=new OpeningPriceStrategy();p.begin(c,sale.toEpochMilli());
        assertFalse(p.shortage(c,new Decision(Decision.State.OUT_OF_STOCK,Decision.Action.NONE,null,"unknown")));
        assertFalse(p.shortage(c.withOpeningPrice(1380),new Decision(Decision.State.OUT_OF_STOCK,Decision.Action.NONE,null,"selected_session_explicitly_unavailable")));
        assertEquals(List.of(1680),p.prices(c));
    }
    @Test public void lateStartUsesBothAndAnotherRunRestartsFirstAttempt(){
        MonitorConfig c=config();OpeningPriceStrategy p=new OpeningPriceStrategy();p.begin(c,sale.toEpochMilli()+60000);
        assertEquals(List.of(1680,1380),p.prices(c));p.begin(c,sale.toEpochMilli()-1000);assertEquals(List.of(1680),p.prices(c));
    }
    @Test public void ordinaryEditsKeepSameSessionPolicyButNewTargetsDoNotInherit(){
        MonitorConfig c=config();MonitorConfig legacy=new MonitorConfig(c.eventKeyword,c.city,c.date,c.time,c.prices,c.quantity,c.maxTotal,c.deadline,1000,3000,c.mode);
        assertEquals(1680,legacy.resolveLegacyTiming(c).openingPrice);
        assertEquals(1680,c.withTiming(sale,60).openingPrice);
        assertEquals(0,legacy.withOpeningPrice(0).resolveLegacyTiming(c).openingPrice);
        MonitorConfig other=new MonitorConfig(c.eventKeyword,"长沙",c.date,c.time,c.prices,c.quantity,c.maxTotal,c.deadline,500,1500,c.mode);
        assertEquals(0,other.resolveLegacyTiming(c).openingPrice);
    }
}
