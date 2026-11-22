package anomaly.hex.android.keyboard;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ephemeral dictionary of words currently visible on screen (active editor + active app window).
 * Never persisted to disk and cleared whenever the editor/app changes or a password field opens.
 */
public final class ScreenContextVocabulary {
  public static final ScreenContextVocabulary EMPTY = new ScreenContextVocabulary(Map.of());
  private static final int MAX_WORDS = 300;
  private static final Pattern WORD_TOKEN = Pattern.compile("[\\p{L}][\\p{L}'’]{2,23}");
  private static final Set<String> STOP_WORDS = Set.of(
      "the", "and", "for", "that", "this", "with", "you", "are", "was", "were", "have", "has",
      "had", "not", "but", "from", "they", "them", "their", "what", "when", "where", "which",
      "will", "would", "could", "should", "there", "about", "into", "your", "just", "like",
      "some", "more", "than", "then", "also", "been", "only", "very", "here", "make", "made");

  private final Map<String, String> lowerToCanonical;

  private ScreenContextVocabulary(Map<String, String> map) {
    this.lowerToCanonical = map.isEmpty() ? Map.of() : Collections.unmodifiableMap(map);
  }

  public static ScreenContextVocabulary fromText(CharSequence text) {
    if (text == null || text.length() == 0) return EMPTY;
    return fromWords(extractWords(text, MAX_WORDS));
  }

  public static ScreenContextVocabulary fromWords(Collection<String> words) {
    if (words == null || words.isEmpty()) return EMPTY;
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    for (String raw : words) {
      if (map.size() >= MAX_WORDS) break;
      addWord(map, raw);
    }
    return map.isEmpty() ? EMPTY : new ScreenContextVocabulary(map);
  }

  public ScreenContextVocabulary merge(Collection<String> moreWords) {
    if (moreWords == null || moreWords.isEmpty()) return this;
    LinkedHashMap<String, String> map = new LinkedHashMap<>(lowerToCanonical);
    for (String raw : moreWords) {
      if (map.size() >= MAX_WORDS) break;
      addWord(map, raw);
    }
    return map.isEmpty() ? EMPTY : new ScreenContextVocabulary(map);
  }

  public boolean isEmpty() { return lowerToCanonical.isEmpty(); }
  public int size() { return lowerToCanonical.size(); }
  public boolean contains(String lower) { return lower != null && lowerToCanonical.containsKey(lower); }
  public String canonical(String lower) { return lower == null ? null : lowerToCanonical.get(lower); }
  public Map<String, String> asMap() { return lowerToCanonical; }

  /** Returns up to `max` salient words (proper nouns / uncommon terms first) for local LLM prompts. */
  public List<String> promptHints(int max) {
    if (lowerToCanonical.isEmpty() || max <= 0) return List.of();
    List<String> proper = new ArrayList<>();
    List<String> other = new ArrayList<>();
    for (var e : lowerToCanonical.entrySet()) {
      String lower = e.getKey();
      String canon = e.getValue();
      if (STOP_WORDS.contains(lower)) continue;
      if (!canon.equals(lower)) proper.add(canon);
      else other.add(canon);
    }
    List<String> out = new ArrayList<>(Math.min(max, proper.size() + other.size()));
    for (String w : proper) {
      if (out.size() >= max) break;
      out.add(w);
    }
    for (String w : other) {
      if (out.size() >= max) break;
      out.add(w);
    }
    return out;
  }

  /** Extracts clean alphabetic words from arbitrary screen/editor text, skipping URLs, emails, and code punctuation. */
  public static List<String> extractWords(CharSequence text, int limit) {
    if (text == null || text.length() == 0 || limit <= 0) return List.of();
    String s = text.toString();
    LinkedHashMap<String, String> seen = new LinkedHashMap<>();
    Matcher m = WORD_TOKEN.matcher(s);
    while (m.find() && seen.size() < limit) {
      int start = m.start();
      int end = m.end();
      if (start > 0) {
        char prev = s.charAt(start - 1);
        if (prev == '@' || prev == '/' || prev == '#' || prev == '_' || Character.isDigit(prev)) continue;
      }
      if (end < s.length()) {
        char next = s.charAt(end);
        if (next == '@' || next == '/' || next == '_' || Character.isDigit(next)) continue;
        // Skip URL scheme/domain tokens like "https:" or "example.com"
        if (next == ':' && end + 2 < s.length() && s.charAt(end + 1) == '/') continue;
      }
      String tok = m.group().replace('’', '\'');
      if (tok.endsWith("'")) tok = tok.substring(0, tok.length() - 1);
      if (tok.length() < 3) continue;
      addWord(seen, tok);
    }
    return new ArrayList<>(seen.values());
  }

  private static void addWord(Map<String, String> map, String raw) {
    if (raw == null) return;
    String w = raw.trim().replace('’', '\'');
    if (w.length() < 3 || w.length() > 24) return;
    for (int i = 0; i < w.length(); i++) {
      char c = w.charAt(i);
      if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c == '\'' && i > 0 && i + 1 < w.length()))) {
        return;
      }
    }
    String lower = w.toLowerCase(Locale.ROOT);
    String prev = map.get(lower);
    if (prev == null) {
      map.put(lower, w);
    } else if (isMixedCase(w) && !isMixedCase(prev)) {
      // Prefer explicit camelCase / PascalCase (e.g. "PostgreSQL", "TypeScript") over plain lowercase/UPPERCASE.
      map.put(lower, w);
    } else if (w.equals(lower) && prev.equals(prev.toUpperCase(Locale.ROOT))) {
      // Prefer lowercase over ALL-CAPS header text.
      map.put(lower, lower);
    }
  }

  private static boolean isMixedCase(String w) {
    boolean hasUpper = false, hasLower = false;
    for (int i = 0; i < w.length(); i++) {
      char c = w.charAt(i);
      if (c >= 'A' && c <= 'Z') hasUpper = true;
      else if (c >= 'a' && c <= 'z') hasLower = true;
    }
    return hasUpper && hasLower;
  }
}
