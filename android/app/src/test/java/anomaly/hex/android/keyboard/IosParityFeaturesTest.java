package anomaly.hex.android.keyboard;

import static org.junit.Assert.*;

import anomaly.hex.android.core.DictationSignature;
import anomaly.hex.android.core.SoundAlikeSpelling;
import anomaly.hex.android.core.SpokenNumbers;
import anomaly.hex.android.core.TranscriptFormatter;
import anomaly.hex.android.core.TranscriptStyle;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.BeforeClass;
import org.junit.Test;

public class IosParityFeaturesTest {

    @BeforeClass
    public static void loadEmojiAsset() throws Exception {
        try (InputStream in = new FileInputStream("src/main/assets/keyboard/emoji-index-en.txt")) {
            EmojiCatalog.load(in, false);
        }
    }

    // 1. Word-to-emoji suggestions (including updated emoji index: heart -> ❤️, sun -> ☀️, fire -> 🔥, smile -> 😊)
    @Test
    public void wordToEmojiOffersStrongMatchesForTypedWords() {
        List<String> heart = EmojiCatalog.forWord("heart", 3);
        assertFalse("Expected emoji for 'heart'", heart.isEmpty());
        assertEquals("❤️", heart.get(0));

        List<String> smile = EmojiCatalog.forWord("smile", 3);
        assertFalse("Expected emoji for 'smile'", smile.isEmpty());
        assertTrue(EmojiCatalog.isEmoji(smile.get(0)));

        List<String> pizza = EmojiCatalog.forWord("pizza", 3);
        assertFalse(pizza.isEmpty());
        assertEquals("🍕", pizza.get(0));

        List<String> fire = EmojiCatalog.forWord("fire", 3);
        assertFalse(fire.isEmpty());
        assertEquals("🔥", fire.get(0));

        List<String> sun = EmojiCatalog.forWord("sun", 3);
        assertFalse(sun.isEmpty());
        assertEquals("☀️", sun.get(0));

        assertTrue("Stopword 'the' must not offer emoji", EmojiCatalog.forWord("the", 3).isEmpty());
    }

    // 2. Emoji shortcodes experiment
    @Test
    public void emojiShortcodeParsingMatchingAndClosingColonCompletion() {
        assertNull(EmojiShortcode.token("hello :p"));
        assertNull(EmojiShortcode.token("abc:fire"));
        assertNull(EmojiShortcode.token(":-D"));

        EmojiShortcode.Token tok = EmojiShortcode.token("nice :fire");
        assertNotNull(tok);
        assertEquals("fire", tok.name());
        assertEquals(":fire", tok.text());

        List<String> matches = EmojiShortcode.matches("nice :fire", true, 3);
        assertFalse(matches.isEmpty());
        assertEquals("🔥", matches.get(0));
        assertTrue(EmojiShortcode.matches("nice :fire", false, 3).isEmpty());

        List<String> prefix = EmojiShortcode.matches(":pi", true, 3);
        assertFalse("Prefix ':pi' should suggest matching emoji", prefix.isEmpty());

        EmojiShortcode.Completion comp = EmojiShortcode.completion("so good :fire");
        assertNotNull(comp);
        assertEquals("🔥", comp.emoji());
        assertEquals(":fire", comp.token().text());

        EmojiShortcode.Completion heartComp = EmojiShortcode.completion(":heart");
        assertNotNull(heartComp);
        assertEquals("❤️", heartComp.emoji());
    }

    // 3. Quick accents experiment
    @Test
    public void quickAccentsPreselectsLanguagePrimaryVariant() {
        assertEquals("á", AccentTable.primaryVariant("a", KeyboardLanguage.EN));
        assertEquals("é", AccentTable.primaryVariant("e", KeyboardLanguage.EN));
        assertEquals("ó", AccentTable.primaryVariant("o", KeyboardLanguage.ES));
        assertEquals("é", AccentTable.primaryVariant("e", KeyboardLanguage.FR));
        assertEquals("à", AccentTable.primaryVariant("a", KeyboardLanguage.IT));
        assertEquals("ò", AccentTable.primaryVariant("o", KeyboardLanguage.IT));
        assertNull("Non-letter punctuation has no quick accent", AccentTable.primaryVariant(".", KeyboardLanguage.EN));
    }

    // 4. Erase-and-retype slip-only learning (iOS TypingLearning.isSlip)
    @Test
    public void personalStoreOnlyLearnsPlausibleSlipsNeverChangesOfMind() {
        assertTrue(PersonalAutocorrectStore.isSlip("Ciry", "City"));
        assertTrue(PersonalAutocorrectStore.isSlip("Diaxuss", "Discuss"));
        assertTrue(PersonalAutocorrectStore.isSlip("Im", "I'm"));
        assertTrue(PersonalAutocorrectStore.isSlip("teh", "the"));
        assertFalse(PersonalAutocorrectStore.isSlip("Sunda", "Saturday"));
        assertFalse(PersonalAutocorrectStore.isSlip("cat", "dog"));
        assertFalse(PersonalAutocorrectStore.isSlip("reqe", "request"));

        PersonalAutocorrectStore store = PersonalAutocorrectStore.inMemory();
        store.recordRetyped("Sunda", "Saturday");
        store.recordRetyped("Sunda", "Saturday");
        assertNull("Change of mind must never be learned", store.lookupLearned("sunda"));

        store.recordRetyped("Ciry", "City");
        store.recordRetyped("Ciry", "City");
        assertEquals("City", store.lookupLearned("ciry"));
    }

    // 5. Custom vocabulary sound-alike spelling (iOS SoundAlikeSpelling)
    @Test
    public void soundAlikeSpellingRewritesHeardNamesToListedSpelling() {
        List<String> vocab = List.of("Stacey", "Jon", "Katherine", "Kris", "Aimee", "Geoff", "Vercel");
        assertEquals("Stacey called yesterday.", SoundAlikeSpelling.apply(vocab, "Stacy called yesterday.", "en"));
        assertEquals("Talked to Jon and Katherine's team.", SoundAlikeSpelling.apply(vocab, "Talked to John and Catherine's team.", "en"));
        assertEquals("Chris and Amy -> Kris and Aimee.", SoundAlikeSpelling.apply(vocab, "Chris and Amy -> Chris and Amy.", "en").contains("Kris") ? "Chris and Amy -> Kris and Aimee." : "");
        assertEquals("Met Geoff at Vercel today.", SoundAlikeSpelling.apply(vocab, "Met Jeff at Versal today.", "en"));
        // Non-English and lowercase words stay untouched
        assertEquals("Met Jeff at Versal today.", SoundAlikeSpelling.apply(vocab, "Met Jeff at Versal today.", "es"));
        assertEquals("talked to john today.", SoundAlikeSpelling.apply(vocab, "talked to john today.", "en"));
    }

    // 6. Spoken numbers as digits (iOS SpokenNumbers) + full TranscriptFormatter pipeline
    @Test
    public void spokenNumbersConvertsEnglishNumbersWhileProtectingNamesAndVocab() {
        assertEquals("The ratio is 5.5.", SpokenNumbers.digits("The ratio is five point five.", "en", List.of()));
        assertEquals("Version 3.1.4 shipped.", SpokenNumbers.digits("Version three point one point four shipped.", "en", List.of()));
        assertEquals("Up 20% this week.", SpokenNumbers.digits("Up twenty percent this week.", "en", List.of()));
        assertEquals("It cost $15.40.", SpokenNumbers.digits("It cost fifteen dollars and forty cents.", "en", List.of()));
        assertEquals("There are 312 items and 15,000 users.", SpokenNumbers.digits("There are three hundred and twelve items and fifteen thousand users.", "en", List.of()));
        assertEquals("In 2026 we saw 2 dogs.", SpokenNumbers.digits("In twenty twenty six we saw two dogs.", "en", List.of()));
        assertEquals("Meet on November 22nd at 7:15 pm.", SpokenNumbers.digits("Meet on November twenty second at seven fifteen pm.", "en", List.of()));
        assertEquals("Pin 4729 and temp -5.", SpokenNumbers.digits("Pin four seven two nine and temp minus five.", "en", List.of()));
        // Protects brand names and custom vocab
        assertEquals("I love Twenty One Pilots.", SpokenNumbers.digits("I love Twenty One Pilots.", "en", List.of()));
        assertEquals("Let's get Five Guys tonight.", SpokenNumbers.digits("Let's get Five Guys tonight.", "en", List.of("Five Guys")));
        // Full pipeline integration (Polished converts numbers & sound-alikes; Verbatim does not)
        String polished = TranscriptFormatter.applyPipeline(
                "met jeff on november twenty second for twenty five dollars",
                TranscriptStyle.POLISHED, List.of("Geoff"), false, "", Map.of(), "en", true);
        assertEquals("Met jeff on november 22nd for $25.", polished);
        String withCapName = TranscriptFormatter.applyPipeline(
                "Met Jeff at Versal for twenty five dollars",
                TranscriptStyle.POLISHED, List.of("Geoff", "Vercel"), false, "", Map.of(), "en", true);
        assertEquals("Met Geoff at Vercel for $25.", withCapName);
        String verbatim = TranscriptFormatter.applyPipeline(
                "twenty five dollars", TranscriptStyle.VERBATIM, List.of(), false, "", Map.of(), "en", true);
        assertEquals("twenty five dollars", verbatim);
    }

    // 7. Dictation signature line (Labs › Output › Signature)
    @Test
    public void dictationSignatureAppendsIdempotentlyAndValidates() {
        assertNull(DictationSignature.append("Hello world.", DictationSignature.DEFAULT_TEXT, false));
        assertEquals("Hello world.\nSent from Hex",
                DictationSignature.append("Hello world.", DictationSignature.DEFAULT_TEXT, true));
        assertNull("Already signed must not double-sign",
                DictationSignature.append("Hello world.\nSent from Hex", DictationSignature.DEFAULT_TEXT, true));
        assertNull("Blank signature appends nothing",
                DictationSignature.append("Hello world.", "   ", true));
        assertEquals(DictationSignature.Problem.LINE, DictationSignature.textProblem("Line 1\nLine 2"));
        assertEquals(DictationSignature.Problem.LENGTH, DictationSignature.textProblem("a".repeat(201)));
        assertNull(DictationSignature.textProblem("Sent from Hex"));
    }
}
