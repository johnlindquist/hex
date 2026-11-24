package anomaly.hex.android.keyboard;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileInputStream;
import org.junit.BeforeClass;
import org.junit.Test;

public class CapitalizationRulesTest {
    private static WordDictionary dict;

    @BeforeClass public static void loadDict() throws Exception {
        File f = new File("src/main/assets/keyboard/en_freq.txt");
        if (!f.exists()) f = new File("app/src/main/assets/keyboard/en_freq.txt");
        assertTrue(f.isFile());
        try (FileInputStream in = new FileInputStream(f)) {
            dict = WordDictionary.load(in);
        }
    }

    @Test public void fixesDoubleCapSlipsWithoutBreakingAcronymsOrBrands() {
        assertEquals("This", CapitalizationRules.fixDoubleCap("THis"));
        assertEquals("Sounds", CapitalizationRules.fixDoubleCap("SOunds"));
        assertEquals("Don't", CapitalizationRules.fixDoubleCap("DOn't"));
        assertEquals("GitHub", CapitalizationRules.fixDoubleCap("GIthub"));
        assertNull(CapitalizationRules.fixDoubleCap("AI"));
        assertNull(CapitalizationRules.fixDoubleCap("NASA"));
        assertNull(CapitalizationRules.fixDoubleCap("This"));
        assertNull(CapitalizationRules.fixDoubleCap("iPhone"));
    }

    @Test public void detectsSentenceStartBoundariesAndIgnoresAbbreviations() {
        assertTrue(CapitalizationRules.shouldAutoCapAfter(""));
        assertTrue(CapitalizationRules.shouldAutoCapAfter("   "));
        assertTrue(CapitalizationRules.shouldAutoCapAfter("Hello. "));
        assertTrue(CapitalizationRules.shouldAutoCapAfter("Really? "));
        assertTrue(CapitalizationRules.shouldAutoCapAfter("Done! "));
        assertTrue(CapitalizationRules.shouldAutoCapAfter("First line\n"));
        assertFalse(CapitalizationRules.shouldAutoCapAfter("Hello"));
        assertFalse(CapitalizationRules.shouldAutoCapAfter("Hello "));
        assertFalse(CapitalizationRules.shouldAutoCapAfter("Wait... "));
        assertFalse(CapitalizationRules.shouldAutoCapAfter("e.g. "));
        assertFalse(CapitalizationRules.shouldAutoCapAfter("Dr. "));
    }

    @Test public void autocorrectFixesDoubleCapsCanonicalCasingAndAllCaps() {
        var engine = new AutocorrectEngine(
                KeyboardLanguage.EN, dict, PersonalAutocorrectStore.inMemory(), true,
                SystemUserDictionary.Snapshot.EMPTY, true);

        // 1. Double-cap Shift-hold slips (known words & typos)
        assertEquals("This", engine.correctOnSpace("THis").caseMatched());
        assertEquals("This", engine.correctOnSpace("THsi").caseMatched());
        assertEquals("Don't", engine.correctOnSpace("DOn't").caseMatched());
        assertEquals("Don't", engine.correctOnSpace("DOnt").caseMatched());

        // 2. Pronoun I' contractions & canonical proper nouns/brands
        assertEquals("I'm", engine.correctOnSpace("i'm").caseMatched());
        assertEquals("I've", engine.correctOnSpace("i've").caseMatched());
        assertEquals("I'll", engine.correctOnSpace("i'll").caseMatched());
        assertEquals("I'd", engine.correctOnSpace("i'd").caseMatched());
        assertEquals("Monday", engine.correctOnSpace("monday").caseMatched());
        assertEquals("English", engine.correctOnSpace("english").caseMatched());
        assertEquals("GitHub", engine.correctOnSpace("github").caseMatched());
        assertEquals("iOS", engine.correctOnSpace("ios").caseMatched());
        assertEquals("macOS", engine.correctOnSpace("macos").caseMatched());
        assertEquals("TypeScript", engine.correctOnSpace("typescript").caseMatched());
        // Ambiguous months ("may", "march") must NEVER auto-capitalize
        assertNull(engine.correctOnSpace("may"));
        assertNull(engine.correctOnSpace("march"));

        // 3. ALL-CAPS preservation for 5+ letter typos/contractions while protecting short acronyms/identifiers
        assertEquals("DIDN'T", engine.correctOnSpace("DIDNT").caseMatched());
        assertEquals("RECEIVE", engine.correctOnSpace("RECIEVE").caseMatched());
        assertNull(engine.correctOnSpace("NASA"));
        assertNull(engine.correctOnSpace("HTTP"));
        assertNull(engine.correctOnSpace("WIP"));
    }

    @Test public void sentenceFixerFixesSentenceStartDoubleCapMidSentenceShiftGrazeAndBrands() {
        var analysis = TailCorrectionEngine.analyzeSentence(
                "hey i'm pushing This to github on monday",
                dict, SpatialKeyboardDecoder.DecodeContext.EMPTY);
        assertFalse(analysis.phraseFixes().isEmpty());
        assertEquals("Hey I'm pushing this to GitHub on Monday", analysis.phraseFixes().get(0).label());

        var doubleCap = TailCorrectionEngine.analyzeSentence(
                "THis Works great",
                dict, SpatialKeyboardDecoder.DecodeContext.EMPTY);
        assertFalse(doubleCap.phraseFixes().isEmpty());
        assertEquals("This works great", doubleCap.phraseFixes().get(0).label());
    }
}
