package anomaly.hex.android.core;

public enum LabId {
    RECOVERY_DRAFTS("recovery_drafts", "Recovery drafts", "Dictation", "Save encrypted local checkpoints during dictation so interrupted takes can be retried."),
    GEMMA_CLEANUP("gemma_cleanup", "Clean up with Gemma", "Output", "Offer an optional on-device Gemma 4 E2B review pass after dictation."),
    WORDS_ON_SCREEN("words_on_screen", "Words on screen", "Output", "Read one approved window image with local OCR to suggest spelling hints during review."),
    SPOKEN_CLEANUP("spoken_cleanup", "Clean up what I say", "Output", "Drop hesitation sounds and spoken self-corrections before formatting."),
    PUNCTUATION_DRUMS("punctuation_drums", "Live punctuation drums", "Keyboard", "Tap punctuation pads while dictating with the full Hex Keyboard."),
    TYPE_ANYWHERE("type_anywhere", "Type anywhere", "Keyboard", "Tap the accessibility button anywhere to capture the screen and ask AI."),
    EMOJI_KEYBOARD("emoji_keyboard", "Emoji keyboard", "Keyboard", "Adds an emoji key and searchable emoji panel to Hex Keyboard."),
    SPATIAL_AUTOCORRECT("spatial_autocorrect", "Thumb-position autocorrect", "Keyboard", "Experimental: fix fat-thumb slips by QWERTY position, pull words on screen into the dictionary, and learn from every word you type."),
    TWO_THUMB_SPACE("two_thumb_space", "Two-thumb tap for space", "Keyboard", "Experimental: tap both thumbs on the keyboard at the same time to trigger the spacebar."),
    SPACEBAR_FIX_TAIL("spacebar_fix_tail", "Spacebar tail fix", "Keyboard", "Experimental: flick up or hold the spacebar to fix fat-finger typos in the last few words."),
    TYPING_TELEMETRY("typing_telemetry", "Typing telemetry log", "Keyboard", "Record keystrokes, tap coordinates, and corrections locally so you can export and share them for tuning."),
    SIGNATURE("signature", "Signature", "Output", "Add a short line such as \"Sent from Hex\" below each keyboard dictation."),
    QUICK_ACCENTS("quick_accents", "Quick accents", "Keyboard", "Hold a letter to pre-select its language's most common accent; lift to type it or slide for others."),
    EMOJI_SHORTCODES("emoji_shortcodes", "Emoji shortcodes", "Keyboard", "Type :name to see matching emoji in the strip, and close with : to insert it.");
    public final String id, title, group, summary;
    LabId(String id, String title, String group, String summary) {
        this.id = id; this.title = title; this.group = group; this.summary = summary;
    }
    public static LabId fromId(String id) {
        for (LabId l : values()) if (l.id.equals(id)) return l;
        return null;
    }
}
