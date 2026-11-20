package anomaly.hex.android.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.*;

public final class TranscriptFormatter {
    private TranscriptFormatter() {}
    private static final Pattern FILLER = Pattern.compile("(?i)(?:^|(?<=[\\s,]))(?:um+|uh+|er+|ah+)(?=[\\s,.!?]|$),?\\s*");
    static String collapse(String s) {
        return s.replaceAll("[ \\t]+", " ").replaceAll("(?m)^ +| +$", "").strip();
    }
    static String stripFillers(String s) { return collapse(FILLER.matcher(s).replaceAll(" ")); }
    private static String punctuation(String s) {
        return s.replaceAll("\\s+([,.;:!?])", "$1").replaceFirst("^[,;:]+\\s*", "").strip();
    }
    private static boolean address(String s,int i) {
        for (; i<s.length() && !Character.isWhitespace(s.charAt(i)); i++) if (s.charAt(i)=='@') return true;
        return false;
    }
    private static String capitalize(String s, boolean firstOnly) {
        StringBuilder out=new StringBuilder(); boolean next=true, ended=false;
        for (int i=0;i<s.length();) {
            int c=s.codePointAt(i); int start=i; i+=Character.charCount(c);
            if (next && Character.isLetter(c)) {
                out.appendCodePoint(address(s,start)?c:Character.toUpperCase(c)); next=false;
                if (firstOnly) return out.append(s,i,s.length()).toString();
                continue;
            } else if (!firstOnly) {
                if (c=='\n') { next=true; ended=false; }
                else if (".!?".indexOf(c)>=0) { next=false; ended=true; }
                else if (Character.isWhitespace(c)) { if (ended) { next=true; ended=false; } }
                else if (!(ended && "\"”’')]".indexOf(c)>=0) && "\"“(¿¡".indexOf(c)<0) { next=false; ended=false; }
            }
            out.appendCodePoint(c);
        }
        return out.toString();
    }
    private static String notes(String s) {
        String[] lines=s.replaceAll("(?i)\\bnew paragraph\\b[,.]?\\s*","\n\n")
            .replaceAll("(?i)\\bnew line\\b[,.]?\\s*","\n")
            .replaceAll("(?i)(?:^|\\s+)(?:bullet point|next bullet|bullet)\\b[:,-]?\\s*","\n- ").split("\n",-1);
        for (int i=0;i<lines.length;i++) {
            String line=collapse(lines[i]);
            lines[i]=line.startsWith("- ")?"- "+capitalize(punctuation(line.substring(2)),true):capitalize(punctuation(line),true);
        }
        return String.join("\n",lines).strip();
    }
    public static String format(String raw, TranscriptStyle style, String beforeCursor) {
        String s=raw==null?"":raw.strip();
        if (s.isEmpty()) return "";
        if (style==TranscriptStyle.VERBATIM) s=collapse(s);
        else {
            s=stripFillers(s);
            if (style==TranscriptStyle.NOTES) s=notes(s);
            else if (style==TranscriptStyle.CASUAL) {
                s=punctuation(s.toLowerCase(Locale.ROOT));
                if (s.matches("(?s)[^.!?]*\\.")) s=s.substring(0,s.length()-1);
            } else {
                s=capitalize(punctuation(s),false);
                if (!s.isEmpty() && ".!?…\"”)]".indexOf(s.charAt(s.length()-1))<0 && !(!s.matches("(?s).*\\s.*") && s.contains("@"))) s+=".";
            }
        }
        if (s.isEmpty() || beforeCursor==null || beforeCursor.isEmpty()) return s;
        boolean sentence=true;
        for (int i=beforeCursor.length()-1;i>=0;i--) {
            char c=beforeCursor.charAt(i);
            if (c=='\n' || c=='\r') break;
            if (Character.isWhitespace(c) || "\"”’')]»".indexOf(c)>=0) continue;
            sentence=".!?…。".indexOf(c)>=0; break;
        }
        if (!sentence && style!=TranscriptStyle.VERBATIM && Character.isUpperCase(s.codePointAt(0))) {
            Matcher m=Pattern.compile("^[\\p{L}'’]+").matcher(s);
            if (m.find()) {
                String word=m.group();
                if (!word.equals("I") && !word.startsWith("I'") && !word.startsWith("I’") && word.codePoints().skip(1).noneMatch(Character::isUpperCase)) {
                    s=word.substring(0,Character.charCount(word.codePointAt(0))).toLowerCase(Locale.ROOT)+s.substring(Character.charCount(word.codePointAt(0)));
                }
            }
        }
        char last=beforeCursor.charAt(beforeCursor.length()-1);
        return !Character.isWhitespace(last) && "([{\"“‘«¿¡".indexOf(last)<0 && ".,!?;:)]}”\n".indexOf(s.charAt(0))<0?" "+s:s;
    }
    public static String applyPipeline(String raw, TranscriptStyle style, List<String> vocab, boolean spokenCleanup, String beforeCursor) {
        String text=raw==null?"":raw;
        if (spokenCleanup) text=SpokenCleanupRules.apply(text);
        return CustomVocabulary.apply(format(CustomVocabulary.apply(text,vocab),style,beforeCursor),vocab);
    }
}
