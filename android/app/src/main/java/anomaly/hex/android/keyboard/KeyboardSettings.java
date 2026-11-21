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

    public boolean emojiEnabled() { return prefs.getBoolean("emojiEnabled", false); }
    public void setEmojiEnabled(boolean value) { prefs.edit().putBoolean("emojiEnabled", value).apply(); }
}
