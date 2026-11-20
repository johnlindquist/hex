package anomaly.hex.android.core;

import java.util.*;
import java.util.regex.Pattern;

/** English-only deterministic counterpart to the bounded, anchored iOS correction rules. */
public final class SpokenCleanupRules {
    private SpokenCleanupRules() {}
    private static final List<Set<String>> KINDS=List.of(
        Set.of("monday","tuesday","wednesday","thursday","friday","saturday","sunday"),
        Set.of("january","february","march","april","may","june","july","august","september","october","november","december"),
        Set.of("today","tomorrow","yesterday"),
        Set.of("one","two","three","four","five","six","seven","eight","nine","ten","eleven","twelve"));
    private static final Pattern HESITATION=Pattern.compile("(?i)(?:^|(?<=\\s))(?:um+|uh+|er+|ah+)[,.]?(?=\\s|$)\\s*");
    private static String bare(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$",""); }
    private static int kind(String word) {
        if (word.codePoints().anyMatch(Character::isDigit)) return 3;
        for (int i=0;i<KINDS.size();i++) if (KINDS.get(i).contains(word)) return i;
        return -1;
    }
    public static String apply(String text) {
        if (text==null || text.isEmpty()) return "";
        String cleaned=HESITATION.matcher(text).replaceAll("").strip();
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
