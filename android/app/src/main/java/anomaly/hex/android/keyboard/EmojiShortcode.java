package anomaly.hex.android.keyboard;

import java.util.*;

/**
 * Labs › Keyboard › Emoji shortcodes (iOS EmojiShortcode parity):
 * detects a ":name" token right before the cursor (at start of field or after whitespace),
 * offers matching emoji in the suggestion strip, and replaces ":name:" with the exact emoji
 * when the closing colon is typed.
 */
public final class EmojiShortcode {
    public static final int MIN_NAME_LENGTH = 2;

    public record Token(String name, String text) {
        /** Normalized lookup form: lowercased with "_", "+", "-" turned into spaces. */
        public String lookupName() {
            StringBuilder sb = new StringBuilder(name.length());
            boolean lastSpace = false;
            for (int i = 0; i < name.length(); i++) {
                char c = Character.toLowerCase(name.charAt(i));
                if (c == '_' || c == '+' || c == '-') {
                    if (!lastSpace && sb.length() > 0) { sb.append(' '); lastSpace = true; }
                } else {
                    sb.append(c);
                    lastSpace = false;
                }
            }
            return sb.toString().trim();
        }
    }

    public record Completion(Token token, String emoji) {}

    private EmojiShortcode() {}

    /**
     * Parses a ":name" token immediately before the cursor, or null if none.
     * Name must start with ASCII letter/digit, contain at least MIN_NAME_LENGTH letters/digits,
     * and only ASCII letters, digits, '_', '+', '-'.
     */
    public static Token token(String beforeText) {
        if (beforeText == null || beforeText.isEmpty()) return null;
        int end = beforeText.length();
        int i = end;
        while (i > 0 && isNameChar(beforeText.charAt(i - 1))) i--;
        if (i == 0 || i == end || beforeText.charAt(i - 1) != ':') return null;
        int colonIdx = i - 1;
        if (colonIdx > 0 && !Character.isWhitespace(beforeText.charAt(colonIdx - 1))) return null;
        String name = beforeText.substring(i, end);
        if (!isNameStart(name.charAt(0))) return null;
        int alnum = 0;
        for (int k = 0; k < name.length(); k++) {
            char c = name.charAt(k);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) alnum++;
        }
        if (alnum < MIN_NAME_LENGTH) return null;
        return new Token(name, ":" + name);
    }

    /** Returns up to {@code limit} matching emoji for a ":name" token before the cursor. */
    public static List<String> matches(String beforeText, boolean enabled, int limit) {
        if (!enabled || limit <= 0) return List.of();
        Token tok = token(beforeText);
        if (tok == null) return List.of();
        return matches(tok, limit);
    }

    public static List<String> matches(Token tok, int limit) {
        if (tok == null || limit <= 0) return List.of();
        String lookup = tok.lookupName();
        if (lookup.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String e : EmojiCatalog.exactMatches(lookup, limit)) {
            out.add(e);
            if (out.size() >= limit) return new ArrayList<>(out);
        }
        for (String e : EmojiCatalog.search(lookup)) {
            out.add(e);
            if (out.size() >= limit) break;
        }
        return new ArrayList<>(out);
    }

    /** Returns an exact emoji completion when a closing ':' arrives after ":name". */
    public static Completion completion(String beforeText) {
        Token tok = token(beforeText);
        if (tok == null) return null;
        List<String> exact = EmojiCatalog.exactMatches(tok.lookupName(), 1);
        if (exact.isEmpty()) return null;
        return new Completion(tok, exact.get(0));
    }

    private static boolean isNameStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static boolean isNameChar(char c) {
        return isNameStart(c) || c == '_' || c == '+' || c == '-';
    }
}
