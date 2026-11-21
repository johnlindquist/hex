package anomaly.hex.android.keyboard;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class AutocorrectEngineTest {
    private final AutocorrectEngine engine = new AutocorrectEngine();

    @Test public void curatedCorrectionsAndContractions() {
        for (String pair : ("freind:friend tommorow:tomorrow untill:until occured:occurred wierd:weird "
                + "woudl:would shoudl:should coudl:could abotu:about whcih:which waht:what knwo:know liek:like "
                + "tiem:time peopel:people realy:really werent:weren't hasnt:hasn't hadnt:hadn't whats:what's "
                + "whos:who's heres:here's youve:you've weve:we've theyve:they've theres:there's Im:I'm Recieve:Receive").split(" ")) {
            String[] parts = pair.split(":");
            assertEquals(parts[0], parts[1], engine.correct(parts[0], " ").caseMatched());
        }
    }

    @Test public void pronounUndoAndSessionLifetime() {
        assertNull(engine.correct("hello i", "\n"));
        assertEquals("I", engine.correct("i", " ").caseMatched());
        assertEquals("I", engine.correct("hello i", " ").caseMatched());
        assertEquals("i", engine.checkBackspaceUndo("hello I ").original());
        assertNull(engine.checkBackspaceUndo("hello I "));
        assertNull(engine.correctOnSpace("again i"));
        assertNull(engine.correctOnSpace("I"));
        for (KeyboardLanguage lang : KeyboardLanguage.values()) if (lang != KeyboardLanguage.EN)
            assertNull(new AutocorrectEngine(lang).correctOnSpace("i"));
    }

    @Test public void unknownAndAmbiguousWordsAreNotRewritten() {
        for (String word : List.of("ill", "well", "were", "hell", "lets", "wed", "shed", "id", "shell", "calender", "TIEM", "tIem", "_tiem", "tiem2", "x_tiem", "I"))
            assertNull(word, engine.correctOnSpace(word));
    }

    @Test public void fuzzyStripMakesAllFourEditsWithoutSilentlyChangingText() {
        for (String typo : List.of("messgae", "messxge", "messge", "messsage")) {
            assertTrue(typo, engine.suggestions(typo, List.of()).contains("message"));
            assertNull(engine.correctOnSpace(typo));
        }
        assertTrue(engine.suggestions("Messgae", List.of()).contains("Message"));
        assertFalse(engine.suggestions("mexxage", List.of()).contains("message"));
        engine.suppress("messgae");
        assertEquals(List.of("“messgae”"), engine.suggestions("messgae", List.of()));
    }

    @Test public void customVocabularyKeepsPreferredCaseAndPrefixPriority() {
        assertEquals(List.of("“parkaeet”", "ParkaEetTools", "Parakeet"),
                engine.suggestions("parkaeet", List.of("Parakeet", "ParkaEetTools")));
        assertEquals(List.of("“MEssgae”"), engine.suggestions("MEssgae", List.of("Message")));
    }
}
