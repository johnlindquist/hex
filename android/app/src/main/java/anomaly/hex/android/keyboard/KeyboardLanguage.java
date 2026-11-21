package anomaly.hex.android.keyboard;

import java.util.List;
import java.util.Locale;

public enum KeyboardLanguage {
    EN, ES, FR, DE, IT, PT, NB;

    public List<String> letterRows() {
        return switch (this) {
            case ES -> List.of("qwertyuiop", "asdfghjklñ", "zxcvbnm");
            case DE -> List.of("qwertzuiopü", "asdfghjklöä", "yxcvbnm");
            case NB -> List.of("qwertyuiopå", "asdfghjkløæ", "zxcvbnm");
            default -> List.of("qwertyuiop", "asdfghjkl", "zxcvbnm");
        };
    }

    public String code() { return name().toLowerCase(Locale.ROOT); }

    public static KeyboardLanguage resolve(String pref, Locale deviceLocale) {
        String code = pref == null || pref.equals("auto") ? (deviceLocale == null ? "" : deviceLocale.getLanguage()) : pref;
        code = code.split("[-_]", 2)[0].toUpperCase(Locale.ROOT);
        if (code.equals("NO") || code.equals("NN")) return NB;
        for (KeyboardLanguage language : values()) if (language.name().equals(code)) return language;
        return EN;
    }
}
