package anomaly.hex.android.anywhere;

import java.util.*;
import java.util.regex.*;

/** Extracts 1-tap screen action intents (calendar, maps, phone, email, URL) from text. */
public final class ScreenActions {
  public enum Kind { CALENDAR, MAP, PHONE, EMAIL, LINK }

  public static final class Action {
    public final Kind kind;
    public final String label;
    public final String value;
    public Action(Kind kind, String label, String value) {
      this.kind = kind;
      this.label = label;
      this.value = value;
    }
  }

  private static final Pattern EMAIL =
      Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
  private static final Pattern URL =
      Pattern.compile("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+");
  private static final Pattern PHONE =
      Pattern.compile("(?<!\\d)(?:\\+?1[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]\\d{3}[-.\\s]\\d{4}(?!\\d)");
  private static final Pattern ADDRESS =
      Pattern.compile("\\b\\d{1,5}\\s+[A-Z][a-zA-Z0-9.'-]+(?:\\s+[A-Z][a-zA-Z0-9.'-]+){0,3}\\s+(?:St|Street|Ave|Avenue|Rd|Road|Blvd|Boulevard|Dr|Drive|Ln|Lane|Way|Ct|Court|Pl|Place|Pkwy|Parkway)\\b\\.?");
  private static final Pattern EVENT =
      Pattern.compile("(?i)\\b(?:(?:mon|tue|wed|thu|fri|sat|sun)(?:day)?|tomorrow|today|(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+\\d{1,2})\\b[^\\n]{0,30}\\b\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)\\b");

  private ScreenActions() {}

  public static List<Action> extract(String text) {
    if (text == null || text.isBlank()) return Collections.emptyList();
    List<Action> out = new ArrayList<>();
    Matcher ev = EVENT.matcher(text);
    if (ev.find()) out.add(new Action(Kind.CALENDAR, "Add to Calendar", ev.group().trim()));
    Matcher addr = ADDRESS.matcher(text);
    if (addr.find()) out.add(new Action(Kind.MAP, "Open in Maps", addr.group().trim()));
    Matcher phone = PHONE.matcher(text);
    if (phone.find()) out.add(new Action(Kind.PHONE, "Call " + phone.group().trim(), phone.group().trim()));
    Matcher email = EMAIL.matcher(text);
    if (email.find()) out.add(new Action(Kind.EMAIL, "Email " + email.group().trim(), email.group().trim()));
    Matcher url = URL.matcher(text);
    if (url.find()) out.add(new Action(Kind.LINK, "Open link", url.group().trim()));
    return out;
  }
}
