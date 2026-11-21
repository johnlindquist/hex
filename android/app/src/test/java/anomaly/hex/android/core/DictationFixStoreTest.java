package anomaly.hex.android.core;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DictationFixStoreTest {
    @Test public void promotionIsPersistentOptInAndUncontested() {
        CorePipelineTest.Memory ctx = new CorePipelineTest.Memory();
        DictationFixStore s = new DictationFixStore(ctx), reopened = new DictationFixStore(ctx);
        assertFalse(s.isEnabled());
        s.recordSighting("cloud", "Claude");
        s.setEnabled(true);
        s.recordSighting("cloud", "claude");
        assertTrue(s.activeFixes().isEmpty());
        reopened.recordSighting("Cloud", "Claude");
        assertEquals(Map.of("cloud", "Claude"), s.activeFixes());
        s.setEnabled(false);
        assertEquals("cloud", reopened.apply("cloud", List.of()));
        s.setEnabled(true);
        assertEquals("Claude", reopened.apply("cloud", List.of()));
        reopened.recordSighting("cloud", "crowd");
        s.recordSighting("cloud", "Claude");
        assertTrue(s.activeFixes().isEmpty());
        s.removeFix("Cloud");
        s.recordSighting("cloud", "crowd"); reopened.recordSighting("cloud", "crowd");
        assertEquals(Map.of("cloud", "crowd"), s.activeFixes());
        reopened.clearAll(); assertTrue(s.activeFixes().isEmpty()); assertTrue(s.isEnabled());
    }
    @Test public void boundedMatchesAreLiteralSinglePassAndFollowCase() {
        Map<String,String> rules = Map.of("cloud", "Claude", "their", "there", "open code", "OpenCode", "code", "$1");
        String inputs = "cloud CLOUD cloud’s cloud.com /cloud/ @cloud #cloud cloud_name cloudy cloud-based cloud.";
        assertEquals("Claude CLAUDE Claude’s cloud.com /cloud/ @cloud #cloud cloud_name cloudy cloud-based Claude.",
                DictationFixStore.applyRules(inputs, List.of(), rules));
        assertEquals("There there THERE OpenCode open, $1", DictationFixStore.applyRules("Their their THEIR open\tcode open, code", List.of(), rules));
        assertEquals("Cloud services and Cloud.", TranscriptFormatter.applyPipeline("cloud services and cloud", TranscriptStyle.POLISHED,
                List.of("Cloud"), false, null, rules));
        assertEquals("Open Code Lab", DictationFixStore.applyRules("open code lab", List.of("Open Code Lab"), rules));
    }
}
