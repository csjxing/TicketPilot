package cn.local.tickethelper.core;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class VisualFrameProofTest {
    @Test public void identicalEntireCropPassesWithoutRetainingPixelArray() {
        int[] original = new int[12]; Arrays.fill(original, 0xffffffff);
        VisualFrameProof proof = VisualFrameProof.capture(4, 3, original);
        assertNotNull(proof); assertTrue(proof.matches(4, 3, original.clone()));
        original[0] = 0xff000000;
        assertFalse(proof.matches(4, 3, original));
    }
    @Test public void onePixelInAmountSelectionOrNewDialogOutsideOldTextRejects() {
        int[] initial = new int[100]; Arrays.fill(initial, 0xffffffff);
        VisualFrameProof proof = VisualFrameProof.capture(10, 10, initial);
        for (int changedAt : new int[]{0, 44, 99}) {
            int[] next = initial.clone(); next[changedAt] = 0xffff8020;
            assertFalse(proof.matches(10, 10, next));
        }
    }
    @Test public void changedGeometryAndInvalidUnboundedFramesReject() {
        VisualFrameProof proof = VisualFrameProof.capture(4, 3, new int[12]);
        assertFalse(proof.matches(3, 4, new int[12]));
        assertFalse(proof.matches(4, 3, new int[11]));
        assertFalse(proof.matches(4, 3, null));
        assertNull(VisualFrameProof.capture(0, 3, new int[0]));
        assertNull(VisualFrameProof.capture(4, 3, new int[13]));
        assertNull(VisualFrameProof.capture(Integer.MAX_VALUE, Integer.MAX_VALUE, new int[0]));
    }
}
