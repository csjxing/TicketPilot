package cn.local.tickethelper;
import org.junit.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.Assert.*;
public class CatalogOpeningTimeTest {
    @Test public void freshCatalogOpeningCannotLeakIntoAnotherSnapshot() {
        Instant opening=Instant.parse("2026-11-03T06:00:00Z");
        CatalogStore.update("TIERS","","",null,null,List.of(),List.of(),List.of(),1000,opening);
        assertEquals(opening,CatalogStore.get().openingAt);
        long revision=CatalogStore.get().revision;
        CatalogStore.update("IDLE","","",null,null,List.of(),List.of(),List.of(),0);
        assertNull(CatalogStore.get().openingAt);assertTrue(CatalogStore.get().revision>revision);
    }
}
