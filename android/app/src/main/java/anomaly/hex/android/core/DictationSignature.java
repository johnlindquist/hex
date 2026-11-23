package anomaly.hex.android.core;

import java.util.*;

/**
 * Labs › Output › Signature (Android & iOS parity):
 * the one line added to the end of a keyboard dictation before it goes in.
 */
public final class DictationSignature {
    public static final String DEFAULT_TEXT = "Sent from Hex";
    public static final int MAX_CHARS = 200;

    public enum Problem { LINE, LENGTH }

    private DictationSignature() {}

    /**
     * Returns {@code text} with {@code signature} on its own line at the end, or null when
     * nothing should be appended (lab off, blank text, blank signature, or already signed).
     */
    public static String append(String text, String signature, boolean enabled) {
        if (!enabled || text == null || text.isBlank() || signature == null) return null;
        String line = signature.trim();
        if (line.isEmpty() || alreadySigned(text.trim(), line)) return null;
        return text + "\n" + line;
    }

    private static boolean alreadySigned(String text, String signature) {
        if (text.equals(signature)) return true;
        if (!text.endsWith(signature)) return false;
        int end = text.length() - signature.length();
        while (end > 0 && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) end--;
        return end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r');
    }

    /** Validates a candidate signature: single line, at most MAX_CHARS code points. */
    public static Problem textProblem(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') return Problem.LINE;
        }
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_CHARS) return Problem.LENGTH;
        return null;
    }
}
