package cn.local.tickethelper.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class HomeStepTest {
    @Test public void firstUseCompletesSetupThenChoosesTarget() {
        assertEquals(HomeStep.SETUP, HomeStep.resolve(false,false,false,false,false,false,false));
        assertEquals(HomeStep.SELECT, HomeStep.resolve(false,false,false,false,true,false,false));
    }
    @Test public void importedTargetsAlwaysRequireReviewEvenWhenEverythingLooksReady() {
        for (boolean valid : new boolean[]{false,true})
            for (boolean dirty : new boolean[]{false,true})
                assertEquals(HomeStep.REVIEW_IMPORTED, HomeStep.resolve(false,true,true,valid,true,false,dirty));
    }
    @Test public void savedExpiredTargetDoesNotOfferStart() {
        assertEquals(HomeStep.REPAIR_TARGET, HomeStep.resolve(false,true,false,false,true,false,false));
    }
    @Test public void missingSetupAndUnavailableOrderModeHaveExplicitNextSteps() {
        assertEquals(HomeStep.SETUP, HomeStep.resolve(false,true,false,true,false,false,false));
        assertEquals(HomeStep.CHANGE_MODE, HomeStep.resolve(false,true,false,true,true,true,false));
    }
    @Test public void editsStillGoThroughStartValidationAndSaving() {
        assertEquals(HomeStep.SAVE_AND_START, HomeStep.resolve(false,true,false,false,true,false,true));
        assertEquals(HomeStep.READY, HomeStep.resolve(false,true,false,true,true,false,false));
    }
    @Test public void runningAlwaysOffersPauseEvenWhenPermissionsChange() {
        assertEquals(HomeStep.RUNNING, HomeStep.resolve(true,false,true,false,false,true,true));
    }
}
