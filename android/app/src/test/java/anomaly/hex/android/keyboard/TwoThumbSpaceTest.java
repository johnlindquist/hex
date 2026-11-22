package anomaly.hex.android.keyboard;

import static anomaly.hex.android.keyboard.KeyboardLayout.KeyKind;
import static org.junit.Assert.*;

import org.junit.Test;

public class TwoThumbSpaceTest {
    private static final float PHONE_WIDTH_DP = 412f;

    @Test public void triggersOnSimultaneousTwoThumbCharTaps() {
        // Left thumb on 'f' (~140dp) and right thumb on 'j' (~270dp), 0–65ms apart
        assertTrue(TwoThumbSpaceDetector.isSimultaneousThumbTap(
                true, KeyKind.CHAR, 140f, 1000L, KeyKind.CHAR, 270f, 1000L, PHONE_WIDTH_DP));
        assertTrue(TwoThumbSpaceDetector.isSimultaneousThumbTap(
                true, KeyKind.CHAR, 80f, 1000L, KeyKind.CHAR, 330f, 1028L, PHONE_WIDTH_DP));
        assertTrue(TwoThumbSpaceDetector.isSimultaneousThumbTap(
                true, KeyKind.CHAR, 320f, 1000L, KeyKind.CHAR, 90f, 1065L, PHONE_WIDTH_DP));
    }

    @Test public void rejectsNormalFastRolloverTypingOutsideChordWindow() {
        // Fast alternating two-thumb typing (66ms+ apart) must remain normal letters
        assertFalse(TwoThumbSpaceDetector.isSimultaneousThumbTap(
                true, KeyKind.CHAR, 120f, 1000L, KeyKind.CHAR, 290f, 1066L, PHONE_WIDTH_DP));
        assertFalse(TwoThumbSpaceDetector.isSimultaneousThumbTap(
                true, KeyKind.CHAR, 120f, 1000L, KeyKind.CHAR, 290f, 1110L, PHONE_WIDTH_DP));
    }

    @Test public void rejectsCloseSingleThumbOrAdjacentGrazes() {
        // Two touches only 40dp or 85dp apart on a 412dp phone (< 22% = 90.64dp)
        assertFalse(TwoThumbSpaceDetector.isSimultaneousThumbTap(
                true, KeyKind.CHAR, 140f, 1000L, KeyKind.CHAR, 180f, 1010L, PHONE_WIDTH_DP));
        assertFalse(TwoThumbSpaceDetector.isSimultaneousThumbTap(
                true, KeyKind.CHAR, 140f, 1000L, KeyKind.CHAR, 225f, 1010L, PHONE_WIDTH_DP));
    }

    @Test public void neverInterferesWithShiftDeleteSpaceOrReturn() {
        assertFalse(TwoThumbSpaceDetector.isSimultaneousThumbTap(
                true, KeyKind.SHIFT, 25f, 1000L, KeyKind.CHAR, 270f, 1010L, PHONE_WIDTH_DP));
        assertFalse(TwoThumbSpaceDetector.isSimultaneousThumbTap(
                true, KeyKind.CHAR, 100f, 1000L, KeyKind.SHIFT, 25f, 1010L, PHONE_WIDTH_DP));
        assertFalse(TwoThumbSpaceDetector.isSimultaneousThumbTap(
                true, KeyKind.DELETE, 380f, 1000L, KeyKind.CHAR, 100f, 1010L, PHONE_WIDTH_DP));
        assertFalse(TwoThumbSpaceDetector.isSimultaneousThumbTap(
                true, KeyKind.SPACE, 200f, 1000L, KeyKind.CHAR, 50f, 1010L, PHONE_WIDTH_DP));
        assertFalse(TwoThumbSpaceDetector.isSimultaneousThumbTap(
                true, KeyKind.CHAR, 100f, 1000L, KeyKind.RETURN, 380f, 1010L, PHONE_WIDTH_DP));
    }

    @Test public void disabledLabNeverTriggers() {
        assertFalse(TwoThumbSpaceDetector.isSimultaneousThumbTap(
                false, KeyKind.CHAR, 80f, 1000L, KeyKind.CHAR, 330f, 1005L, PHONE_WIDTH_DP));
    }
}
