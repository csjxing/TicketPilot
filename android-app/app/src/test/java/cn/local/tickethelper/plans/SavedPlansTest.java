package cn.local.tickethelper.plans;
import org.junit.Test;
import java.time.Instant;
import java.util.*;
import cn.local.tickethelper.core.MonitorConfig;
import static org.junit.Assert.*;
public class SavedPlansTest {
    private MonitorConfig plan(String city,String date,int price,MonitorConfig.Mode mode) {
        return new MonitorConfig("测试演唱会",city,date,"19:00",List.of(price),2,price*2,
                Instant.parse("2026-12-31T10:00:00Z"),500,1500,mode,Instant.parse("2026-11-01T07:00:00Z"),30);
    }
    @Test public void differentCitiesAndDatesSurviveRoundTripWithOwnTimingAndPrices() {
        MonitorConfig a=plan("长沙","2026-11-06",1680,MonitorConfig.Mode.PREPARE);
        MonitorConfig b=plan("广州","2026-11-13",1380,MonitorConfig.Mode.ALERT).withTiming(Instant.parse("2026-11-02T07:00:00Z"),60);
        List<MonitorConfig> x=SavedPlans.read(SavedPlans.write(SavedPlans.put(SavedPlans.put(List.of(),a),b)));
        assertEquals(2,x.size());assertEquals("广州",x.get(0).city);assertEquals(List.of(1380),x.get(0).prices);
        assertEquals(b.saleAt,x.get(0).saleAt);assertEquals(60,x.get(0).followUpMinutes);assertEquals(a.saleAt,x.get(1).saleAt);
        assertEquals(3,SavedPlans.put(x,plan("广州","2026-11-14",1380,MonitorConfig.Mode.ALERT)).size());
    }
    @Test public void editingSameSessionReplacesOnlyItsSettings() {
        List<MonitorConfig> x=SavedPlans.put(List.of(),plan("长沙","2026-11-06",1680,MonitorConfig.Mode.PREPARE));
        x=SavedPlans.put(x,plan("广州","2026-11-13",1680,MonitorConfig.Mode.PREPARE));
        x=SavedPlans.put(x,plan("长沙","2026-11-06",980,MonitorConfig.Mode.PREPARE));
        assertEquals(2,x.size());assertEquals(List.of(980),x.get(0).prices);assertEquals("广州",x.get(1).city);
    }
    @Test public void libraryNeverRestoresLockOrderAuthorityOrUnspecifiedTiming() {
        MonitorConfig c=plan("广州","2026-11-13",1680,MonitorConfig.Mode.LOCK_ORDER).withTiming(null,0);
        MonitorConfig saved=SavedPlans.read(SavedPlans.write(List.of(c))).get(0);
        assertEquals(MonitorConfig.Mode.PREPARE,saved.mode);assertTrue(saved.timingSpecified);assertNull(saved.saleAt);
        String json=SavedPlans.write(List.of(c));assertFalse(json.contains("viewer"));assertFalse(json.contains("LOCK_ORDER"));
    }
    @Test public void invalidDraftDoesNotOverwriteArchivedPlan() {
        MonitorConfig old=plan("长沙","2026-11-06",1680,MonitorConfig.Mode.PREPARE);
        MonitorConfig empty=new MonitorConfig("","","","",List.of(),1,0,null,500,1500,MonitorConfig.Mode.ALERT);
        assertEquals(1,SavedPlans.put(List.of(old),empty).size());
    }
    @Test public void corruptionCannotSilentlyEraseLibrary() {
        for(String bad:List.of("garbage","[{}]",SavedPlans.write(List.of(plan("广州","2026-11-13",1680,MonitorConfig.Mode.ALERT))).replace("1680","-1"))) {
            try{SavedPlans.read(bad);fail();}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void fullLibraryDoesNotSilentlyEvictOlderSessions() {
        List<MonitorConfig> x=List.of();
        for(int i=0;i<SavedPlans.LIMIT;i++)x=SavedPlans.put(x,plan("城市"+i,"2026-11-13",1680,MonitorConfig.Mode.ALERT));
        try{SavedPlans.put(x,plan("新城市","2026-11-13",1680,MonitorConfig.Mode.ALERT));fail();}catch(IllegalArgumentException expected){}
        assertEquals(SavedPlans.LIMIT,SavedPlans.put(x,plan("城市1","2026-11-13",1380,MonitorConfig.Mode.ALERT)).size());
    }
}
