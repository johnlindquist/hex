package anomaly.hex.android.keyboard;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.Map;
import org.junit.BeforeClass;
import org.junit.Test;

public class DictionaryAutocorrectTest {
    private static WordDictionary dict;

    @BeforeClass public static void loadRealAssetDictionary() throws Exception {
        File f = new File("src/main/assets/keyboard/en_freq.txt");
        if (!f.exists()) f = new File("app/src/main/assets/keyboard/en_freq.txt");
        assertTrue("Expected en_freq.txt asset at " + f.getAbsolutePath(), f.isFile());
        try (FileInputStream in = new FileInputStream(f)) {
            dict = WordDictionary.load(in);
        }
        assertTrue("Dictionary should have >= 25000 words, got " + dict.size(), dict.size() >= 25000);
        assertTrue(dict.contains("the"));
        assertTrue(dict.contains("replacements"));
    }

    @Test public void realWorldTyposCorrectOnSpace() {
        var engine = new AutocorrectEngine(KeyboardLanguage.EN, dict, PersonalAutocorrectStore.inMemory(), true);
        String[][] cases = {
            {"reolacements", "replacements"},
            {"helllo", "hello"},
            {"tpying", "typing"},
            {"keboard", "keyboard"},
            {"wroking", "working"},
            {"mesage", "message"},
            {"sometihng", "something"},
            {"tge", "the"},
            {"abd", "and"},
            {"speling", "spelling"},
            {"autocorect", "autocorrect"},
            {"Helllo", "Hello"},
            {"Reolacements", "Replacements"}
        };
        for (String[] tc : cases) {
            var c = engine.correctOnSpace(tc[0]);
            assertNotNull("Expected correction for " + tc[0], c);
            assertEquals("Wrong correction for " + tc[0], tc[1], c.caseMatched());
        }
    }

    @Test public void validWordsAndShorthandNeverCorrupted() {
        var engine = new AutocorrectEngine(KeyboardLanguage.EN, dict, PersonalAutocorrectStore.inMemory(), true);
        for (String w : List.of("hello", "typing", "keyboard", "working", "message", "replacements",
                "ill", "well", "were", "hell", "lets", "shed", "wed", "terminal", "backspace",
                "thx", "pls", "tmrw", "tbh", "idk", "imo", "btw", "nvm", "rn", "ngl", "pr", "ci", "ssh", "cli", "hex", "rune")) {
            assertNull("Must not alter valid/shorthand word: " + w, engine.correctOnSpace(w));
        }
    }

    @Test public void suggestOnlyContractionsNeverAutoReplaceOnSpace() {
        var engine = new AutocorrectEngine(KeyboardLanguage.EN, dict, PersonalAutocorrectStore.inMemory(), true);
        assertNull(engine.correctOnSpace("well"));
        assertFalse(engine.willAutoReplace("well", List.of()));
        List<String> sugs = engine.suggestions("well", List.of());
        assertTrue("Expected we'll in suggestions for well, got " + sugs, sugs.contains("we'll"));

        assertNull(engine.correctOnSpace("Were"));
        assertTrue(engine.suggestions("Were", List.of()).contains("We're"));
        assertNull(engine.correctOnSpace("lets"));
        assertTrue(engine.suggestions("lets", List.of()).contains("let's"));
    }

    @Test public void tokenBoundaryGuardsProtectHandlesPathsAndIdentifiers() {
        var engine = new AutocorrectEngine(KeyboardLanguage.EN, dict, PersonalAutocorrectStore.inMemory(), true);
        for (String ctx : List.of("@teh", "/teh", "foo.teh", "#teh", "foo_teh", "foo-teh", "http:teh", "123teh")) {
            assertEquals("Expected empty token for " + ctx, "", AutocorrectEngine.currentToken(ctx));
            assertNull("Must not autocorrect " + ctx, engine.correctOnSpace(ctx));
        }
        assertEquals("teh", AutocorrectEngine.currentToken("hello teh"));
        assertEquals("the", engine.correctOnSpace("hello teh").caseMatched());
    }

    @Test public void twoLetterNeighborFixesAndMissedSpaceSplits() {
        var engine = new AutocorrectEngine(KeyboardLanguage.EN, dict, PersonalAutocorrectStore.inMemory(), true);
        var tk = engine.correctOnSpace("tk");
        assertNotNull(tk);
        assertEquals("to", tk.caseMatched());

        var fo = engine.correctOnSpace("fo");
        assertNotNull(fo);
        assertEquals("of", fo.caseMatched());

        var upto = engine.correctOnSpace("upto");
        assertNotNull(upto);
        assertEquals("up to", upto.caseMatched());

        var nevermind = engine.correctOnSpace("nevermind");
        assertNotNull(nevermind);
        assertEquals("never mind", nevermind.caseMatched());

        var alot = engine.correctOnSpace("alot");
        assertNotNull(alot);
        assertEquals("a lot", alot.caseMatched());
    }

    @Test public void userDictionaryShortcutsAndTermsWork() {
        var userDict = SystemUserDictionary.Snapshot.of(
                Map.of("omw", "On my way!"),
                List.of("Lindquist")
        );
        var engine = new AutocorrectEngine(KeyboardLanguage.EN, dict, PersonalAutocorrectStore.inMemory(), true, userDict);
        var omw = engine.correctOnSpace("omw");
        assertNotNull(omw);
        assertEquals("On my way!", omw.caseMatched());

        // User terms are never auto-replaced and appear as completions
        assertNull(engine.correctOnSpace("Lindquist"));
        assertTrue(engine.suggestions("Lind", List.of()).contains("Lindquist"));
    }

    @Test public void learningRequiresTwoConsistentPicksAndIgnoresPrefixes() {
        var store = PersonalAutocorrectStore.inMemory();
        var engine = new AutocorrectEngine(KeyboardLanguage.EN, dict, store, true);

        // Prefix completion ("repla" -> "replay") is NEVER learned as a typo fix
        engine.onSuggestionPicked("repla", "replay");
        engine.onSuggestionPicked("repla", "replay");
        assertNull(store.lookupLearned("repla"));

        // Non-prefix typo ("kuberxyz" -> "Kubernetes") requires 2 consistent picks
        engine.onSuggestionPicked("kuberxyz", "Kubernetes");
        assertNull("1 pick should not auto-replace yet", store.lookupLearned("kuberxyz"));
        engine.onSuggestionPicked("kuberxyz", "Kubernetes");
        assertEquals("Kubernetes", store.lookupLearned("kuberxyz"));

        var next = new AutocorrectEngine(KeyboardLanguage.EN, dict, store, true);
        var learned = next.correctOnSpace("kuberxyz");
        assertNotNull(learned);
        assertEquals("Kubernetes", learned.caseMatched());

        // Sentence-start pick ("Qzplk" -> "Apple") normalizes to lowercase "apple" for mid-sentence use
        engine.onSuggestionPicked("Qzplk", "Apple");
        engine.onSuggestionPicked("Qzplk", "Apple");
        var mid = new AutocorrectEngine(KeyboardLanguage.EN, dict, store, true);
        assertEquals("apple", mid.correctOnSpace("qzplk").caseMatched());
        assertEquals("Apple", mid.correctOnSpace("Qzplk").caseMatched());

        // User explicitly taps verbatim Slot 0 ("tpying") -> never auto-corrects "tpying" again
        next.onSuggestionPicked("tpying", "tpying");
        var afterReject = new AutocorrectEngine(KeyboardLanguage.EN, dict, store, true);
        assertNull(afterReject.correctOnSpace("tpying"));
    }

    @Test public void incognitoDisablesPersonalLearning() {
        var store = PersonalAutocorrectStore.inMemory();
        var incognito = new AutocorrectEngine(KeyboardLanguage.EN, dict, store, false);
        incognito.onSuggestionPicked("kuberxyz", "Kubernetes");
        incognito.onSuggestionPicked("kuberxyz", "Kubernetes");
        assertNull(store.lookupLearned("kuberxyz"));

        store.recordAccepted("kuberxyz", "Kubernetes");
        store.recordAccepted("kuberxyz", "Kubernetes");
        assertNull(incognito.correctOnSpace("kuberxyz"));
    }

    @Test public void spellCheckerRecommendationCommitsOnSpace() {
        var engine = new AutocorrectEngine(KeyboardLanguage.EN, dict, PersonalAutocorrectStore.inMemory(), true);
        var c = engine.correctOnSpace("definetely", List.of("definitely"));
        assertNotNull(c);
        assertEquals("definitely", c.caseMatched());
    }

    @Test public void spatialAutocorrectDecodesMultiKeyFatThumbSlips() {
        var spatial = new AutocorrectEngine(
                KeyboardLanguage.EN, dict, PersonalAutocorrectStore.inMemory(), true,
                SystemUserDictionary.Snapshot.EMPTY, true);
        String[][] cases = {
            {"tbks", "this"},
            {"wjaf", "what"},
            {"wpyld", "would"},
            {"rbeir", "their"},
            {"kblw", "know"},
            {"thiks", "this"},
            {"yku", "you"},
            {"feom", "from"},
            {"thst", "that"},
            {"tbe", "the"},
            {"gokd", "good"},
            {"coyld", "could"},
        };
        for (String[] c : cases) {
            var fix = spatial.correctOnSpace(c[0]);
            assertNotNull("Expected spatial correction for " + c[0], fix);
            assertEquals("For " + c[0], c[1], fix.caseMatched());
        }
        // Distant non-adjacent typos must NOT fire in spatial mode.
        assertNull("Distant non-adjacent typo should not fire in spatial mode", spatial.correctOnSpace("qzxz"));
        // Local Gemma reranker helper only accepts choices from the spatial candidate list.
        assertEquals("tabs", LocalSpatialReranker.matchCandidate("  tabs. ", List.of("this", "tabs")));
        assertNull(LocalSpatialReranker.matchCandidate("banana", List.of("this", "tabs")));
    }
}
