package cn.local.tickethelper;
import java.time.Instant;
import org.junit.Test;
import static org.junit.Assert.*;
public final class SaleTimeCaptureTest {
    @Test public void capturedResultCannotSurvivePlanChangeExpiryOrClockRollback() {
        SaleTimeCapture.Result r=new SaleTimeCapture.Result(3,7,1000,Instant.ofEpochMilli(900000),false,"");
        assertTrue(r.usable(7,1100,5000));
        assertFalse(r.usable(8,1100,5000));
        assertFalse(r.usable(7,301001,5000));
        assertFalse(r.usable(7,999,5000));
        assertFalse(r.usable(7,1100,900000));
    }
    @Test public void unfinishedOrFailedReadIsNotAUsableTime() {
        assertFalse(new SaleTimeCapture.Result(3,7,0,null,true,"").usable(7,1100,5000));
        assertFalse(new SaleTimeCapture.Result(3,7,1000,null,false,"").usable(7,1100,5000));
    }
}
