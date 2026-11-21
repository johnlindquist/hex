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
