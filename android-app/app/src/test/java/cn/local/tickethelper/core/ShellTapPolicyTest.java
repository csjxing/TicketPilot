package cn.local.tickethelper.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShellTapPolicyTest {
    @Test public void rejectsExpiredAndUnreasonablyFutureCommands() {
        assertEquals("stale_deadline", ShellTapPolicy.validate(1, 1, 1260, 2772, 100, 100));
        assertEquals("stale_deadline", ShellTapPolicy.validate(1, 1, 1260, 2772, 100, 3101));
        assertNull(ShellTapPolicy.validate(801, 1262, 1260, 2772, 100, 3100));
        assertEquals("stale_deadline", ShellTapPolicy.validate(1, 1, 1260, 2772, -1, 100));
    }
    @Test public void validatesActualDisplayBounds() {
        assertEquals("invalid_coordinates", ShellTapPolicy.validate(1260, 0, 1260, 2772, 100, 500));
        assertEquals("invalid_coordinates", ShellTapPolicy.validate(-1, 0, 1260, 2772, 100, 500));
        assertEquals("invalid_display", ShellTapPolicy.validate(1, 1, 0, 2772, 100, 500));
        assertEquals("invalid_display", ShellTapPolicy.validate(1, 1, 20000, 2772, 100, 500));
    }
    @Test public void onlyAcceptsUnambiguousForegroundDamaiActivity() {
        String focus = "  mCurrentFocus=Window{12abc u0 cn.damai/cn.damai.trade.newtradeorder.ui.projectdetail.ui.activity.NcovSkuActivity type=1 }\n";
        assertTrue(ShellTapPolicy.damaiFocused(focus));
        assertFalse(ShellTapPolicy.damaiFocused(focus + focus));
        assertFalse(ShellTapPolicy.damaiFocused(focus.replace("cn.damai/", "cn.damai.fake/")));
        assertFalse(ShellTapPolicy.damaiFocused(focus.replace(" u0 ", " u10 ")));
        assertFalse(ShellTapPolicy.damaiFocused("mCurrentFocus=null\n" + focus));
        assertFalse(ShellTapPolicy.damaiFocused(focus + "mShowingLockscreen=true"));
        assertFalse(ShellTapPolicy.damaiFocused("mFocusedApp=cn.damai"));
    }
    @Test public void constructsFixedArgumentVectorWithoutShellInterpretation() {
        assertArrayEquals(new String[]{"/system/bin/input", "-d", "0", "tap", "801", "1262"},
                ShellTapPolicy.tapCommand(801, 1262));
    }
}
