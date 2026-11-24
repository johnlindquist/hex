package anomaly.hex.android.keyboard;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;

public final class AdaptiveLearningTest {

    @Test
    public void thumbOffsetAdaptationShiftsHitTestTowardUserDrift() {
        KeyboardLayout layout = new KeyboardLayout(
                390f, false, KeyboardLayout.Layer.LETTERS, KeyboardLayout.FieldKind.TEXT,
                false, true, KeyboardLanguage.EN);
        PersonalAutocorrectStore store = PersonalAutocorrectStore.inMemory();

        KeyboardLayout.Key h = layout.keyForChar('h');
        KeyboardLayout.Key j = layout.keyForChar('j');
        assertNotNull(h);
        assertNotNull(j);

        float hCenterX = (h.left() + h.right()) * 0.5f;
        float hCenterY = (h.top() + h.bottom()) * 0.5f;
        float hWidth = h.right() - h.left();
        float hHeight = h.bottom() - h.top();

        // A tap 2dp inside j's left edge geometrically hits 'j'.
        float boundaryTapX = j.hitLeft() + 2.0f;
        float boundaryTapY = hCenterY;
        assertEquals("j", layout.hitTest(boundaryTapX, boundaryTapY).label());
        assertEquals("j", layout.hitTestAdaptive(boundaryTapX, boundaryTapY, store).label());

        // User consistently aims for 'h' with a rightward thumb bias (corrected from 'j' -> 'h').
        for (int i = 0; i < 4; i++) {
            store.recordThumbSample('h', boundaryTapX - hCenterX, 0f, hWidth, hHeight, true);
        }
        float[] off = store.thumbOffset('h');
        assertNotNull(off);
        assertTrue("Expected positive x drift for h, got " + off[0], off[0] > 4f);

        // Now tapping the exact same boundary spot resolves directly to 'h'!
        assertEquals("h", layout.hitTestAdaptive(boundaryTapX, boundaryTapY, store).label());

        // Non-character keys (e.g. Space) always keep exact geometric hit-testing.
        KeyboardLayout.Key space = null;
        for (KeyboardLayout.Key k : layout.keys()) {
            if (k.kind() == KeyboardLayout.KeyKind.SPACE) { space = k; break; }
        }
        assertNotNull(space);
        float sx = (space.left() + space.right()) * 0.5f;
        float sy = (space.top() + space.bottom()) * 0.5f;
        assertEquals(KeyboardLayout.KeyKind.SPACE, layout.hitTestAdaptive(sx, sy, store).kind());
    }

    @Test
    public void retypeTrackerDetectsManualBackspaceAndRetypeSlips() {
        RetypeTracker tracker = new RetypeTracker();

        // 1. User types "thsi", realizes typo before space, backspaces 2 chars, retypes "is" -> "this"
        tracker.recordCharTap('t', 100f, 50f, 100f, 50f, 35f, 47f);
        tracker.recordCharTap('h', 200f, 100f, 200f, 100f, 35f, 47f);
        tracker.recordCharTap('s', 60f, 100f, 60f, 100f, 35f, 47f);
        tracker.recordCharTap('i', 280f, 50f, 280f, 50f, 35f, 47f);

        tracker.onBackspace("hello thsi", false);
        tracker.onBackspace("hello ths", false);
        tracker.recordCharTap('i', 280f, 50f, 280f, 50f, 35f, 47f);
        tracker.recordCharTap('s', 60f, 100f, 60f, 100f, 35f, 47f);

        RetypeTracker.RetypeMatch m1 = tracker.onWordCommitted("this");
        assertNotNull(m1);
        assertEquals("thsi", m1.erased());
        assertEquals("this", m1.retyped());
        assertEquals(4, m1.erasedTaps().size());

        // 2. User types "teh " (with space), backspaces into it, retypes "the"
        tracker.recordCharTap('t', 100f, 50f, 100f, 50f, 35f, 47f);
        tracker.recordCharTap('e', 80f, 50f, 80f, 50f, 35f, 47f);
        tracker.recordCharTap('h', 200f, 100f, 200f, 100f, 35f, 47f);
        assertNull(tracker.onWordCommitted("teh"));
        tracker.onBackspace("teh ", false);
        tracker.onBackspace("teh", false);
        tracker.onBackspace("te", false);
        tracker.recordCharTap('h', 200f, 100f, 200f, 100f, 35f, 47f);
        tracker.recordCharTap('e', 80f, 50f, 80f, 50f, 35f, 47f);
        RetypeTracker.RetypeMatch m2 = tracker.onWordCommitted("the");
        assertNotNull(m2);
        assertEquals("teh", m2.erased());
        assertEquals("the", m2.retyped());

        // 3. Unrelated word change ("cat" -> "dog") must NOT emit a false retype slip
        tracker.onBackspace("my cat", false);
        tracker.onBackspace("my ca", false);
        tracker.onBackspace("my c", false);
        tracker.recordCharTap('d', 100f, 100f, 100f, 100f, 35f, 47f);
        tracker.recordCharTap('o', 300f, 50f, 300f, 50f, 35f, 47f);
        tracker.recordCharTap('g', 160f, 100f, 160f, 100f, 35f, 47f);
        assertNull(tracker.onWordCommitted("dog"));

        // 4. Autocorrect undo resets tracker instead of recording a retype
        tracker.onBackspace("hello world ", true);
        tracker.recordCharTap('w', 50f, 50f, 50f, 50f, 35f, 47f);
        assertNull(tracker.onWordCommitted("word"));
    }

    @Test
    public void typoFixExtractorHandlesSpacebarMissSplitSentences() {
        List<TypoFixExtractor.FixPair> pairs = TypoFixExtractor.extract(
                "Bow do you want to sknthkd?",
                "How do you want to do this?",
                null);
        assertTrue("Expected bow -> how to be extracted despite split word count: " + pairs,
                pairs.stream().anyMatch(p -> p.from().equals("bow") && p.to().equals("how")));
    }

    @Test
    public void personalSlipWeightsLearnFromFirstObservationAndTwoLetterWords() {
        PersonalAutocorrectStore store = PersonalAutocorrectStore.inMemory();
        store.recordWordSlips("sk", "do");
        assertEquals(1, store.slipCount('s', 'd'));
        assertEquals(1, store.slipCount('k', 'o'));

        int baseCost = SpatialKeyboardDecoder.spatialSameLengthCost("tbks", "this", 2, null);
        store.recordWordSlips("tbks", "this");
        int adaptedCost = SpatialKeyboardDecoder.spatialSameLengthCost("tbks", "this", 2, store);
        assertTrue("Personal slip should meaningfully reduce cost after 1st observation: base="
                + baseCost + " adapted=" + adaptedCost, adaptedCost <= baseCost - 6);
    }

    @Test
    public void telemetryLogsRetypeAndRawKeyAdaptation() throws Exception {
        File tmp = Files.createTempFile("hex-telem-retype", ".ndjson").toFile();
        tmp.deleteOnExit();
        TypingTelemetryLog log = TypingTelemetryLog.forFile(tmp);
        log.logTap("h", "CHAR", 218f, 100f, 200f, 100f, "j", "LETTERS", 390f);
        log.logRetype("thsi", "this");
        log.flushSync();
        String content = new String(Files.readAllBytes(tmp.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(content.contains("\"rawKey\":\"j\""));
        assertTrue(content.contains("\"ev\":\"retype\""));
        assertTrue(content.contains("\"from\":\"thsi\""));
        assertTrue(content.contains("\"to\":\"this\""));
    }
}
