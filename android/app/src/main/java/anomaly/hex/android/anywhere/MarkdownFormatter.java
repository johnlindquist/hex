package anomaly.hex.android.anywhere;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight Markdown-to-Spannable renderer for concise Gemini mobile answers. */
public final class MarkdownFormatter {
  private static final Pattern INLINE = Pattern.compile(
      "`([^`\\n]+)`|\\*\\*([^\\n*]+?)\\*\\*|\\*([^\\s*][^\\n*]*?[^\\s*]|[^\\s*])\\*|_([^\\s_][^\\n_]*?[^\\s_]|[^\\s_])_");
  private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d+\\.)\\s+(.*)$");
  private static final Pattern BULLET = Pattern.compile("^(\\s*)[*\\-•]\\s+(.*)$");
  private static final Pattern HEADING = Pattern.compile("^#{1,4}\\s+(.*)$");

  private MarkdownFormatter() {}

  public static CharSequence format(String markdown, float density) {
    if (markdown == null || markdown.isEmpty()) return "";
    SpannableStringBuilder out = new SpannableStringBuilder();
    String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    int indentUnit = Math.max(12, Math.round(14f * density));
    int codeBg = 0xFF23252C;
    boolean prevBlank = true;

    for (String rawLine : lines) {
      String trimmed = rawLine.trim();
      if (trimmed.isEmpty()) {
        if (!prevBlank && out.length() > 0) out.append('\n');
        prevBlank = true;
        continue;
      }
      if (out.length() > 0) out.append('\n');
      prevBlank = false;

      int lineStart = out.length();
      Matcher hm = HEADING.matcher(trimmed);
      if (hm.matches()) {
        appendInline(out, hm.group(1).trim(), codeBg);
        out.setSpan(new StyleSpan(Typeface.BOLD), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(1.08f), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        continue;
      }

      Matcher bm = BULLET.matcher(rawLine);
      if (bm.matches()) {
        int spaces = bm.group(1).length();
        boolean nested = spaces >= 2;
        out.append(nested ? "◦  " : "•  ");
        appendInline(out, bm.group(2).trim(), codeBg);
        int first = nested ? indentUnit : 0;
        int rest = nested ? indentUnit * 2 : indentUnit;
        out.setSpan(new LeadingMarginSpan.Standard(first, rest), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        continue;
      }

      Matcher nm = NUMBERED.matcher(rawLine);
      if (nm.matches()) {
        int spaces = nm.group(1).length();
        String num = nm.group(2);
        int numStart = out.length();
        out.append(num).append(" ");
        out.setSpan(new StyleSpan(Typeface.BOLD), numStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        appendInline(out, nm.group(3).trim(), codeBg);
        int first = spaces >= 2 ? indentUnit : 0;
        int rest = first + Math.round(18f * density);
        out.setSpan(new LeadingMarginSpan.Standard(first, rest), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        continue;
      }

      appendInline(out, trimmed, codeBg);
    }
    return out;
  }

  /** Plain-text stripping helper for JVM unit tests (no Android Span dependencies required). */
  public static String toPlainPreview(String markdown) {
    if (markdown == null || markdown.isEmpty()) return "";
    StringBuilder out = new StringBuilder();
    String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    boolean prevBlank = true;
    for (String rawLine : lines) {
      String trimmed = rawLine.trim();
      if (trimmed.isEmpty()) {
        if (!prevBlank && out.length() > 0) out.append('\n');
        prevBlank = true;
        continue;
      }
      if (out.length() > 0) out.append('\n');
      prevBlank = false;
      Matcher hm = HEADING.matcher(trimmed);
      if (hm.matches()) {
        out.append(stripInline(hm.group(1).trim()));
        continue;
      }
      Matcher bm = BULLET.matcher(rawLine);
      if (bm.matches()) {
        out.append(bm.group(1).length() >= 2 ? "◦  " : "•  ").append(stripInline(bm.group(2).trim()));
        continue;
      }
      Matcher nm = NUMBERED.matcher(rawLine);
      if (nm.matches()) {
        out.append(nm.group(2)).append(" ").append(stripInline(nm.group(3).trim()));
        continue;
      }
      out.append(stripInline(trimmed));
    }
    return out.toString();
  }

  private static String stripInline(String text) {
    Matcher m = INLINE.matcher(text);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String rep = m.group(1) != null ? m.group(1)
          : m.group(2) != null ? m.group(2)
          : m.group(3) != null ? m.group(3) : m.group(4);
      m.appendReplacement(sb, Matcher.quoteReplacement(rep));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private static void appendInline(SpannableStringBuilder out, String text, int codeBg) {
    Matcher m = INLINE.matcher(text);
    int last = 0;
    while (m.find()) {
      if (m.start() > last) out.append(text, last, m.start());
      int start = out.length();
      if (m.group(1) != null) {
        out.append(' ').append(m.group(1)).append(' ');
        out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new BackgroundColorSpan(codeBg), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      } else if (m.group(2) != null) {
        out.append(m.group(2));
        out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      } else {
        out.append(m.group(3) != null ? m.group(3) : m.group(4));
        out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
      last = m.end();
    }
    if (last < text.length()) out.append(text, last, text.length());
  }
}
