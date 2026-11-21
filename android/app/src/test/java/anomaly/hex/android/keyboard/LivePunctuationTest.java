package anomaly.hex.android.keyboard;

import org.junit.Test;
import static org.junit.Assert.*;

public class LivePunctuationTest {
    private String text="";
    private final LivePunctuationSession session=new LivePunctuationSession(0,0);
    private void apply(LivePunctuationSession.Edit edit,boolean tap) {
        text=text.substring(0,text.length()-edit.trim())+edit.text();
        session.committed(edit,tap);
        assertTrue(session.matches(text.length(),text.length()));
    }
    private void tap(String mark) { apply(session.tap(mark,text),true); }
    private void phrase(String phrase) { apply(session.phrase(phrase),false); }

    @Test public void marksTrimSpacesAndContinueAcrossLiveAndFinalPhrases() {
        phrase("one   "); tap(","); assertEquals("one, ",text);
        phrase("two"); tap("."); phrase("three"); tap("?"); phrase("four"); tap("!"); phrase("five");
        assertEquals("one, two. Three? Four! Five",text);
    }
    @Test public void lineAndParagraphBreaksSurviveFinalFlush() {
        phrase("one "); tap("\n"); phrase("two "); tap("\n\n"); phrase("three");
        assertEquals("one\nTwo\n\nThree",text);
    }
    @Test public void repeatedTapsAndEarlyTapPersist() {
        tap("!"); tap("?"); phrase("hello"); phrase(" and welcome"); tap("\n"); tap("\n");
        assertEquals("!? Hello and welcome\n\n",text);
    }
    @Test public void explicitPadReplacesRecognizedEndingButNotAnotherPad() {
        phrase("Ready."); tap("?"); tap("!"); phrase("yes");
        assertEquals("Ready?! Yes",text);
    }
    @Test public void selectionAnchorAdvancesOnlyOnConfirmation() {
        var selected=new LivePunctuationSession(9,5);
        var edit=selected.tap(",","word "); assertTrue(selected.matches(9,5));
        selected.committed(edit,true); assertTrue(selected.matches(6,6)); assertFalse(selected.matches(5,5));
    }
}
