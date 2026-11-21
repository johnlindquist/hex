package anomaly.hex.android.keyboard;

/** A verified insertion. A selection replacement may arrive in several phrases. */
record DictationUndo(String inserted, int end, String replacedSelection) {
    int start() { return end - inserted.length(); }
    DictationUndo append(String phrase) {
        return new DictationUndo(inserted + phrase, end + phrase.length(), replacedSelection);
    }
    void restore(android.view.inputmethod.InputConnection c) {
        if (replacedSelection == null) { c.deleteSurroundingText(inserted.length(),0); return; }
        c.beginBatchEdit();
        try {
            if (!c.setSelection(start(),end)) return;
            CharSequence selected = c.getSelectedText(0);
            if (selected != null && inserted.contentEquals(selected)) c.commitText(replacedSelection,1);
        } finally { c.endBatchEdit(); }
    }
    boolean matches(int cursor, CharSequence before) {
        return cursor == end && before.toString().endsWith(inserted);
    }
}
