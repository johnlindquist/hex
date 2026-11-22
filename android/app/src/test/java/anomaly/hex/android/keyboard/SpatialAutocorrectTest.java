package anomaly.hex.android.keyboard;

import static org.junit.Assert.*;

import android.content.SharedPreferences;
import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Comprehensive test suite for Labs › Thumb-position autocorrect:
 * - Short, medium, and long words (up to 16+ chars) with 1–4 fat-thumb slips
 * - Grazed extra keys, double-taps, skipped keys, and adjacent transpositions
 * - On-screen context dictionary extraction, priority, casing preservation, and isolation
 * - Continuous learning from all typed words (vocabulary, frequency boost, bigrams, thumb-slip habits)
 * - Persistence round-trip and privacy/incognito boundaries
 */
public class SpatialAutocorrectTest {
    private static WordDictionary dict;

    @BeforeClass public static void loadRealAssetDictionary() throws Exception {
        File f = new File("src/main/assets/keyboard/en_freq.txt");
        if (!f.exists()) f = new File("app/src/main/assets/keyboard/en_freq.txt");
        assertTrue("Expected en_freq.txt asset at " + f.getAbsolutePath(), f.isFile());
        try (FileInputStream in = new FileInputStream(f)) {
            dict = WordDictionary.load(in);
        }
        assertTrue(dict.size() >= 25000);
    }

    private AutocorrectEngine newSpatial(PersonalAutocorrectStore store, boolean allowLearning) {
        return new AutocorrectEngine(
                KeyboardLanguage.EN, dict, store, allowLearning,
                SystemUserDictionary.Snapshot.EMPTY, true);
    }

    @Test public void decodesLongWordsWithSingleAndMultiKeyFatThumbSlips() {
        var engine = newSpatial(PersonalAutocorrectStore.inMemory(), true);
        String[][] cases = {
            // Medium words (5–7 letters) with 1–2 adjacent slips
            {"wpyld", "would"},
            {"coyld", "could"},
            {"sbould", "should"},
            {"abkut", "about"},
            {"rbeir", "their"},
            {"tbere", "there"},
            {"wherd", "where"},
            {"peiple", "people"},
            {"messsge", "message"},
            {"kdyboard", "keyboard"},
            {"wkrking", "working"},
            {"sometbing", "something"},
            {"bexause", "because"},
            {"tbroigh", "through"},
            {"betwewn", "between"},
            {"anorher", "another"},
            // Long words (8–15 letters) with 1–3 adjacent fat-thumb slips
            {"envieonment", "environment"},
            {"impoetant", "important"},
            {"comouters", "computers"},
            {"undeesranding", "understanding"},
            {"applucstion", "application"},
            {"developmemt", "development"},
            {"configueation", "configuration"},
            {"architwcture", "architecture"},
            {"experimemtal", "experimental"},
            {"commynication", "communication"},
            {"infkemation", "information"},
            {"perfomrance", "performance"},
            {"responsobility", "responsibility"},
            {"implwmentstion", "implementation"},
            {"recognitiom", "recognition"},
            {"suggesrions", "suggestions"},
            {"interrsting", "interesting"},
            {"diffeeence", "difference"},
            {"opportynity", "opportunity"},
            {"technolpgy", "technology"},
            {"progrsmming", "programming"},
            {"documemtstion", "documentation"},
            {"authemticstion", "authentication"},
            {"inftasttucture", "infrastructure"},
            {"characteeistics", "characteristics"},
            {"recommemdatikn", "recommendation"},
            {"internstional", "international"},
            {"organozstion", "organization"},
            {"unfortynately", "unfortunately"},
            {"conversstion", "conversation"},
        };
        for (String[] c : cases) {
            var fix = engine.correctOnSpace(c[0]);
            assertNotNull("Expected spatial correction for long word: " + c[0], fix);
            assertEquals("For " + c[0], c[1], fix.caseMatched());
        }
    }

    @Test public void decodesGrazedExtraKeysAndDoubleTapsOnLongWords() {
        var engine = newSpatial(PersonalAutocorrectStore.inMemory(), true);
        String[][] cases = {
            {"thiks", "this"},
            {"envirionment", "environment"},
            {"appplication", "application"},
            {"undersstanding", "understanding"},
            {"communicatiion", "communication"},
            {"keyyboard", "keyboard"},
            {"perfformance", "performance"},
            {"configguration", "configuration"},
            {"devellopment", "development"},
            {"imporrtant", "important"},
            {"informmation", "information"},
        };
        for (String[] c : cases) {
            var fix = engine.correctOnSpace(c[0]);
            assertNotNull("Expected grazed-key fix for: " + c[0], fix);
            assertEquals("For " + c[0], c[1], fix.caseMatched());
        }
    }

    @Test public void decodesSkippedKeysAndTranspositionsOnLongWords() {
        var engine = newSpatial(PersonalAutocorrectStore.inMemory(), true);
        String[][] cases = {
            // Skipped keys
            {"envronment", "environment"},
            {"imprtant", "important"},
            {"develpment", "development"},
            {"configration", "configuration"},
            {"undrstanding", "understanding"},
            {"applcation", "application"},
            {"perfrmance", "performance"},
            {"archtecture", "architecture"},
            {"communcation", "communication"},
            // Transpositions
            {"tehcnical", "technical"},
            {"porblem", "problem"},
            {"availbale", "available"},
            {"differnet", "different"},
            {"undertsanding", "understanding"},
            {"devleopment", "development"},
            {"enviornment", "environment"},
            {"infromation", "information"},
        };
        for (String[] c : cases) {
            var fix = engine.correctOnSpace(c[0]);
            assertNotNull("Expected skipped/transposed fix for: " + c[0], fix);
            assertEquals("For " + c[0], c[1], fix.caseMatched());
        }
    }

    @Test public void pullsOnScreenWordsIntoCorrectiveDictionaryWithCasing() {
        var engine = newSpatial(PersonalAutocorrectStore.inMemory(), true);
        String screenText = "Hey John Lindquist, please check the PostgreSQL migration in Supabase "
                + "before deploying Antigravity and Kubernetes clusters for TypeScript services. "
                + "Also review https://example.com/foo_bar @handle #tag 12345 and Anthropic SDK.";
        ScreenContextVocabulary vocab = ScreenContextVocabulary.fromText(screenText);

        // Clean extraction checks: proper nouns kept with casing, URLs/handles/numbers skipped
        assertTrue(vocab.contains("lindquist"));
        assertEquals("Lindquist", vocab.canonical("lindquist"));
        assertEquals("PostgreSQL", vocab.canonical("postgresql"));
        assertEquals("Supabase", vocab.canonical("supabase"));
        assertEquals("Antigravity", vocab.canonical("antigravity"));
        assertEquals("TypeScript", vocab.canonical("typescript"));
        assertFalse(vocab.contains("handle"));
        assertFalse(vocab.contains("tag"));
        assertFalse(vocab.contains("foo_bar"));
        assertFalse(vocab.contains("https"));

        engine.setScreenWords(vocab);

        // Typing on-screen words cleanly must NEVER trigger false autocorrect
        assertNull(engine.correctOnSpace("Lindquist"));
        assertNull(engine.correctOnSpace("Supabase"));
        assertNull(engine.correctOnSpace("PostgreSQL"));
        assertNull(engine.correctOnSpace("Antigravity"));

        // Fat-thumbing on-screen words decodes to exact canonical screen casing!
        String[][] screenTypos = {
            {"linfquist", "Lindquist"},
            {"linfqyist", "Lindquist"},
            {"podtgresql", "PostgreSQL"},
            {"postgtesql", "PostgreSQL"},
            {"supabade", "Supabase"},
            {"syoabase", "Supabase"},
            {"antigrsbity", "Antigravity"},
            {"antbropic", "Anthropic"},
            {"kubwrnetes", "Kubernetes"},
            {"tyoescript", "TypeScript"},
            {"supabasse", "Supabase"}, // grazed double key on screen word
            {"supbase", "Supabase"},   // skipped key on screen word
        };
        for (String[] c : screenTypos) {
            var fix = engine.correctOnSpace(c[0]);
            assertNotNull("Expected screen-word fix for: " + c[0], fix);
            assertEquals("For " + c[0], c[1], fix.caseMatched());
        }

        // Clearing screen words (e.g. switching apps) removes ephemeral screen words the user never typed
        var freshStore = PersonalAutocorrectStore.inMemory();
        var ephemeralEngine = newSpatial(freshStore, true);
        ephemeralEngine.setScreenWords(ScreenContextVocabulary.fromText("Check Zebrathon status"));
        assertEquals("Zebrathon", ephemeralEngine.correctOnSpace("zebrathin").caseMatched());
        ephemeralEngine.setScreenWords(ScreenContextVocabulary.EMPTY);
        assertNull(ephemeralEngine.correctOnSpace("zebrathin"));

        // Gemma prompt includes salient screen words
        String prompt = LocalSpatialReranker.buildPrompt(
                "check the migration in", "supabade", List.of("Supabase", "subspace"), vocab.promptHints(10));
        assertTrue(prompt.contains("Words on screen:"));
        assertTrue(prompt.contains("Supabase"));
        assertTrue(prompt.contains("PostgreSQL"));
    }

    @Test public void learnsFromAllTypedWordsAndCorrectsFatThumbsLater() {
        var store = PersonalAutocorrectStore.inMemory();
        var engine = newSpatial(store, true);

        // Before learning: "mknorepo" and "wkrktree" are unknown
        assertNull(engine.correctOnSpace("mknorepo"));
        assertNull(engine.correctOnSpace("wkrktree"));

        // User types domain words naturally in sentences (Space, punctuation, Return)
        engine.correctOnSpace("our monorepo");
        engine.correctOnSpace("this monorepo");
        engine.recordCommittedWord("create worktree");
        engine.recordCommittedWord("switch worktree");
        engine.correctOnSpace("using FastAPI");
        engine.correctOnSpace("deploy FastAPI");
        engine.correctOnSpace("open ghostty");
        engine.correctOnSpace("launch ghostty");

        assertTrue(store.isPersonalWord("monorepo"));
        assertTrue(store.isPersonalWord("worktree"));
        assertTrue(store.isPersonalWord("fastapi"));
        assertEquals("FastAPI", store.personalCanonical("fastapi"));
        assertTrue(store.isPersonalWord("ghostty"));

        // Typing learned words cleanly is never falsely overwritten
        assertNull(engine.correctOnSpace("monorepo"));
        assertNull(engine.correctOnSpace("worktree"));
        assertNull(engine.correctOnSpace("FastAPI"));
        assertNull(engine.correctOnSpace("ghostty"));

        // Later fat-thumb slips of those learned words now auto-correct accurately!
        assertEquals("monorepo", engine.correctOnSpace("mknorepo").caseMatched());
        assertEquals("monorepo", engine.correctOnSpace("monoreoo").caseMatched());
        assertEquals("worktree", engine.correctOnSpace("wkrktree").caseMatched());
        assertEquals("FastAPI", engine.correctOnSpace("fsstapi").caseMatched());
        assertEquals("ghostty", engine.correctOnSpace("ghkstty").caseMatched());
    }

    @Test public void improvesOverTimeWithPersonalFrequencyAndBigrams() {
        var store = PersonalAutocorrectStore.inMemory();
        var engine = newSpatial(store, true);

        // "gokd" is a genuine fat-thumb neighbor of both "good" (k->o) and "gold" (k->l).
        // Before personal usage, "good" wins on general English frequency.
        var beforeUsage = engine.correctOnSpace("solid gokd");
        assertNotNull(beforeUsage);
        assertEquals("good", beforeUsage.caseMatched());

        // User repeatedly types "solid gold" in daily usage
        for (int i = 0; i < 6; i++) {
            engine.correctOnSpace("solid gold");
        }
        assertTrue(store.wordCount("gold") >= 6);
        assertTrue(store.bigramCount("solid", "gold") >= 6);

        // Now "solid gokd" adapts to "gold" because of personal bigram + frequency learning!
        var afterSolid = engine.correctOnSpace("solid gokd");
        assertNotNull(afterSolid);
        assertEquals("gold", afterSolid.caseMatched());

        // Meanwhile in a different bigram context ("very good"), "very gokd" resolves to "good"!
        for (int i = 0; i < 6; i++) {
            engine.correctOnSpace("very good");
        }
        var afterVery = engine.correctOnSpace("very gokd");
        assertNotNull(afterVery);
        assertEquals("good", afterVery.caseMatched());
    }

    @Test public void adaptsToPersonalThumbSlipHabitsOverTime() {
        var store = PersonalAutocorrectStore.inMemory();
        int baselineCost = SpatialKeyboardDecoder.spatialSameLengthCost("tbks", "this", 2, store);
        assertTrue(baselineCost > 0);

        // Simulate user accepting fat-thumb corrections with b->h and k->i slips over time
        for (int i = 0; i < 6; i++) {
            store.recordWordSlips("tbks", "this");
        }
        assertEquals(6, store.slipCount('b', 'h'));
        assertEquals(6, store.slipCount('k', 'i'));

        int adaptedCost = SpatialKeyboardDecoder.spatialSameLengthCost("tbks", "this", 2, store);
        assertTrue("Expected personal thumb-slip cost to decrease after usage (" + adaptedCost + " < " + baselineCost + ")",
                adaptedCost < baselineCost);
    }

    @Test public void persistsLearnedVocabularyBigramsAndSlipsAcrossRestarts() {
        InMemoryPrefs prefs = new InMemoryPrefs();
        var store1 = new PersonalAutocorrectStore(prefs);
        var engine1 = newSpatial(store1, true);

        engine1.correctOnSpace("deploy Supabase");
        engine1.correctOnSpace("deploy Supabase");
        engine1.correctOnSpace("deploy Supabase");
        store1.recordWordSlips("tbks", "this");
        store1.recordWordSlips("tbks", "this");

        // Reload fresh store from same SharedPreferences (simulates app/keyboard restart)
        var store2 = new PersonalAutocorrectStore(prefs);
        assertEquals(3, store2.wordCount("supabase"));
        assertEquals("Supabase", store2.personalCanonical("supabase"));
        assertEquals(3, store2.bigramCount("deploy", "supabase"));
        assertEquals(2, store2.slipCount('b', 'h'));

        var engine2 = newSpatial(store2, true);
        assertEquals("Supabase", engine2.correctOnSpace("deploy supabade").caseMatched());
    }

    @Test public void incognitoRespectsPrivacyAndNeverLearnsOrReadsPersonalData() {
        var store = PersonalAutocorrectStore.inMemory();
        var incognito = newSpatial(store, false);

        incognito.correctOnSpace("secretword");
        incognito.correctOnSpace("secretword");
        assertEquals(0, store.wordCount("secretword"));

        // Even if store already has a personal word, incognito ignores it
        store.recordTypedWord("monorepo");
        store.recordTypedWord("monorepo");
        assertNull(incognito.correctOnSpace("mknorepo"));
    }

    @Test public void guardsAgainstFalsePositivesAndDistantGibberish() {
        var engine = newSpatial(PersonalAutocorrectStore.inMemory(), true);

        // Valid English words of all lengths must never be overwritten
        String[] validWords = {
            "this", "that", "with", "from", "would", "could", "should", "environment",
            "implementation", "configuration", "understanding", "communication", "architecture"
        };
        for (String w : validWords) {
            assertNull("Valid word must not be replaced: " + w, engine.correctOnSpace(w));
        }

        // Distant non-adjacent keyboard gibberish must not auto-replace in spatial mode
        String[] gibberish = {"qzxz", "zxqwerty", "mnbvcxz", "pqlamz", "qzplkm"};
        for (String g : gibberish) {
            assertNull("Distant gibberish must not fire in spatial mode: " + g, engine.correctOnSpace(g));
        }

        // Handles, URLs, hashtags, snake_case, and numbers stay untouched
        assertNull(engine.correctOnSpace("@tbks"));
        assertNull(engine.correctOnSpace("#tbks"));
        assertNull(engine.correctOnSpace("foo_tbks"));
        assertNull(engine.correctOnSpace("https://tbks"));
        assertNull(engine.correctOnSpace("v2tbks"));
    }

    @Test public void decodingManyLongWordsStaysFast() {
        var engine = newSpatial(PersonalAutocorrectStore.inMemory(), true);
        engine.setScreenWords(ScreenContextVocabulary.fromText(
                "Lindquist PostgreSQL Supabase Antigravity Kubernetes TypeScript Anthropic"));
        String[] batch = {
            "envieonment", "undeesranding", "configueation", "implwmentstion", "commynication",
            "authemticstion", "inftasttucture", "linfquist", "podtgresql", "supabade"
        };
        long startNs = System.nanoTime();
        for (int rep = 0; rep < 5; rep++) {
            for (String w : batch) {
                assertNotNull(engine.correctOnSpace(w));
            }
        }
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        assertTrue("Expected 50 long-word decodes in <1000ms, took " + elapsedMs + "ms", elapsedMs < 1000);
    }

    /** Minimal in-memory SharedPreferences for unit-testing persistence round-trips. */
    private static final class InMemoryPrefs implements SharedPreferences {
        private final Map<String, Object> data = new HashMap<>();
        @Override public Map<String, ?> getAll() { return new HashMap<>(data); }
        @Override public String getString(String k, String d) { return data.containsKey(k) ? (String) data.get(k) : d; }
        @Override public Set<String> getStringSet(String k, Set<String> d) { return d; }
        @Override public int getInt(String k, int d) { return d; }
        @Override public long getLong(String k, long d) { return d; }
        @Override public float getFloat(String k, float d) { return d; }
        @Override public boolean getBoolean(String k, boolean d) { return d; }
        @Override public boolean contains(String k) { return data.containsKey(k); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public Editor edit() {
            return new Editor() {
                private final Map<String, Object> staged = new HashMap<>();
                @Override public Editor putString(String k, String v) { staged.put(k, v); return this; }
                @Override public Editor putStringSet(String k, Set<String> v) { return this; }
                @Override public Editor putInt(String k, int v) { return this; }
                @Override public Editor putLong(String k, long v) { return this; }
                @Override public Editor putFloat(String k, float v) { return this; }
                @Override public Editor putBoolean(String k, boolean v) { return this; }
                @Override public Editor remove(String k) { staged.put(k, null); return this; }
                @Override public Editor clear() { data.clear(); return this; }
                @Override public boolean commit() { apply(); return true; }
                @Override public void apply() {
                    for (var e : staged.entrySet()) {
                        if (e.getValue() == null) data.remove(e.getKey());
                        else data.put(e.getKey(), e.getValue());
                    }
                }
            };
        }
    }
}
