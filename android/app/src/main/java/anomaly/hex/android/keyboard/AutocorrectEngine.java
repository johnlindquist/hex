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
    private final Map<String, String> fixes = new HashMap<>();
    private final LinkedHashSet<String> suppressed = new LinkedHashSet<>();
    private Correction pending;
    private String undoContext;

    public AutocorrectEngine() { this(KeyboardLanguage.EN); }
    public AutocorrectEngine(KeyboardLanguage language) {
        String data = switch (language) {
            case EN -> "teh:the thw:the adn:and dont:don't cant:can't wont:won't im:I'm ive:I've "
                    + "recieve:receive seperate:separate definately:definitely becuase:because widht:width "
                    + "hte:the taht:that wiht:with fromm:from jsut:just youre:you're theyre:they're "
                    + "didnt:didn't doesnt:doesn't isnt:isn't wasnt:wasn't wouldnt:wouldn't couldnt:couldn't "
                    + "shouldnt:shouldn't havent:haven't thats:that's theres:there's thier:their acheive:achieve";
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
        if (fix == null || suppressed.contains(lower)) return null;
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
        return List.copyOf(result);
    }
}
