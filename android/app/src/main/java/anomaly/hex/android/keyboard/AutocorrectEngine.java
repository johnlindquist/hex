package anomaly.hex.android.keyboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final Set<String> SHORTHAND_ALLOWLIST = Set.of(
            "ok", "okay", "ty", "thx", "pls", "plz", "tmrw", "tbh", "idk", "imo", "imho",
            "lol", "lmao", "omg", "brb", "btw", "afaik", "fyi", "nvm", "rn", "irl", "jk",
            "np", "yw", "smh", "ikr", "ngl", "fr", "gg", "dm", "pr", "prs", "ci", "ui",
            "ux", "ios", "macos", "api", "apis", "url", "urls", "json", "sql", "ssh", "cli",
            "gpu", "cpu", "ram", "sdk", "hex", "wisp", "seer", "rune", "kit", "gonna",
            "wanna", "gotta", "kinda", "sorta", "yeah", "yep", "nope", "nah", "hey", "yo"
    );
    private static final Map<String, String> SUGGEST_ONLY_CONTRACTIONS = Map.of(
            "ill", "I'll",
            "id", "I'd",
            "were", "we're",
            "wed", "we'd",
            "well", "we'll",
            "its", "it's",
            "lets", "let's",
            "hell", "he'll",
            "shell", "she'll"
    );
    private final boolean english;
    private final WordDictionary dictionary;
    private final PersonalAutocorrectStore store;
    private final SystemUserDictionary.Snapshot userDict;
    private final boolean allowLearning;
    private final Map<String, String> fixes = new HashMap<>();
    private final LinkedHashSet<String> suppressed = new LinkedHashSet<>();
    private Correction pending;
    private String undoContext;

    public AutocorrectEngine() { this(KeyboardLanguage.EN, null, null, false, SystemUserDictionary.Snapshot.EMPTY); }
    public AutocorrectEngine(KeyboardLanguage language) { this(language, null, null, false, SystemUserDictionary.Snapshot.EMPTY); }
    public AutocorrectEngine(KeyboardLanguage language, WordDictionary dictionary, PersonalAutocorrectStore store, boolean allowLearning) {
        this(language, dictionary, store, allowLearning, SystemUserDictionary.Snapshot.EMPTY);
    }
    public AutocorrectEngine(KeyboardLanguage language, WordDictionary dictionary, PersonalAutocorrectStore store,
                             boolean allowLearning, SystemUserDictionary.Snapshot userDict) {
        english = language == KeyboardLanguage.EN;
        this.dictionary = dictionary;
        this.store = store;
        this.allowLearning = allowLearning;
        this.userDict = userDict == null ? SystemUserDictionary.Snapshot.EMPTY : userDict;
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

    /** Extracts the active word before cursor, returning "" if attached to @, /, ., #, _, -, :, or digits. */
    public static String currentToken(String textBeforeCursor) {
        if (textBeforeCursor == null || textBeforeCursor.isEmpty()) return "";
        Matcher m = TOKEN.matcher(textBeforeCursor);
        if (!m.find()) return "";
        int start = m.start();
        if (start > 0) {
            char prev = textBeforeCursor.charAt(start - 1);
            if (prev == '@' || prev == '/' || prev == '.' || prev == '#'
                    || prev == '_' || prev == '-' || prev == ':' || Character.isDigit(prev)) {
                return "";
            }
        }
        String tok = m.group();
        for (int i = 0; i < tok.length(); i++) {
            char c = tok.charAt(i);
            if (c == '_' || Character.isDigit(c)) return "";
        }
        return tok;
    }

    private boolean isKnownValidWord(String lower) {
        if (SHORTHAND_ALLOWLIST.contains(lower)) return true;
        if (userDict.termsLower().contains(lower)) return true;
        if (allowLearning && store != null && store.isKept(lower)) return true;
        return english && dictionary != null && dictionary.contains(lower);
    }

    private Correction lookup(String word, List<String> spellSuggestions) {
        if (word.isEmpty()) return null;
        String lower = word.toLowerCase(Locale.ROOT);
        if (suppressed.contains(lower)) return null;
        if (allowLearning && store != null && store.isKept(lower)) return null;

        // 0. Android UserDictionary explicit shortcut (e.g. "omw" -> "On my way!"), even for ALL-CAPS
        String shortcut = userDict.shortcuts().get(lower);
        if (shortcut != null && !word.equals(shortcut)) {
            return new Correction(word, shortcut, shortcut);
        }

        if (SHORTHAND_ALLOWLIST.contains(lower)) return null;
        if (userDict.termsLower().contains(lower)) return null;

        boolean title = !word.equals(lower);
        if (title && !word.equals(capitalize(lower))) return null;

        // 1. User-learned replacement (promoted after 2 consistent accepts; never auto-replaces a valid word)
        if (allowLearning && store != null) {
            String learned = store.lookupLearned(lower);
            if (learned != null && !word.equals(learned) && !(english && dictionary != null && dictionary.contains(lower))) {
                return new Correction(word, learned, matchCase(word, title, learned));
            }
        }

        // 2. Curated typo & contraction map
        String fix = fixes.get(lower);
        if (fix != null && !word.equals(fix)) {
            return new Correction(word, fix, matchCase(word, title, fix));
        }

        // 3. Two-letter neighbor/swap typo ("tk" -> "to", "fo" -> "of")
        if (english && dictionary != null && lower.length() == 2 && isPlainWord(word)) {
            String twoFix = dictionary.bestTwoLetterFix(lower);
            if (twoFix != null) {
                return new Correction(word, twoFix, matchCase(word, title, twoFix));
            }
        }

        // 4. Full 30k English frequency dictionary + QWERTY spatial-proximity 1-edit + missed-space split
        if (english && dictionary != null && dictionary.size() > 0 && lower.length() >= 3 && isPlainWord(word)) {
            if (!dictionary.contains(lower)) {
                List<WordDictionary.Candidate> edits = dictionary.bestEdits(lower, 1);
                String split = dictionary.splitMissedSpace(lower);
                if (!edits.isEmpty()) {
                    WordDictionary.Candidate top = edits.get(0);
                    // Prefer a high-frequency missed-space split ("upto" -> "up to") over a rare 1-edit word ("upton").
                    if (split != null && top.rank() > 3000) {
                        return new Correction(word, split, matchCase(word, title, split));
                    }
                    if (top.rank() < 31000 && (top.cost() <= 14 || lower.length() >= 5)) {
                        return new Correction(word, top.word(), matchCase(word, title, top.word()));
                    }
                }
                if (split != null) {
                    return new Correction(word, split, matchCase(word, title, split));
                }
            } else {
                return null; // Valid known English word: never replace on space.
            }
        }

        // 5. System SpellCheckerSession recommendation (e.g. 2-edit typos or non-English locales)
        if (spellSuggestions != null && !spellSuggestions.isEmpty() && lower.length() >= 3 && isPlainWord(word)) {
            String best = spellSuggestions.get(0);
            if (best != null && !best.isBlank() && !best.equalsIgnoreCase(word)
                    && (dictionary == null || !english || !dictionary.contains(lower))) {
                return new Correction(word, best, matchCase(word, title, best));
            }
        }
        return null;
    }

    public boolean willAutoReplace(String currentWord, List<String> spellSuggestions) {
        if (currentWord == null || currentWord.isBlank()) return false;
        return lookup(currentWord, spellSuggestions) != null;
    }

    private static String matchCase(String original, boolean title, String replacement) {
        if (replacement.isEmpty()) return replacement;
        if (!title) return replacement;
        return Character.isUpperCase(replacement.charAt(0)) ? replacement : capitalize(replacement);
    }

    private static String capitalize(String word) {
        return word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
    }

    public Correction correctOnSpace(String textBeforeCursor) {
        return correctOnSpace(textBeforeCursor, List.of());
    }

    public Correction correctOnSpace(String textBeforeCursor, List<String> spellSuggestions) {
        clearUndo();
        if (textBeforeCursor == null) return null;
        String token = currentToken(textBeforeCursor);
        if (token.isEmpty()) return null;
        int tokenStart = textBeforeCursor.length() - token.length();
        pending = lookup(token, spellSuggestions);
        if (pending != null) {
            // A bounded suffix also works when the editor gives a sliding window of context.
            int start = Math.max(0, tokenStart - 32);
            undoContext = textBeforeCursor.substring(start, tokenStart) + pending.caseMatched + " ";
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
        if (undo != null) {
            suppress(undo.original);
            if (allowLearning && store != null) store.recordRejected(undo.original, false);
        }
        return undo;
    }

    /** Record user's explicit choice from the suggestion bar (slot 0 verbatim = reject, slot 1/2 = learn). */
    public void onSuggestionPicked(String typedWord, String pickedWord) {
        if (typedWord == null || typedWord.isBlank() || pickedWord == null || pickedWord.isBlank()) return;
        if (typedWord.equalsIgnoreCase(pickedWord)) {
            suppress(typedWord);
            if (allowLearning && store != null) store.recordRejected(typedWord, true);
        } else if (allowLearning && store != null && isPlainWord(typedWord)) {
            String stored = pickedWord;
            String typedLower = typedWord.toLowerCase(Locale.ROOT);
            String pickedLower = pickedWord.toLowerCase(Locale.ROOT);
            boolean typedTitle = !typedWord.equals(typedLower) && typedWord.equals(capitalize(typedLower));
            boolean pickedTitle = !pickedWord.equals(pickedLower) && pickedWord.equals(capitalize(pickedLower));
            // If sentence-start auto-cap capitalized both typed & picked for a regular dictionary word,
            // store lowercase so mid-sentence reuse doesn't inject spurious capitals (while keeping proper nouns like "Kubernetes").
            if (typedTitle && pickedTitle && dictionary != null && dictionary.contains(pickedLower)) {
                stored = pickedLower;
            }
            store.recordAccepted(typedWord, stored);
        }
    }

    public void suppress(String original) {
        String word = original.toLowerCase(Locale.ROOT);
        suppressed.remove(word);
        suppressed.add(word);
        if (suppressed.size() > 128) suppressed.remove(suppressed.iterator().next());
    }

    public List<String> suggestions(String currentWord, List<String> customVocab) {
        return suggestions(currentWord, customVocab, List.of());
    }

    public List<String> suggestions(String currentWord, List<String> customVocab, List<String> spellSuggestions) {
        if (currentWord == null || currentWord.isBlank()) return List.of();
        List<String> result = new ArrayList<>(3);
        result.add("“" + currentWord + "”");
        String lower = currentWord.toLowerCase(Locale.ROOT);
        boolean title = !currentWord.equals(lower) && currentWord.equals(capitalize(lower));
        Correction fix = lookup(currentWord, spellSuggestions);
        if (fix != null) {
            result.add(fix.caseMatched);
        } else {
            // Suggest-only learned fix for a real word, or suggest-only English contraction ("well" -> "we'll")
            if (allowLearning && store != null) {
                String learned = store.lookupLearned(lower);
                if (learned != null && !currentWord.equals(learned)) {
                    result.add(matchCase(currentWord, title, learned));
                }
            }
            if (result.size() < 2 && english) {
                String contraction = SUGGEST_ONLY_CONTRACTIONS.get(lower);
                if (contraction != null) {
                    result.add(matchCase(currentWord, title, contraction));
                }
            }
        }
        for (String word : customVocab) {
            if (result.size() == 3) break;
            if (word != null && word.length() > currentWord.length() && word.regionMatches(true, 0, currentWord, 0, currentWord.length()) && !result.contains(word)) result.add(word);
        }
        for (String word : userDict.terms()) {
            if (result.size() == 3) break;
            if (word.length() > currentWord.length() && word.regionMatches(true, 0, currentWord, 0, currentWord.length()) && !result.contains(word)) result.add(word);
        }
        // Secondary dictionary edits or prefix completions
        if (english && dictionary != null && dictionary.size() > 0 && isPlainWord(currentWord) && result.size() < 3) {
            if (!dictionary.contains(lower) && lower.length() >= 3) {
                for (WordDictionary.Candidate c : dictionary.bestEdits(lower, 3)) {
                    if (result.size() == 3) break;
                    String m = matchCase(currentWord, title, c.word());
                    if (!result.contains(m)) result.add(m);
                }
            }
            if (result.size() < 3 && lower.length() >= 2) {
                for (String w : dictionary.prefixCompletions(lower, 3)) {
                    if (result.size() == 3) break;
                    String m = matchCase(currentWord, title, w);
                    if (!result.contains(m)) result.add(m);
                }
            }
        }
        // System SpellChecker extra suggestions
        if (spellSuggestions != null && result.size() < 3) {
            for (String s : spellSuggestions) {
                if (result.size() == 3) break;
                if (s != null && !s.isBlank() && !s.equalsIgnoreCase(currentWord)) {
                    String m = matchCase(currentWord, title, s);
                    if (!result.contains(m)) result.add(m);
                }
            }
        }
        if (english && currentWord.length() >= 4 && !suppressed.contains(lower)
                && isPlainWord(currentWord) && result.size() < 3) {
            for (String word : customVocab) {
                if (result.size() == 3) break;
                if (word != null && Math.abs(word.length() - lower.length()) <= 1
                        && oneEdit(lower, word.toLowerCase(Locale.ROOT)) && !result.contains(word)) result.add(word);
            }
            if (dictionary == null && result.size() < 3 && !COMMON.contains(lower)) {
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
