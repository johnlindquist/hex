package anomaly.hex.android.keyboard;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure capitalization rules shared by live typing ({@link AutocorrectEngine}),
 * auto-cap fallback ({@link HexInputMethodService}), and full-sentence fixing
 * ({@link TailCorrectionEngine}).
 */
public final class CapitalizationRules {
    private static final Map<String, String> CANONICAL_CASE = Map.ofEntries(
            // Pronoun "I" contractions typed with an apostrophe
            Map.entry("i'm", "I'm"),
            Map.entry("i've", "I've"),
            Map.entry("i'll", "I'll"),
            Map.entry("i'd", "I'd"),
            // Days of the week
            Map.entry("monday", "Monday"),
            Map.entry("tuesday", "Tuesday"),
            Map.entry("wednesday", "Wednesday"),
            Map.entry("thursday", "Thursday"),
            Map.entry("friday", "Friday"),
            Map.entry("saturday", "Saturday"),
            Map.entry("sunday", "Sunday"),
            // Unambiguous months ("march" and "may" intentionally omitted)
            Map.entry("january", "January"),
            Map.entry("february", "February"),
            Map.entry("april", "April"),
            Map.entry("june", "June"),
            Map.entry("july", "July"),
            Map.entry("august", "August"),
            Map.entry("september", "September"),
            Map.entry("october", "October"),
            Map.entry("november", "November"),
            Map.entry("december", "December"),
            // Major languages & nationalities
            Map.entry("english", "English"),
            Map.entry("spanish", "Spanish"),
            Map.entry("french", "French"),
            Map.entry("german", "German"),
            Map.entry("italian", "Italian"),
            Map.entry("portuguese", "Portuguese"),
            Map.entry("japanese", "Japanese"),
            Map.entry("chinese", "Chinese"),
            Map.entry("korean", "Korean"),
            Map.entry("russian", "Russian"),
            Map.entry("arabic", "Arabic"),
            Map.entry("hindi", "Hindi"),
            Map.entry("dutch", "Dutch"),
            Map.entry("swedish", "Swedish"),
            Map.entry("polish", "Polish"),
            Map.entry("turkish", "Turkish"),
            Map.entry("american", "American"),
            Map.entry("british", "British"),
            Map.entry("canadian", "Canadian"),
            Map.entry("australian", "Australian"),
            Map.entry("european", "European"),
            // Unambiguous tech & product brands (no common-noun collisions)
            Map.entry("github", "GitHub"),
            Map.entry("gitlab", "GitLab"),
            Map.entry("ios", "iOS"),
            Map.entry("macos", "macOS"),
            Map.entry("ipados", "iPadOS"),
            Map.entry("iphone", "iPhone"),
            Map.entry("ipad", "iPad"),
            Map.entry("macbook", "MacBook"),
            Map.entry("airpods", "AirPods"),
            Map.entry("android", "Android"),
            Map.entry("google", "Google"),
            Map.entry("gmail", "Gmail"),
            Map.entry("youtube", "YouTube"),
            Map.entry("openai", "OpenAI"),
            Map.entry("chatgpt", "ChatGPT"),
            Map.entry("anthropic", "Anthropic"),
            Map.entry("gemini", "Gemini"),
            Map.entry("vercel", "Vercel"),
            Map.entry("cloudflare", "Cloudflare"),
            Map.entry("kubernetes", "Kubernetes"),
            Map.entry("typescript", "TypeScript"),
            Map.entry("javascript", "JavaScript"),
            Map.entry("kotlin", "Kotlin"),
            Map.entry("postgresql", "PostgreSQL"),
            Map.entry("sqlite", "SQLite"),
            Map.entry("graphql", "GraphQL"),
            Map.entry("oauth", "OAuth"),
            Map.entry("figma", "Figma"),
            Map.entry("spotify", "Spotify"),
            Map.entry("whatsapp", "WhatsApp"),
            Map.entry("instagram", "Instagram"),
            Map.entry("linkedin", "LinkedIn"),
            Map.entry("reddit", "Reddit"),
            Map.entry("wikipedia", "Wikipedia"),
            Map.entry("netflix", "Netflix"),
            Map.entry("shopify", "Shopify")
    );

    private static final Set<String> PRESERVE_LOWER_START = Set.of(
            "iOS", "macOS", "iPadOS", "iPhone", "iPad"
    );

    private static final Set<String> ABBREVIATIONS = Set.of(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "eg", "ie", "e.g", "i.e"
    );

    // High-frequency words that are also common given names, surnames, or place words:
    // never auto-flag them as accidental mid-sentence capitals.
    private static final Set<String> NAME_OR_PLACE_COLLISIONS = Set.of(
            "will", "may", "mark", "bill", "jack", "john", "paul", "mary", "anna", "rose",
            "grace", "grant", "frank", "max", "sam", "dan", "ben", "tom", "bob", "joe",
            "jim", "tim", "kim", "ann", "lee", "jay", "ray", "rob", "pat", "don", "ken",
            "new", "old", "north", "south", "east", "west", "park", "king", "hill", "green",
            "white", "black", "brown", "young", "long", "short", "high", "little", "big",
            "red", "blue", "gold", "star", "sun", "moon", "world", "state", "city", "street",
            "road", "house", "home", "school", "church", "day", "night", "year", "time",
            "love", "hope", "faith", "joy", "art", "law", "war", "peace", "power", "life"
    );

    private CapitalizationRules() {}

    /** Returns canonical casing for known proper nouns/brands/I-contractions, or null if none. */
    public static String canonicalCase(String lower) {
        if (lower == null || lower.isEmpty()) return null;
        return CANONICAL_CASE.get(lower);
    }

    /** True when {@code word} has >= 2 letters and every letter is uppercase (e.g. "THAKS", "DONT"). */
    public static boolean isAllCaps(String word) {
        if (word == null || word.length() < 2) return false;
        int letters = 0;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isLetter(c)) {
                if (!Character.isUpperCase(c)) return false;
                letters++;
            }
        }
        return letters >= 2;
    }

    /**
     * Fixes accidental double-cap Shift-hold slips like "THis" -> "This", "SOunds" -> "Sounds",
     * or "DOn't" -> "Don't". Returns null if {@code word} is not a double-cap slip.
     */
    public static String fixDoubleCap(String word) {
        if (word == null || word.length() < 4) return null;
        if (!Character.isUpperCase(word.charAt(0)) || !Character.isUpperCase(word.charAt(1))) return null;
        int lowerCount = 0;
        for (int i = 2; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isUpperCase(c)) return null;
            if (Character.isLowerCase(c)) lowerCount++;
        }
        if (lowerCount < 2) return null;
        String lower = word.toLowerCase(Locale.ROOT);
        String canon = CANONICAL_CASE.get(lower);
        if (canon != null) return canon;
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /**
     * Applies the casing style of {@code original} (ALL CAPS, Title/double-cap, or lowercase)
     * to {@code replacement}, while preserving special brand casings like "iOS" or "GitHub".
     */
    public static String matchCase(String original, String replacement) {
        if (replacement == null || replacement.isEmpty()) return "";
        if (original == null || original.isEmpty()) return replacement;
        if (isAllCaps(original)) {
            return replacement.toUpperCase(Locale.ROOT);
        }
        if (Character.isUpperCase(original.charAt(0))) {
            return capitalizePreservingBrand(replacement);
        }
        return replacement;
    }

    /** Capitalizes the first character unless {@code word} is a lowercase-leading brand like "iOS". */
    public static String capitalizePreservingBrand(String word) {
        if (word == null || word.isEmpty()) return "";
        if (PRESERVE_LOWER_START.contains(word)) return word;
        if (Character.isUpperCase(word.charAt(0))) return word;
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    /**
     * Fallback sentence-start auto-cap detector for editors/WebViews whose
     * {@code InputConnection.getCursorCapsMode} returns 0 even at sentence boundaries.
     */
    public static boolean shouldAutoCapAfter(String textBeforeCursor) {
        if (textBeforeCursor == null || textBeforeCursor.isEmpty()) return true;
        int i = textBeforeCursor.length() - 1;
        // Empty / whitespace-only field -> sentence start
        int ws = 0;
        while (i >= 0 && Character.isWhitespace(textBeforeCursor.charAt(i))) {
            char c = textBeforeCursor.charAt(i);
            if (c == '\n' || c == '\r') return true;
            ws++;
            i--;
        }
        if (i < 0) return true;
        if (ws == 0) return false;

        // Skip closing quotes/brackets immediately before the whitespace (e.g. 'He said "hi." ')
        while (i >= 0) {
            char c = textBeforeCursor.charAt(i);
            if (c == '"' || c == '\'' || c == '”' || c == '’' || c == ')' || c == ']') {
                i--;
            } else {
                break;
            }
        }
        if (i < 0) return false;
        char punct = textBeforeCursor.charAt(i);
        if (punct != '.' && punct != '!' && punct != '?') return false;
        if (punct == '.') {
            // Avoid auto-capping after ellipses ("... ") or common abbreviations ("e.g. ", "Dr. ")
            if (i > 0 && textBeforeCursor.charAt(i - 1) == '.') return false;
            int end = i;
            int start = end;
            while (start > 0) {
                char ch = textBeforeCursor.charAt(start - 1);
                if (Character.isLetter(ch) || ch == '.') start--;
                else break;
            }
            if (start < end) {
                String token = textBeforeCursor.substring(start, end).toLowerCase(Locale.ROOT);
                if (ABBREVIATIONS.contains(token)) return false;
            }
        }
        return true;
    }

    /**
     * True when a mid-sentence word looks like an accidental Shift-graze capital on a very common
     * lowercase English word (e.g. "this Works", "want To go"), avoiding proper nouns and names.
     */
    public static boolean isLikelyAccidentalMidSentenceCap(
            String orig,
            String lower,
            int dictRank,
            Map<String, String> screen,
            PersonalAutocorrectStore store) {
        if (orig == null || lower == null || orig.length() < 2) return false;
        if (!Character.isUpperCase(orig.charAt(0))) return false;
        // Only plain Title Case (not ALL CAPS, not camelCase)
        for (int i = 1; i < orig.length(); i++) {
            if (Character.isUpperCase(orig.charAt(i))) return false;
        }
        if (CANONICAL_CASE.containsKey(lower)) return false;
        if (NAME_OR_PLACE_COLLISIONS.contains(lower)) return false;
        if (dictRank < 0 || dictRank >= 1500) return false;
        if (screen != null) {
            String s = screen.get(lower);
            if (s != null && !s.isEmpty() && Character.isUpperCase(s.charAt(0))) return false;
        }
        if (store != null) {
            String p = store.personalCanonical(lower);
            if (p != null && !p.isEmpty() && Character.isUpperCase(p.charAt(0))) return false;
        }
        return true;
    }
}
