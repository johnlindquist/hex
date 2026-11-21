package anomaly.hex.android.keyboard;

import android.text.InputType;
import org.junit.Test;
import static anomaly.hex.android.keyboard.EditorDeletePolicy.*;
import static org.junit.Assert.*;

public class TerminalDeleteTest {
  private static final int TEXT = InputType.TYPE_CLASS_TEXT;
  private static final int RAW = InputType.TYPE_NULL;

  @Test public void editTextUsesCodePointDeletion() {
    assertEquals(Delete.SURROUNDING_TEXT, deletion(TEXT, "", "😀"));
  }

  @Test public void selectionWinsEvenWithoutContext() {
    assertEquals(Delete.SELECTION, deletion(TEXT, "selected", ""));
  }

  @Test public void terminalUsesDeleteEvenWithContextOrFlags() {
    assertEquals(Delete.KEY_EVENT, deletion(RAW | InputType.TYPE_TEXT_FLAG_MULTI_LINE, null, "x"));
  }

  @Test public void textClassWithoutCursorContextUsesDeleteKey() {
    assertEquals(Delete.KEY_EVENT, deletion(TEXT, null, ""));
    assertEquals(Delete.KEY_EVENT, deletion(TEXT, null, null));
  }

  @Test public void acceleratedDeleteUsesOneKeyPerRepeatWithoutAnEditableWord() {
    assertEquals(Delete.KEY_EVENT, deletion(RAW, null, "last word"));
    assertEquals(Delete.KEY_EVENT, deletion(TEXT, null, ""));
    assertEquals(Delete.SURROUNDING_TEXT, deletion(TEXT, null, "last word"));
  }

  @Test public void enterKeyOnlyForRawEditorWithoutAction() {
    assertTrue(sendEnter(RAW, ""));
    assertFalse(sendEnter(RAW, "Send"));
    assertFalse(sendEnter(TEXT, ""));
  }
}
