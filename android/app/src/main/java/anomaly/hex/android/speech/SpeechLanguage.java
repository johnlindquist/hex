package anomaly.hex.android.speech;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Parakeet int8 recognizes speech multilingually. This choice supplies Hex's locale for
 * transcript formatting and filler rules; it does not force the recognizer's language. */
public final class SpeechLanguage {
  public static final String AUTO = "auto";
  private static final List<String> SUPPORTED = Collections.unmodifiableList(Arrays.asList(
      "en", "bg", "hr", "cs", "da", "nl", "et", "fi", "fr", "de", "el", "hu", "it", "lv", "lt",
      "mt", "pl", "pt", "ro", "ru", "sk", "sl", "es", "sv", "uk"));
  private final SharedPreferences prefs;

  public SpeechLanguage(Context ctx) {
    prefs = ctx.getSharedPreferences("hex_speech_language", Context.MODE_PRIVATE);
  }

  public static List<String> supportedIds() { return SUPPORTED; }

  public String selectedId() {
    String id = prefs.getString("selected", AUTO);
    return SUPPORTED.contains(id) ? id : AUTO;
  }

  public static Locale resolveLocale(String selected, Locale deviceLocale) {
    if (SUPPORTED.contains(selected)) return Locale.forLanguageTag(selected);
    return deviceLocale != null && SUPPORTED.contains(deviceLocale.getLanguage())
        ? deviceLocale : Locale.ENGLISH;
  }

  public List<String> recentIds() {
    return orderedRecent(AUTO, Arrays.asList(prefs.getString("recent", "").split(",")));
  }

  static List<String> orderedRecent(String id, List<String> previous) {
    List<String> result = new ArrayList<>(5);
    if (SUPPORTED.contains(id)) result.add(id);
    for (String old : previous) {
      if (SUPPORTED.contains(old) && !result.contains(old)) result.add(old);
      if (result.size() == 5) break;
    }
    return result;
  }

  public void select(String id) {
    if (!AUTO.equals(id) && !SUPPORTED.contains(id)) throw new IllegalArgumentException("Unknown language");
    synchronized (SpeechLanguage.class) {
      prefs.edit().putString("selected", id)
          .putString("recent", String.join(",", orderedRecent(id, recentIds()))).apply();
    }
  }
}
