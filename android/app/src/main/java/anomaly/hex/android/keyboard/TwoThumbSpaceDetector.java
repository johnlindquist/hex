package anomaly.hex.android.keyboard;

import static anomaly.hex.android.keyboard.KeyboardLayout.KeyKind;

/**
 * Pure detector for the experimental "tap both thumbs simultaneously -> Spacebar" gesture.
 * Requires two CHAR keys pressed within CHORD_WINDOW_MS and physically separated across the
 * keyboard so normal fast two-thumb rollover typing, Shift+key chords, and single-thumb
 * edge grazes never false-trigger.
 */
final class TwoThumbSpaceDetector {
    static final long CHORD_WINDOW_MS = 65L;
    static final float MIN_SEPARATION_FLOOR_DP = 72f;
    static final float MIN_SEPARATION_RATIO = 0.22f;

    private TwoThumbSpaceDetector() {}

    static float minSeparationDp(float keyboardWidthDp) {
        return Math.max(MIN_SEPARATION_FLOOR_DP, keyboardWidthDp * MIN_SEPARATION_RATIO);
    }

    /**
     * Returns true when a second finger lands on a CHAR key nearly simultaneously with a
     * held first CHAR key across the keyboard, meaning both keys should be swallowed and
     * converted into a single Space press.
     */
    static boolean isSimultaneousThumbTap(
            boolean enabled,
            KeyKind firstKind,
            float firstXdp,
            long firstDownMs,
            KeyKind secondKind,
            float secondXdp,
            long secondDownMs,
            float keyboardWidthDp) {
        if (!enabled) return false;
        if (firstKind != KeyKind.CHAR || secondKind != KeyKind.CHAR) return false;
        long deltaMs = secondDownMs - firstDownMs;
        if (deltaMs < 0 || deltaMs > CHORD_WINDOW_MS) return false;
        float sepDp = Math.abs(secondXdp - firstXdp);
        return sepDp >= minSeparationDp(keyboardWidthDp);
    }
}
