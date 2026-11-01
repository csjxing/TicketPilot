package cn.local.tickethelper.computer;
import org.junit.Test;
import static org.junit.Assert.*;
public class ComputerLeaseTest {
    private static final String S="11111111111111111111111111111111",A="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",B="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    @Test public void claimIsExclusiveAndDoesNotExtendLifetime(){
        ComputerLease l=new ComputerLease(S,"",100000,1000).claim(A,100100,1100);
        assertTrue(l.permits(A,100200,1200));assertFalse(l.permits(B,100200,1200));
        assertEquals(899800,l.remaining(100200,1200));
        try{l.claim(B,100300,1300);fail();}catch(IllegalArgumentException expected){}
        assertEquals(100000,l.claim(A,100400,1400).startedWall);
    }
    @Test public void expiryRollbackAndClockChangesFailClosed(){
        ComputerLease l=new ComputerLease(S,A,100000,1000);
        assertEquals(0,l.remaining(100000+ComputerLease.DURATION_MS,1000+ComputerLease.DURATION_MS));
        assertEquals(0,l.remaining(99999,1100));assertEquals(0,l.remaining(100100,999));
        assertEquals(0,l.remaining(130000,1100));assertFalse(l.permits(A,1000000,901000));
    }
    @Test public void malformedOperatorAndExpiredClaimRejected(){
        ComputerLease l=new ComputerLease(S,"",100000,1000);
        for(String owner:new String[]{null,"", "host;input tap 1 2","A".repeat(32)})
            try{l.claim(owner,100100,1100);fail();}catch(IllegalArgumentException expected){}
        try{l.claim(A,1000000,901000);fail();}catch(IllegalArgumentException expected){}
    }
}
