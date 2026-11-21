package anomaly.hex.android.keyboard;

import android.view.inputmethod.InputConnection;
import java.lang.reflect.Proxy;
import org.junit.Test;
import static org.junit.Assert.*;

public class SelectionAndSpeakTest {
    private final SelectionTrackpad pad = new SelectionTrackpad();
    private void move(int delta, int anchor, int active) {
        assertEquals(new SelectionTrackpad.Range(anchor, active), pad.move(delta));
    }

    @Test public void extendsShrinksAndReversesWithoutMovingAnchor() {
        pad.begin(5,5,10);
        move(-3,5,2); move(1,5,3); move(2,5,5); move(4,5,9); move(-7,5,2);
    }
    @Test public void continuesFromActiveEndOfExistingSelections() {
        pad.begin(2,5,8); move(1,2,6); move(-5,2,1);
        pad.begin(6,3,8); move(-1,6,2); move(5,6,7);
    }
    @Test public void clampsAtBothBoundsWithoutAccumulatingOvershoot() {
        pad.begin(3,5,8);
        move(Integer.MAX_VALUE,3,8); move(-1,3,7); move(Integer.MIN_VALUE,3,0); move(1,3,1);
        pad.begin(0,0,0); move(9,0,0);
    }
    @Test public void endingGestureForgetsTheAnchorUntilNextBegin() {
        assertNull(pad.move(1)); pad.begin(4,1,5); pad.end(); assertNull(pad.move(-1));
        pad.begin(0,0,2); move(1,0,1);
    }
    @Test public void replacementUndoSpansAllPhrasesAndKeepsBothNeighbors() {
        String original = "old words", prefix = "before ", suffix = " after";
        DictationUndo undo = new DictationUndo("new",prefix.length()+3,original).append(" words!");
        StringBuilder document = new StringBuilder(prefix + "new words!" + suffix);
        assertTrue(undo.matches(undo.end(),document.substring(0,undo.end())));
        assertEquals(prefix.length(),undo.start());
        int[] selected = {undo.end(),undo.end()};
        InputConnection connection = (InputConnection)Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{InputConnection.class}, (proxy, method, args) -> switch (method.getName()) {
                case "beginBatchEdit", "endBatchEdit" -> true;
                case "setSelection" -> { selected[0]=(int)args[0]; selected[1]=(int)args[1]; yield true; }
                case "getSelectedText" -> document.substring(selected[0],selected[1]);
                case "commitText" -> { document.replace(selected[0],selected[1],args[0].toString()); yield true; }
                default -> throw new AssertionError(method.getName());
            });
        undo.restore(connection);
        assertEquals(prefix + original + suffix,document.toString());
    }
    @Test public void changedTextMovedCursorAndTruncatedContextCannotUndo() {
        DictationUndo undo = new DictationUndo("new",7,"old");
        assertFalse(undo.matches(7,"now")); assertFalse(undo.matches(8,"new"));
        assertFalse(undo.matches(7,"ew")); assertTrue(undo.matches(7,"new"));
    }
    @Test public void ordinaryInsertionDoesNotRestoreAnEarlierSelection() {
        DictationUndo insertion = new DictationUndo("📚",4,null);
        assertNull(insertion.replacedSelection()); assertEquals(2,insertion.start());
    }
}
