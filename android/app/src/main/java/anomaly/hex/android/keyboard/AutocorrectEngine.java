package anomaly.hex.android.keyboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Per-editor state; the caller applies edits. Only a literal space requests a correction. */
public final class AutocorrectEngine {
    public record Correction(String original, String replacement, String caseMatched) {}
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{M}\\p{N}_'’]+\\z");
    // Ordered for deterministic strip ranking. A small lexicon cannot establish that an
    // unknown spelling isn't a real word or name, so fuzzy matches never commit on space.
    private static final List<String> COMMON = List.of(("about after again always another answer anyone anything around away back because before "
            + "being believe better between bring build business called calling change check children city class close "
            + "coffee come coming common company complete computer could course days different doing done down during each "
            + "early easy enough even evening ever every everyone everything example face fact family feel find fine finish "
            + "first follow food form found four free friend friends from full future game getting give given good great group "
            + "hand happy hard have head hear heard heart hello help here high hold home hope hour house however idea important "
            + "information inside into issue join just keep kind knew know known language large last late later learn least leave left "
            + "less letter life light like line list little live long look looking love made mail main make making many matter "
            + "maybe mean means meet meeting message might mind minute minutes miss moment money month months more morning most "
            + "move much must name near need never news next nice night note nothing number often once only open order other "
            + "ourselves outside over page part party pass past people person phone pick picture place plan play please point "
            + "possible power present pretty probably problem program project public question quick quite read ready real really "
            + "reason receive remember reply report rest return right room running said same save school second seem seen send "
            + "sense sent separate serious service several share short should show side since small some someone something sometimes "
            + "soon sorry sound space speak special start state stay step still stop story street strong study such sure system "
            + "take talk team tell test text than thank thanks that their them then there these they thing things think third this "
            + "those though thought three through time today together told tomorrow tonight took town true turn type under until "
            + "used user usually very visit wait walk want warm watch water week weekend welcome well went were what when where "
            + "whether which while white whole whose will wish with within without word words work working world worry worth would "
            + "write wrong year years yesterday your yourself").split(" "));
    private final boolean english;
    private final Map<String, String> fixes = new HashMap<>();
    private final LinkedHashSet<String> suppressed = new LinkedHashSet<>();
    private Correction pending;
    private String undoContext;

    public AutocorrectEngine() { this(KeyboardLanguage.EN); }
    public AutocorrectEngine(KeyboardLanguage language) {
        english = language == KeyboardLanguage.EN;
        String data = switch (language) {
            case EN -> "teh:the thw:the adn:and dont:don't cant:can't wont:won't im:I'm ive:I've "
                    + "recieve:receive seperate:separate definately:definitely becuase:because widht:width "
                    + "hte:the taht:that wiht:with fromm:from jsut:just youre:you're theyre:they're "
                    + "didnt:didn't doesnt:doesn't isnt:isn't wasnt:wasn't wouldnt:wouldn't couldnt:couldn't "
                    + "shouldnt:shouldn't havent:haven't thats:that's theres:there's thier:their acheive:achieve "
                    + "i:I freind:friend tommorow:tomorrow tommorrow:tomorrow untill:until occured:occurred wierd:weird "
                    + "woudl:would shoudl:should coudl:could abotu:about whcih:which waht:what knwo:know liek:like "
                    + "tiem:time peopel:people realy:really werent:weren't hasnt:hasn't hadnt:hadn't whats:what's "
                    + "whos:who's heres:here's youve:you've weve:we've theyve:they've arent:aren't mustnt:mustn't "
                    + "youll:you'll theyll:they'll thatll:that'll itll:it'll hows:how's "
                    + "wheres:where's theyd:they'd "
                    + "adress:address becasue:because buisness:business goverment:government "
                    + "neccessary:necessary probaly:probably thansk:thanks thnaks:thanks typign:typing";
            case ES -> "tambien:también despues:después ademas:además entocnes:entonces";
            case FR -> "beacoup:beaucoup mercie:merci deja:déjà aujourdhui:aujourd'hui";
            case DE -> "vileicht:vielleicht nähmlich:nämlich warscheinlich:wahrscheinlich";
            case IT -> "perche:perché piu:più poiche:poiché";
            case PT -> "nao:não tambem:também voce:você obrigdao:obrigado";
            case NB -> "kansje:kanskje desverre:dessverre egentilg:egentlig";
        };
        for (String pair : data.split(" ")) {
            String[] p = pair.split(":");
            fixes.put(p[0], p[1]);
        }
    }

    private Correction lookup(String word) {
        if (word.isEmpty()) return null;
        String lower = word.toLowerCase(Locale.ROOT);
        String fix = fixes.get(lower);
        if (fix == null || word.equals(fix) || suppressed.contains(lower)) return null;
        boolean title = !word.equals(lower);
        if (title && !word.equals(capitalize(lower))) return null;
        return new Correction(word, fix, title ? capitalize(fix) : fix);
    }

    private static String capitalize(String word) {
        return word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
    }

    public Correction correctOnSpace(String textBeforeCursor) {
        clearUndo();
        if (textBeforeCursor == null) return null;
        Matcher token = TOKEN.matcher(textBeforeCursor);
        if (!token.find()) return null;
        pending = lookup(token.group());
        if (pending != null) {
            // A bounded suffix also works when the editor gives a sliding window of context.
            int start = Math.max(0, token.start() - 32);
            undoContext = textBeforeCursor.substring(start, token.start()) + pending.caseMatched + " ";
        }
        return pending;
    }

    public Correction correct(String before, String inserted) {
        if (" ".equals(inserted)) return correctOnSpace(before);
        clearUndo();
        return null;
    }

    public void clearUndo() { pending = null; undoContext = null; }

    public Correction checkBackspaceUndo(String textBeforeCursor) {
        Correction undo = pending != null && textBeforeCursor != null && textBeforeCursor.endsWith(undoContext) ? pending : null;
        clearUndo();
        if (undo != null) suppress(undo.original);
        return undo;
    }

    public void suppress(String original) {
        String word = original.toLowerCase(Locale.ROOT);
        suppressed.remove(word);
        suppressed.add(word);
        if (suppressed.size() > 128) suppressed.remove(suppressed.iterator().next());
    }

    public List<String> suggestions(String currentWord, List<String> customVocab) {
        if (currentWord == null || currentWord.isBlank()) return List.of();
        List<String> result = new ArrayList<>(3);
        result.add("“" + currentWord + "”");
        Correction fix = lookup(currentWord);
        if (fix != null) result.add(fix.caseMatched);
        for (String word : customVocab) {
            if (result.size() == 3) break;
            if (word != null && word.length() > currentWord.length() && word.regionMatches(true, 0, currentWord, 0, currentWord.length()) && !result.contains(word)) result.add(word);
        }
        if (english && currentWord.length() >= 4 && !suppressed.contains(currentWord.toLowerCase(Locale.ROOT))
                && isPlainWord(currentWord) && result.size() < 3) {
            String lower = currentWord.toLowerCase(Locale.ROOT);
            for (String word : customVocab) {
                if (result.size() == 3) break;
                if (word != null && Math.abs(word.length() - lower.length()) <= 1
                        && oneEdit(lower, word.toLowerCase(Locale.ROOT)) && !result.contains(word)) result.add(word);
            }
            if (result.size() < 3 && !COMMON.contains(lower)) {
                for (String word : COMMON) {
                    if (result.size() == 3) break;
                    if (!oneEdit(lower, word)) continue;
                    String match = lower.equals(currentWord) ? word : capitalize(word);
                    if (!result.contains(match)) result.add(match);
                }
            }
        }
        return List.copyOf(result);
    }

    private static boolean isPlainWord(String word) {
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (!(c >= 'a' && c <= 'z' || i == 0 && c >= 'A' && c <= 'Z')) return false;
        }
        return true;
    }

    // Exactly one edit. After the first mismatch there is only one suffix to verify.
    private static boolean oneEdit(String a, String b) {
        if (a.length() > b.length()) return oneEdit(b, a);
        int n = a.length();
        if (b.length() - n > 1) return false;
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        if (b.length() != n) return a.regionMatches(i, b, i + 1, n - i);
        if (i == n) return false;
        if (a.regionMatches(i + 1, b, i + 1, n - i - 1)) return true;
        return i + 1 < n && a.charAt(i) == b.charAt(i + 1) && a.charAt(i + 1) == b.charAt(i)
                && a.regionMatches(i + 2, b, i + 2, n - i - 2);
    }
}
