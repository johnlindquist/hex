package anomaly.hex.android.keyboard;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.util.List;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for:
 * - c/v/b/n/m spacebar-miss splitting ("wantbto" -> "want to", "inmthe" -> "in the")
 * - TailCorrectionEngine multi-word parsing, full-phrase fixes, real-word neighbors, and surgical word replacement
 * - SpacebarFixMode defaults (FLICK_SCRUBBER)
 * - TypingTelemetryLog NDJSON recording, coordinate deltas, stats, and clear
 */
public class SpacebarTailFixAndTelemetryTest {
    private static WordDictionary dict;

    @BeforeClass public static void loadRealAssetDictionary() throws Exception {
        File f = new File("src/main/assets/keyboard/en_freq.txt");
        if (!f.exists()) f = new File("app/src/main/assets/keyboard/en_freq.txt");
        assertTrue(f.isFile());
        try (FileInputStream in = new FileInputStream(f)) {
            dict = WordDictionary.load(in);
        }
    }

    @Test public void splitsBottomRowSpacebarMissesAutomatically() {
        var engine = new AutocorrectEngine(
                KeyboardLanguage.EN, dict, PersonalAutocorrectStore.inMemory(), true,
                SystemUserDictionary.Snapshot.EMPTY, true);
        String[][] cases = {
            {"wantbto", "want to"},
            {"inmthe", "in the"},
            {"gotvthere", "got there"},
            {"neednto", "need to"},
            {"goingbto", "going to"},
            {"onbthe", "on the"},
            {"fornthe", "for the"},
            {"withbme", "with me"},
            {"havevto", "have to"},
        };
        for (String[] c : cases) {
            var fix = engine.correctOnSpace(c[0]);
            assertNotNull("Expected spacebar-miss split for: " + c[0], fix);
            assertEquals("For " + c[0], c[1], fix.caseMatched());
        }
        // Real words containing b/v/n/m must NEVER be split
        assertNull(engine.correctOnSpace("combine"));
        assertNull(engine.correctOnSpace("environment"));
        assertNull(engine.correctOnSpace("someone"));
    }

    @Test public void tailEngineFixesMultipleWordsAndPreservesPunctuation() {
        var analysis = TailCorrectionEngine.analyze(
                "I wantbto check tbks envieonment ",
                dict, SpatialKeyboardDecoder.DecodeContext.EMPTY);
        assertFalse(analysis.isEmpty());
        assertEquals(5, analysis.words().size());
        assertFalse(analysis.phraseFixes().isEmpty());
        assertEquals("I want to check this environment ", analysis.phraseFixes().get(0).replacement());
        assertEquals("I want to check this environment", analysis.phraseFixes().get(0).label());

        // Surgical single-word swap replaces only the chosen word, leaving surrounding text intact
        String swapped = TailCorrectionEngine.replaceWord(analysis, 1, "want to");
        assertEquals("I want to check tbks envieonment ", swapped);
    }

    @Test public void tailEngineOffersRealWordSpatialNeighborsForValidWords() {
        // "thus" -> "this" (u is right next to i on QWERTY)
        var analysis = TailCorrectionEngine.analyze(
                "I really like thus",
                dict, SpatialKeyboardDecoder.DecodeContext.EMPTY);
        assertEquals(4, analysis.words().size());
        TailCorrectionEngine.WordSlot last = analysis.words().get(3);
        assertEquals("thus", last.original());
        assertFalse(last.suspicious());
        assertTrue("Expected real-word neighbor 'this' for 'thus', got " + last.candidates(),
                last.candidates().contains("this"));
        // Phrase fixes should also offer replacing 'thus' with 'this'
        boolean hasThisPhrase = analysis.phraseFixes().stream()
                .anyMatch(p -> p.label().equals("I really like this"));
        assertTrue("Expected phrase fix 'I really like this'", hasThisPhrase);

        // "food" -> "good" (f is right next to g on QWERTY)
        var foodAnalysis = TailCorrectionEngine.analyze(
                "that was food",
                dict, SpatialKeyboardDecoder.DecodeContext.EMPTY);
        assertTrue(foodAnalysis.words().get(2).candidates().contains("good"));
    }

    @Test public void sentenceAnalysisScansFullCurrentSentenceAndBuildsMultipleOptions() {
        String text = "First sentence stays untouched. I really wantbto check teh configuration befroe we shup";
        var analysis = TailCorrectionEngine.analyzeSentence(
                text, dict, SpatialKeyboardDecoder.DecodeContext.EMPTY);
        assertFalse(analysis.isEmpty());
        assertEquals("I really wantbto check teh configuration befroe we shup", analysis.originalTail());
        assertFalse("Expected at least 1 full corrected sentence option", analysis.phraseFixes().isEmpty());
        String top = analysis.phraseFixes().get(0).label();
        assertTrue("Top sentence should fix 'wantbto' -> 'want to', got: " + top, top.contains("want to"));
        assertTrue("Top sentence should fix 'teh' -> 'the', got: " + top, top.contains("the"));
        assertTrue("Top sentence should fix 'befroe' -> 'before', got: " + top, top.contains("before"));
        assertTrue("Should offer multiple corrected sentence options, got " + analysis.phraseFixes().size(),
                analysis.phraseFixes().size() >= 2);
    }

    @Test public void spacebarFixModeDefaultsToFlickScrubber() {
        assertEquals(KeyboardSettings.SpacebarFixMode.FLICK_SCRUBBER,
                KeyboardSettings.SpacebarFixMode.fromId(null));
        assertEquals(KeyboardSettings.SpacebarFixMode.FLICK_SCRUBBER,
                KeyboardSettings.SpacebarFixMode.fromId("unknown"));
        assertEquals(KeyboardSettings.SpacebarFixMode.LONG_PRESS_PANEL,
                KeyboardSettings.SpacebarFixMode.fromId("long_press_panel"));
        assertEquals(KeyboardSettings.SpacebarFixMode.HOLD_SLIDE_PICKER,
                KeyboardSettings.SpacebarFixMode.fromId("hold_slide_picker"));
    }

    @Test public void telemetryLogWritesValidNdjsonAndComputesStats() throws Exception {
        File tmp = Files.createTempFile("hex-telemetry-test", ".ndjson").toFile();
        tmp.delete();
        try {
            TypingTelemetryLog log = TypingTelemetryLog.forFile(tmp);
            log.logSession("com.example.chat", true, true, "flick_scrubber");
            log.logTap("b", "CHAR", 214.2f, 152.8f, 206.0f, 148.0f, "LETTERS", 412.0f);
            log.logSpace("tbks", "this", "spacebar_autocorrect");
            log.logBackspace(null, "this", "tbks");
            log.logTailFix("flick_scrubber", "wantbto", "want to");
            log.flushSync();

            List<String> lines = Files.readAllLines(tmp.toPath());
            assertEquals(5, lines.size());

            JSONObject tap = new JSONObject(lines.get(1));
            assertEquals("tap", tap.getString("ev"));
            assertEquals("b", tap.getString("key"));
            assertEquals(8.2, tap.getDouble("dx"), 0.05);
            assertEquals(4.8, tap.getDouble("dy"), 0.05);

            TypingTelemetryLog.Stats stats = log.stats();
            assertEquals(5, stats.events());
            assertEquals(2, stats.corrections());
            assertEquals(1, stats.undos());
            assertTrue(TypingTelemetryLog.formatStats(stats).contains("5 events"));

            log.clear();
            assertFalse(tmp.exists());
            assertEquals(0, log.stats().events());
        } finally {
            tmp.delete();
        }
    }
}
