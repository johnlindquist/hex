package anomaly.hex.android.cleanup;

import java.util.*;
import java.util.regex.*;

/** Conservative English/date and numeral checks, not a general semantic verifier. */
public final class FactGuard {
    private static final String DAY="(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)";
    private static final Pattern DAY_RESTART=Pattern.compile("(?i)\\b"+DAY+"\\s*[,—]\\s*(?:(?:no|sorry)\\s*[,—]|I mean\\s*[,—]?)\\s*("+DAY+")\\b");
    private static final Pattern LITERAL=Pattern.compile("(?iu)\\b(?:"+DAY+"|January|February|March|April|May|June|July|August|September|October|November|December)\\b|\\p{Nd}+(?:[.:/]\\p{Nd}+)*");
    public static String prepare(String speech){return DAY_RESTART.matcher(speech).replaceAll("$1");}
    static List<String> literals(String text){List<String> all=new ArrayList<>();Matcher m=LITERAL.matcher(text);while(m.find())all.add(m.group().toLowerCase(Locale.ROOT));return all;}
    public static String guard(String source,String suggestion){return literals(source).equals(literals(suggestion))?suggestion:source;}
}
