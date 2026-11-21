package anomaly.hex.android.keyboard;

import static org.junit.Assert.*;
import static anomaly.hex.android.keyboard.KeyboardLayout.*;
import static anomaly.hex.android.keyboard.KeyboardLanguage.*;
import static anomaly.hex.android.keyboard.ShiftStateMachine.State.OFF;
import static anomaly.hex.android.keyboard.ShiftStateMachine.State.CAPS_LOCK;
import static anomaly.hex.android.keyboard.ShiftStateMachine.State.SHIFTED;

import java.util.List;
import java.util.Locale;
import org.junit.Test;

public class KeyboardEngineTest {
    private static String row(KeyboardLayout layout, int row) {
        StringBuilder text = new StringBuilder();
        for (Key key : layout.keys()) if (key.kind() == KeyKind.CHAR && key.row() == row) text.append(key.text());
        return text.toString();
    }

    @Test public void letterRowsForEveryLocale() {
        for (KeyboardLanguage lang : values()) {
            var layout = new KeyboardLayout(390, false, Layer.LETTERS, FieldKind.TEXT, false, false, lang);
            assertEquals(lang == DE ? "qwertzuiopü" : lang == NB ? "qwertyuiopå" : "qwertyuiop", row(layout, 0));
            assertEquals(lang == DE ? "asdfghjklöä" : lang == NB ? "asdfghjkløæ" : lang == ES ? "asdfghjklñ" : "asdfghjkl", row(layout, 1));
            assertEquals(lang == DE ? "yxcvbnm" : "zxcvbnm", row(layout, 2));
        }
    }

    @Test public void autoLanguageAndExplicitOverride() {
        assertEquals(NB, resolve("auto", Locale.forLanguageTag("nn-NO")));
        assertEquals(PT, resolve("pt_BR", Locale.ENGLISH));
        assertEquals(EN, resolve("auto", Locale.JAPANESE));
    }

    @Test public void punctuationAndFieldExtras() {
        var n = new KeyboardLayout(390, false, Layer.NUMBERS, FieldKind.URL, false, false, NB);
        assertEquals("1234567890", row(n, 0)); assertEquals("-/:;()$&@\"", row(n, 1));
        assertEquals(".,?!'", row(n, 2)); assertEquals("./", row(n, 3));
        var s = new KeyboardLayout(390, true, Layer.SYMBOLS, FieldKind.EMAIL, true, true, FR);
        assertEquals("[]{}#%^*+=", row(s, 0)); assertEquals("_\\|~<>€£¥•", row(s, 1));
        assertEquals(".,?!'", row(s, 2)); assertEquals("@.", row(s, 3));
        assertTrue(s.keys().stream().anyMatch(k -> k.kind() == KeyKind.EMOJI));
        assertTrue(s.keys().stream().anyMatch(k -> k.kind() == KeyKind.GLOBE));
    }

    @Test public void framesTileAcrossOrientationsWidthsAndLanguages() {
        for (boolean wide : List.of(false, true)) for (int width : List.of(320,375,390,640)) for (KeyboardLanguage lang : values()) {
            var layout = new KeyboardLayout(width, wide, Layer.LETTERS, FieldKind.EMAIL, true, true, lang);
            assertEquals(wide ? 176 : 262, layout.totalHeight(), 0);
            assertEquals(wide ? 39 : 44, layout.stripHeight(), 0);
            assertNull(layout.hitTest(0, layout.stripHeight()-1)); assertNull(layout.hitTest(width, 60));
            assertNull(layout.hitTest(0, layout.totalHeight()));
            Key prev = null;
            for (Key k : layout.keys()) {
                assertTrue(k.right() > k.left()); assertEquals(wide ? 27.5 : 45, k.bottom()-k.top(), 0);
                assertSame(k, layout.hitTest(k.hitLeft(), k.hitTop()));
                assertSame(k, layout.hitTest((k.left()+k.right())/2, (k.top()+k.bottom())/2));
                if (prev == null || prev.row() != k.row()) {
                    assertEquals(0, k.hitLeft(), 0);
                    if (prev != null) { assertEquals(width, prev.hitRight(), 0); assertEquals(prev.hitBottom(), k.hitTop(), 0); }
                } else assertEquals(prev.hitRight(), k.hitLeft(), 0);
                prev = k;
            }
            assertEquals(width, prev.hitRight(), 0); assertEquals(layout.totalHeight(), prev.hitBottom(), 0);
        }
    }

    @Test public void shiftDoubleTapBoundaryAndUnlock() {
        for (int delta : List.of(299,300,301)) {
            var m = new ShiftStateMachine(); m.onShiftDown(0); m.onShiftUp(); m.onShiftDown(delta); m.onShiftUp();
            assertEquals(delta < 300 ? CAPS_LOCK : OFF, m.state());
            if (delta < 300) { m.applyAutoCap(false); m.onCharacterInserted(); assertEquals(CAPS_LOCK,m.state());
                m.onShiftDown(320); m.onShiftUp(); assertEquals(OFF,m.state()); }
        }
    }

    @Test public void shiftChordAndOneShot() {
        var m = new ShiftStateMachine(); m.onShiftDown(1); m.onCharacterInserted(); m.applyAutoCap(false);
        assertTrue(m.isUppercase()); m.onShiftUp(); assertEquals(OFF, m.state());
        m.onShiftDown(100); m.onShiftUp(); assertEquals(SHIFTED,m.state());
        m.onCharacterInserted(); assertEquals(OFF,m.state()); m.applyAutoCap(true); assertTrue(m.isUppercase());
    }

    @Test public void accentsPreserveLanguageOrderAndDedicatedKeys() {
        assertEquals("aàáâäæãåā", String.join("", AccentTable.accentsFor("a", EN)));
        assertEquals("aáàäâãåąæāª", String.join("", AccentTable.accentsFor("a", ES)));
        assertEquals("oôœöòóõøō", String.join("", AccentTable.accentsFor("o", FR)));
        assertEquals("uùúûüū", String.join("", AccentTable.accentsFor("u", IT)));
        assertEquals("oóõôòöœøōº", String.join("", AccentTable.accentsFor("o", PT)));
        assertArrayEquals(new String[]{"ø","ö"}, AccentTable.accentsFor("ø", NB));
        assertFalse(List.of(AccentTable.accentsFor("u", DE)).contains("ü"));
        assertArrayEquals(new String[]{"/","\\"}, AccentTable.accentsFor("/", DE));
        assertEquals(0, AccentTable.accentsFor("&", EN).length); // No iOS alternatives.
    }

    @Test public void onlySpaceCorrectsAndProtectsIdentifiers() {
        var e = new AutocorrectEngine();
        for (String sep : List.of("\n", ".", "!", "", "  ")) assertNull(e.correct("teh",sep));
        for (String w : List.of("TeH", "TEH", "teh2", "2teh", "_teh", "teh.", "teh ")) assertNull(e.correct(w," "));
        var c = e.correct("before Teh", " "); assertEquals("Teh",c.original()); assertEquals("the",c.replacement()); assertEquals("The",c.caseMatched());
        assertEquals("I'm",e.correctOnSpace("im").caseMatched());
    }

    @Test public void undoIsSingleUseAndSuppressesOriginalNotCorrection() {
        var e = new AutocorrectEngine(); e.correctOnSpace("say teh");
        assertNull(e.checkBackspaceUndo("other the ")); assertNull(e.checkBackspaceUndo("say the "));
        e.correctOnSpace("say teh"); assertEquals("teh",e.checkBackspaceUndo("say the ").original());
        assertNull(e.checkBackspaceUndo("say the ")); assertNull(e.correctOnSpace("TEh")); assertNull(e.correctOnSpace("Teh"));
        assertEquals("the",e.correctOnSpace("hte").caseMatched()); e.clearUndo(); assertNull(e.checkBackspaceUndo("the "));
    }

    @Test public void suggestionsAndLanguageIsolation() {
        var e = new AutocorrectEngine();
        assertEquals(List.of("“teh”", "the", "Tehran"), e.suggestions("teh", List.of("Tehran","tehsil")));
        assertNull(e.correctOnSpace("tambien")); var es = new AutocorrectEngine(ES);
        assertNull(es.correctOnSpace("teh")); assertEquals("también", es.correctOnSpace("tambien").caseMatched());
    }

    @Test public void emojiSearchAndContext() {
        assertEquals(8, EmojiCatalog.categories().size());
        assertTrue(EmojiCatalog.categories().values().stream().allMatch(g -> g.size() >= 20));
        assertEquals(List.of("🍕"), EmojiCatalog.search(" PiZ "));
        assertEquals("🍕", EmojiCatalog.suggestForContext("have pizza "));
        assertEquals("🙏", EmojiCatalog.suggestForContext("Thanks! "));
        assertEquals("🔥", EmojiCatalog.suggestForContext("fire "));
        assertNull(EmojiCatalog.suggestForContext("pizza delivery ")); assertNull(EmojiCatalog.suggestForContext(""));
    }
}
