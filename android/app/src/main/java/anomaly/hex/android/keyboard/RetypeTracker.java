package anomaly.hex.android.keyboard;

import java.util.*;

/**
 * Pure-Java state machine that detects when a user manually backspaces a mistyped word
 * and retypes a correction, while buffering per-character tap coordinates so both
 * clean commits and corrected slips can train per-key thumb-offset adaptation.
 */
public final class RetypeTracker {
    public record TapSample(
            char key,
            float tapXdp,
            float tapYdp,
            float keyCenterXdp,
            float keyCenterYdp,
            float keyWidthDp,
            float keyHeightDp) {}

    public record RetypeMatch(String erased, String retyped, List<TapSample> erasedTaps) {}

    private enum State { IDLE, DELETING, RETYPING }

    private State state = State.IDLE;
    private String erasedWord;
    private List<TapSample> erasedTaps = List.of();
    private final List<TapSample> currentWordTaps = new ArrayList<>();
    private String lastCommittedWord;
    private List<TapSample> lastCommittedTaps = List.of();

    public void recordCharTap(
            char key, float tx, float ty, float kx, float ky, float kw, float kh) {
        char lower = Character.toLowerCase(key);
        if (lower < 'a' || lower > 'z') return;
        if (state == State.DELETING) {
            state = State.RETYPING;
        }
        if (currentWordTaps.size() < 32) {
            currentWordTaps.add(new TapSample(lower, tx, ty, kx, ky, kw, kh));
        }
    }

    /**
     * Call before deleting characters on Backspace or Delete-Word.
     * Ignores autocorrect undos (those are already recorded as rejections).
     */
    public void onBackspace(String textBeforeCursor, boolean wasAutocorrectUndo) {
        if (wasAutocorrectUndo) {
            reset();
            return;
        }
        if (state == State.IDLE) {
            String candidate = trailingWordForDeletion(textBeforeCursor);
            if (candidate != null && candidate.length() >= 2) {
                erasedWord = candidate;
                boolean deletingJustCommitted = textBeforeCursor != null
                        && textBeforeCursor.endsWith(" ")
                        && lastCommittedWord != null
                        && lastCommittedWord.equalsIgnoreCase(candidate);
                if (deletingJustCommitted && !lastCommittedTaps.isEmpty()) {
                    erasedTaps = List.copyOf(lastCommittedTaps);
                    currentWordTaps.clear();
                    currentWordTaps.addAll(lastCommittedTaps);
                } else {
                    erasedTaps = List.copyOf(currentWordTaps);
                }
                state = State.DELETING;
            }
        } else if (state == State.RETYPING) {
            state = State.DELETING;
        }
        if (!currentWordTaps.isEmpty()) {
            currentWordTaps.remove(currentWordTaps.size() - 1);
        }
    }

    public void onDeleteWord(String textBeforeCursor) {
        if (state == State.IDLE) {
            String candidate = trailingWordForDeletion(textBeforeCursor);
            if (candidate != null && candidate.length() >= 2) {
                erasedWord = candidate;
                boolean deletingJustCommitted = textBeforeCursor != null
                        && textBeforeCursor.endsWith(" ")
                        && lastCommittedWord != null
                        && lastCommittedWord.equalsIgnoreCase(candidate);
                erasedTaps = deletingJustCommitted ? List.copyOf(lastCommittedTaps) : List.copyOf(currentWordTaps);
                state = State.DELETING;
            }
        } else if (state == State.RETYPING) {
            state = State.DELETING;
        }
        currentWordTaps.clear();
    }

    public List<TapSample> currentWordTapsSnapshot() {
        return currentWordTaps.isEmpty() ? List.of() : List.copyOf(currentWordTaps);
    }

    /**
     * Call when a word is committed (Space, punctuation, Return, or suggestion pick).
     * Returns a RetypeMatch if the user erased a plausible slip and retyped a correction.
     */
    public RetypeMatch onWordCommitted(String committedWord) {
        RetypeMatch match = null;
        String clean = committedWord == null ? "" : committedWord.trim();
        if ((state == State.RETYPING || state == State.DELETING)
                && erasedWord != null && !clean.isEmpty()
                && !erasedWord.equalsIgnoreCase(clean)
                && PersonalAutocorrectStore.isSlip(erasedWord, clean)) {
            match = new RetypeMatch(erasedWord, clean, erasedTaps);
        }
        lastCommittedWord = clean.isEmpty() ? null : clean;
        lastCommittedTaps = currentWordTaps.isEmpty() ? List.of() : List.copyOf(currentWordTaps);
        currentWordTaps.clear();
        state = State.IDLE;
        erasedWord = null;
        erasedTaps = List.of();
        return match;
    }

    public void reset() {
        state = State.IDLE;
        erasedWord = null;
        erasedTaps = List.of();
        currentWordTaps.clear();
        lastCommittedWord = null;
        lastCommittedTaps = List.of();
    }

    static String trailingWordForDeletion(String textBeforeCursor) {
        if (textBeforeCursor == null || textBeforeCursor.isEmpty()) return null;
        int end = textBeforeCursor.length();
        // Allow at most one trailing space (e.g. user typed "thsi " then backspaced into it).
        if (end > 0 && textBeforeCursor.charAt(end - 1) == ' ') {
            end--;
        }
        int start = end;
        while (start > 0) {
            char c = textBeforeCursor.charAt(start - 1);
            if (Character.isLetter(c) || c == '\'' || c == '’') {
                start--;
            } else {
                break;
            }
        }
        return end > start ? textBeforeCursor.substring(start, end) : null;
    }
}
