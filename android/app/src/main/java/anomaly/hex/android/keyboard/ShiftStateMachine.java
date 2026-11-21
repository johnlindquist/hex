package anomaly.hex.android.keyboard;

public final class ShiftStateMachine {
    public enum State { OFF, SHIFTED, CAPS_LOCK }
    private State state = State.OFF;
    private boolean held, typedWhileHeld;
    private long lastTap = -1;

    public State state() { return state; }
    public boolean isUppercase() { return held || state != State.OFF; }

    public void onShiftDown(long nowMs) {
        held = true;
        typedWhileHeld = false;
        if (state == State.CAPS_LOCK) {
            state = State.OFF;
            lastTap = -1;
        } else if (lastTap >= 0 && nowMs >= lastTap && nowMs - lastTap < 300) {
            state = State.CAPS_LOCK;
            lastTap = -1;
        } else {
            state = state == State.OFF ? State.SHIFTED : State.OFF;
            lastTap = nowMs;
        }
    }

    public void onShiftUp() {
        held = false;
        if (typedWhileHeld && state != State.CAPS_LOCK) state = State.OFF;
        typedWhileHeld = false;
    }

    public void onCharacterInserted() {
        if (held) typedWhileHeld = true;
        else if (state == State.SHIFTED) state = State.OFF;
        lastTap = -1;
    }

    public void applyAutoCap(boolean shouldCap) {
        if (!held && state != State.CAPS_LOCK) state = shouldCap ? State.SHIFTED : State.OFF;
    }
}
