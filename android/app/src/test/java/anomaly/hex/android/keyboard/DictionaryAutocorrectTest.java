package anomaly.hex.android.keyboard;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;
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

    @Test public void validWordsNeverCorrupted() {
        var engine = new AutocorrectEngine(KeyboardLanguage.EN, dict, PersonalAutocorrectStore.inMemory(), true);
        for (String w : List.of("hello", "typing", "keyboard", "working", "message", "replacements",
                "ill", "well", "were", "hell", "lets", "shed", "wed", "terminal", "backspace")) {
            assertNull("Must not alter valid word: " + w, engine.correctOnSpace(w));
        }
    }

    @Test public void learnsAcceptedReplacementAndRespectsRejection() {
        var store = PersonalAutocorrectStore.inMemory();
        var engine = new AutocorrectEngine(KeyboardLanguage.EN, dict, store, true);

        // User picks "Kubernetes" after typing "kuber"
        engine.onSuggestionPicked("kuber", "Kubernetes");

        // Fresh engine instance (simulating new editor / restart) auto-corrects "kuber" on space!
        var next = new AutocorrectEngine(KeyboardLanguage.EN, dict, store, true);
        var learned = next.correctOnSpace("kuber");
        assertNotNull(learned);
        assertEquals("Kubernetes", learned.caseMatched());

        // User explicitly taps verbatim Slot 0 ("tpying") -> never auto-corrects "tpying" again
        next.onSuggestionPicked("tpying", "tpying");
        var afterReject = new AutocorrectEngine(KeyboardLanguage.EN, dict, store, true);
        assertNull(afterReject.correctOnSpace("tpying"));
    }

    @Test public void incognitoDisablesPersonalLearning() {
        var store = PersonalAutocorrectStore.inMemory();
        var incognito = new AutocorrectEngine(KeyboardLanguage.EN, dict, store, false);
        incognito.onSuggestionPicked("kuberxyz", "Kubernetes");
        assertNull(store.lookupLearned("kuberxyz"));

        // Even if store already has a pair, incognito does not read it
        store.recordAccepted("kuberxyz", "Kubernetes");
        assertNull(incognito.correctOnSpace("kuberxyz"));
    }

    @Test public void spellCheckerRecommendationCommitsOnSpace() {
        var engine = new AutocorrectEngine(KeyboardLanguage.EN, dict, PersonalAutocorrectStore.inMemory(), true);
        var c = engine.correctOnSpace("definetely", List.of("definitely"));
        assertNotNull(c);
        assertEquals("definitely", c.caseMatched());
    }
}
