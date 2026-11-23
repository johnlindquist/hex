package anomaly.hex.android.keyboard;

import android.content.Context;
import android.content.SharedPreferences;

public final class KeyboardSettings {
    private final SharedPreferences prefs;

    public KeyboardSettings(Context context) {
        prefs = context.getSharedPreferences("hex_keyboard", Context.MODE_PRIVATE);
    }

    public String layoutLanguage() { return prefs.getString("layoutLanguage", "auto"); }
    public void setLayoutLanguage(String code) {
        String value = code == null || code.equals("auto") ? "auto" : KeyboardLanguage.resolve(code, null).code();
        prefs.edit().putString("layoutLanguage", value).apply();
    }

    public boolean isFullKeyboard() { return prefs.getBoolean("isFullKeyboard", true); }
    public void setFullKeyboard(boolean value) { prefs.edit().putBoolean("isFullKeyboard", value).apply(); }

    public boolean autocorrect() { return prefs.getBoolean("autocorrect", true); }
    public void setAutocorrect(boolean value) { prefs.edit().putBoolean("autocorrect", value).apply(); }

    public boolean keyPopups() { return prefs.getBoolean("keyPopups", true); }
    public void setKeyPopups(boolean value) { prefs.edit().putBoolean("keyPopups", value).apply(); }

    public boolean haptics() { return prefs.getBoolean("haptics", true); }
    public void setHaptics(boolean value) { prefs.edit().putBoolean("haptics", value).apply(); }

    public enum SpacebarFixMode {
        FLICK_SCRUBBER("flick_scrubber", "Flick up → inline word scrubber"),
        LONG_PRESS_PANEL("long_press_panel", "Long-press → Fix Tail panel"),
        HOLD_SLIDE_PICKER("hold_slide_picker", "Hold + slide up → release to pick");

        public final String id, label;
        SpacebarFixMode(String id, String label) { this.id = id; this.label = label; }
        public static SpacebarFixMode fromId(String id) {
            if (id != null) {
                for (SpacebarFixMode m : values()) if (m.id.equals(id)) return m;
            }
            return FLICK_SCRUBBER;
        }
    }

    public boolean emojiEnabled() { return prefs.getBoolean("emojiEnabled", false); }
    public void setEmojiEnabled(boolean value) { prefs.edit().putBoolean("emojiEnabled", value).apply(); }

    public SpacebarFixMode spacebarFixMode() {
        return SpacebarFixMode.fromId(prefs.getString("spacebarFixMode", SpacebarFixMode.FLICK_SCRUBBER.id));
    }
    public void setSpacebarFixMode(SpacebarFixMode mode) {
        prefs.edit().putString("spacebarFixMode", (mode == null ? SpacebarFixMode.FLICK_SCRUBBER : mode).id).apply();
    }
}
