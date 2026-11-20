package anomaly.hex.android.cleanup;

import org.junit.Test;
import static org.junit.Assert.*;

public class FactGuardTest {
    @Test public void explicitDayCorrectionKeepsFinalDay(){
        assertEquals("Meet Friday at 9:30",FactGuard.prepare("Meet Thursday, no, Friday at 9:30"));
        assertEquals("Not Thursday but Friday",FactGuard.prepare("Not Thursday but Friday"));
    }
    @Test public void rejectsDroppedAddedAndReorderedLiterals(){
        String s="Meet May 3 at 9:30, not May 4";
        for(String bad:new String[]{"Meet May 3 at 9:30",s+" at 5","Meet May 4 at 9:30, not May 3"})
            assertEquals(s,FactGuard.guard(s,bad));
    }
    @Test public void acceptsFormattingWithoutChangingLiterals(){
        String suggestion="Meet Friday at 9:30.";
        assertEquals(suggestion,FactGuard.guard("um meet friday at 9:30",suggestion));
    }
}
