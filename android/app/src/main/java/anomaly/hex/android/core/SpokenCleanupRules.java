package anomaly.hex.android.core;

import java.util.*;
import java.util.regex.*;

/** Localized hesitation and repeat cleanup, plus English spoken addresses and anchored repairs. */
public final class SpokenCleanupRules {
    private SpokenCleanupRules() {}
    private static final List<Set<String>> KINDS=List.of(
        Set.of("monday","tuesday","wednesday","thursday","friday","saturday","sunday"),
        Set.of("january","february","march","april","may","june","july","august","september","october","november","december"),
        Set.of("today","tomorrow","yesterday"),
        Set.of("one","two","three","four","five","six","seven","eight","nine","ten","eleven","twelve"));
    private static final String LOCAL = "[a-z0-9](?:[a-z0-9._+-]*[a-z0-9])?";
    private static final String LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    private static final String DOT = "(?:\\.|\\s+dot\\s+)";
    private static final String DOMAIN = LABEL + "(?:" + DOT + LABEL + ")*" + DOT + "[a-z]{2,63}";
    private static final Pattern ADDRESS = Pattern.compile("(?i)(?<!\\S)(?:(" + LOCAL + "(?:\\s+dot\\s+" + LOCAL + ")*)\\s+)?"
        + "(?:at\\s+the\\s+rate[,.:;!?]*\\s*(?!of\\b)|at(?:-|\\s+)sign[,.:;!?]*\\s*|at\\s+(?!(?:the|that|this|a)\\b)(?=" + LABEL + "\\s+dot\\s+))"
        + "(" + DOMAIN + ")(?=[.,;!?):\\]’”'\"]*(?:\\s|$))");
    private static final Pattern SIGN = Pattern.compile("(?i)(?<!\\S)at(?:-|\\s+)sign\\b(?!\\s+language\\b)");
    private static final Pattern REPEAT = Pattern.compile("(?iu)\\b([\\p{L}]+)(?:[ \\t]+\\1\\b)+");

    static String symbols(String text) {
        Matcher m = ADDRESS.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String address = (m.group(1) == null ? "" : m.group(1)) + "@" + m.group(2);
            m.appendReplacement(out, Matcher.quoteReplacement(address.toLowerCase(Locale.ROOT).replaceAll("\\s+dot\\s+", ".")));
        }
        return SIGN.matcher(m.appendTail(out)).replaceAll("@");
    }
    private static String bare(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$",""); }
    private static int kind(String word) {
        if (word.codePoints().anyMatch(Character::isDigit)) return 3;
        for (int i=0;i<KINDS.size();i++) if (KINDS.get(i).contains(word)) return i;
        return -1;
    }
    public static String apply(String text) {
        return apply(text, "en");
    }
    public static String apply(String text, String language) {
        if (text==null || text.isEmpty()) return "";
        String cleaned = REPEAT.matcher(TranscriptFormatter.stripFillers(text, language)).replaceAll("$1");
        if (!TranscriptFormatter.language(language).equals("en")) return cleaned;
        cleaned = symbols(cleaned);
        List<String> words=new ArrayList<>(Arrays.asList(cleaned.split("\\s+"))); boolean changed=false;
        for (int i=1;i+1<words.size();i++) {
            int after=i+1;
            String marker=bare(words.get(i));
            if (marker.equals("i") && bare(words.get(after)).equals("mean")) after++;
            else if (marker.equals("no") && bare(words.get(after)).equals("wait")) after++;
            else if (!marker.equals("no") && !marker.equals("sorry")) continue;
            if (after>=words.size() || !words.get(after-1).endsWith(",") || !words.get(i-1).matches(".*[,;.!?]$")) continue;
            String first=bare(words.get(after)); int match=-1, similar=-1;
            if (first.isEmpty()) continue;
            for (int j=i-1;j>=Math.max(0,i-8);j--) {
                if (j<i-1 && words.get(j).matches(".*[.!?]$")) break;
                String earlier=bare(words.get(j));
                if (first.equals(earlier)) { match=j; break; }
                if (similar<0 && kind(first)>=0 && kind(earlier)==kind(first)) similar=j;
            }
            if (match<0) match=similar;
            if (match>=0) {
                String repair=words.get(after);
                if (Character.isUpperCase(words.get(match).codePointAt(0))) words.set(after,repair.substring(0,1).toUpperCase(Locale.ROOT)+repair.substring(1));
                words.subList(match,after).clear(); changed=true; i=match;
            }
        }
        return changed?String.join(" ",words):cleaned;
    }
}
