package anomaly.hex.android.keyboard;

public final class AccentTable {
    private AccentTable() {}

    public static String[] accentsFor(String baseLower, KeyboardLanguage lang) {
        String options = switch (lang) {
        case NB -> switch (baseLower) {
            case "a" -> "aàáâäãā";
            case "o" -> "oôöòóœōõ";
            case "æ" -> "æä";
            case "ø" -> "øö";
            default -> shared(baseLower);
        };
        case ES -> switch (baseLower) {
            case "a" -> "aáàäâãåąæāª";
            case "e" -> "eéèëêęėē";
            case "i" -> "iíïìîįī";
            case "o" -> "oóòöôõøœōº";
            case "u" -> "uúüùûū";
            case "n" -> "nñń";
            default -> shared(baseLower);
        };
        case DE -> switch (baseLower) {
            case "a" -> "aàáâæãåā";
            case "o" -> "oôòóœøōõ";
            case "u" -> "uûùúū";
            default -> shared(baseLower);
        };
        case IT -> switch (baseLower) {
            case "o" -> "oòóôöõœøō";
            case "u" -> "uùúûüū";
            default -> shared(baseLower);
        };
        case FR -> switch (baseLower) {
            case "e" -> "eéèêëęėē";
            case "a" -> "aàâæáäãåā";
            case "i" -> "iîïìíįī";
            case "o" -> "oôœöòóõøō";
            case "u" -> "uùûüúū";
            default -> shared(baseLower);
        };
        case PT -> switch (baseLower) {
            case "a" -> "aáãâàäåæāª";
            case "e" -> "eéêèëęėē";
            case "i" -> "iíìîïįī";
            case "o" -> "oóõôòöœøōº";
            case "u" -> "uúüùûū";
            default -> shared(baseLower);
        };
        default -> shared(baseLower);
        };
        return options.isEmpty() ? new String[0] : options.split("");
    }

    /**
     * Labs › Keyboard › Quick accents (iOS AccentCatalog.primaryVariant parity):
     * returns the language's most common accent for a held letter, else the first variant after
     * the base letter, or null if the key has no accented variants.
     */
    public static String primaryVariant(String baseLower, KeyboardLanguage lang) {
        if (baseLower == null || baseLower.isEmpty() || !Character.isLetter(baseLower.charAt(0))) return null;
        KeyboardLanguage resolved = lang == null ? KeyboardLanguage.EN : lang;
        String[] opts = accentsFor(baseLower.toLowerCase(java.util.Locale.ROOT), resolved);
        if (opts.length < 2) return null;
        String preferred = switch (resolved) {
            case EN, ES, PT -> switch (baseLower.toLowerCase(java.util.Locale.ROOT)) {
                case "a" -> "á"; case "e" -> "é"; case "i" -> "í"; case "o" -> "ó"; case "u" -> "ú";
                default -> null;
            };
            case FR -> "e".equalsIgnoreCase(baseLower) ? "é" : null;
            case IT -> switch (baseLower.toLowerCase(java.util.Locale.ROOT)) {
                case "a" -> "à"; case "e" -> "è"; case "i" -> "ì"; case "o" -> "ò"; case "u" -> "ù";
                default -> null;
            };
            default -> null;
        };
        if (preferred != null) {
            for (String o : opts) if (o.equals(preferred)) return preferred;
        }
        return opts[1];
    }

    private static String shared(String base) {
        return switch (base) {
            case "e" -> "eèéêëēėę";
            case "a" -> "aàáâäæãåā";
            case "i" -> "iìíîïīį";
            case "o" -> "oôöòóœøōõ";
            case "u" -> "uûüùúū";
            case "y" -> "yÿ";
            case "n" -> "nñń";
            case "c" -> "cçćč";
            case "s" -> "sßśš";
            case "z" -> "zžźż";
            case "l" -> "lł";
            case "-" -> "-–—•";
            case "\"" -> "\"“”„«»";
            case "'" -> "'‘’`";
            case "." -> ".…";
            case "?" -> "?¿";
            case "!" -> "!¡";
            case "$" -> "$€£¥¢";
            case "/" -> "/\\";
            default -> "";
        };
    }
}
