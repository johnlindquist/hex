package anomaly.hex.android.keyboard;

/** Offsets are relative to the extracted text window, in UTF-16 units. */
final class SelectionTrackpad {
    record Range(int anchor, int active) {}
    private int anchor, active, length;
    private boolean tracking;

    void begin(int selStart, int selEnd, int textLen) {
        length = Math.max(0, textLen);
        anchor = clamp(selStart); active = clamp(selEnd); tracking = true;
    }
    Range move(int delta) {
        if (!tracking) return null;
        active = clamp((long)active + delta);
        return new Range(anchor, active);
    }
    private int clamp(long value) { return (int)Math.max(0, Math.min(length, value)); }
    void end() { tracking = false; }
}
