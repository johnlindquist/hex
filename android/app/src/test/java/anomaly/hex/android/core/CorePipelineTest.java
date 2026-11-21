package anomaly.hex.android.core;

import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CorePipelineTest {
    @Test public void historyLikePatternMatchesLiteralSearchText() {
        assertEquals("%word%",HexHistory.likePattern("word"));
        assertEquals("%50\\%\\_off\\\\%",HexHistory.likePattern("50%_off\\"));
        assertEquals("%\\\\\\%%",HexHistory.likePattern("\\%"));
    }

    // Exercise the real persistence keys/revision paths without booting an emulator.
    static final class Memory extends ContextWrapper {
        private final Map<String,SharedPreferences> files=new HashMap<>();
        Memory() { super(null); }
        @Override public SharedPreferences getSharedPreferences(String name,int mode) {
            return files.computeIfAbsent(name,n->{
                Map<String,Object> data=new HashMap<>();
                return (SharedPreferences)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SharedPreferences.class},(self,m,args)->{
                    if (m.getName().equals("edit")) {
                        Map<String,Object> pending=new HashMap<>();
                        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SharedPreferences.Editor.class},(e,em,a)->{
                            if (em.getName().startsWith("put")) { pending.put((String)a[0],a[1]); return e; }
                            if (em.getName().equals("apply")) { data.putAll(pending); return null; }
                            throw new AssertionError(em);
                        });
                    }
                    if (m.getName().startsWith("get")) return data.getOrDefault(args[0],args[1]);
                    throw new AssertionError(m);
                });
            });
        }
    }
    @Test public void settingsRoundTrip() {
        Memory c=new Memory(); HexSettings s=new HexSettings(c);
        assertEquals(TranscriptStyle.POLISHED,s.style()); assertEquals(HistoryRetention.DAYS_7,s.historyRetention());
        assertFalse(s.onboardingCompleted()); assertTrue(s.hapticsEnabled()); assertEquals(0,s.onboardingPage());
        s.setStyle(TranscriptStyle.CASUAL); s.setHistoryRetention(HistoryRetention.OFF); s.setHapticsEnabled(false);
        s.setOnboardingPage(3); s.setOnboardingCompleted(true); s.setOnboardingCompleted(true);
        HexSettings again=new HexSettings(c); assertEquals(5,again.revision());
        assertEquals("casual",again.toJson().optString("style")); assertTrue(again.onboardingCompleted());
        assertFalse(again.hapticsEnabled()); assertEquals(3,again.onboardingPage()); assertEquals(HistoryRetention.OFF,again.historyRetention());
        assertEquals(30L*86400000, HistoryRetention.DAYS_30.millis());
        for (TranscriptStyle t:TranscriptStyle.values()) assertEquals(t,TranscriptStyle.fromId(t.id()));
        assertEquals(HistoryRetention.DAYS_7, HistoryRetention.fromId(null));
        for (HistoryRetention r:HistoryRetention.values()) assertEquals(r,HistoryRetention.fromId(r.id()));
    }
    @Test public void labsIndependentAndStaleWritesRejected() {
        Memory c=new Memory(); HexLabs a=new HexLabs(c), b=new HexLabs(c);
        assertEquals(6,LabId.values().length);
        assertEquals(LabId.EMOJI_KEYBOARD, LabId.fromId("emoji_keyboard"));
        for (LabId l:LabId.values()) { assertFalse(a.isEnabled(l)); assertEquals(l,LabId.fromId(l.id)); }
        assertTrue(a.setEnabled(LabId.GEMMA_CLEANUP,true,0)); assertFalse(b.setEnabled(LabId.WORDS_ON_SCREEN,true,0));
        assertFalse(b.isEnabled(LabId.WORDS_ON_SCREEN)); assertTrue(b.isEnabled(LabId.GEMMA_CLEANUP));
        assertTrue(b.setEnabled(LabId.GEMMA_CLEANUP,true,1)); assertEquals(1,a.revision());
        b.setEnabledUnchecked(LabId.GEMMA_CLEANUP,false); assertFalse(a.toJson().optBoolean("gemma_cleanup"));
    }
    @Test public void vocabularySanitizesAndCaps() {
        Memory c=new Memory(); CustomVocabulary v=new CustomVocabulary(c);
        assertTrue(v.addTerm("   WeChat  ")); assertFalse(v.addTerm("we-chat")); assertFalse(v.addTerm("!"));
        assertFalse(v.addTerm("x".repeat(65))); assertFalse(v.addTerm("a"));
        assertEquals(List.of("WeChat"),new CustomVocabulary(c).terms()); assertTrue(v.removeTerm("we chat"));
        List<String> many=new ArrayList<>(); for(int i=0;i<105;i++) many.add("word"+i);
        v.replaceAll(many); assertEquals(many.subList(0,100),v.terms()); assertFalse(v.addTerm("extra"));
    }
    @Test public void spellingBoundariesAndPossessives() {
        List<String> v=List.of("WeChat","iPhone 7","X-ray","Shell","Open Code Lab","open");
        assertEquals("WeChat’s iPhone 7 and X-ray",CustomVocabulary.apply("we\tchat’s IPHONE-7 and x ray",v));
        assertEquals("Open Code Lab! 'WeChat'",CustomVocabulary.apply("open code lab! 'wechat'",v));
        String unchanged="we, chat we--chat we\nchat she'll welcome";
        assertEquals(unchanged,CustomVocabulary.apply(unchanged,v));
        assertEquals("a long Shell",CustomVocabulary.apply("a long shell",List.of("Along","Shell")));
    }
    private static String f(String raw,TranscriptStyle style,String before) { return TranscriptFormatter.format(raw,style,before); }
    @Test public void polishedSpacingAndAddresses() {
        assertEquals("Hello! Again.\nNext.",f(" uh, hello ! again.\n next ",TranscriptStyle.POLISHED,null));
        assertEquals("john@gmail.com",f("john@gmail.com",TranscriptStyle.POLISHED,null));
        assertEquals("I use e.g. Mail.",f("i use e.g. mail",TranscriptStyle.POLISHED,null));
        assertEquals("",f(" um ",TranscriptStyle.POLISHED,"word"));
    }
    @Test public void stylesAndContext() {
        assertEquals("hello",f("Hello.",TranscriptStyle.CASUAL,null)); assertEquals("hi! bye.",f("Hi! Bye.",TranscriptStyle.CASUAL,null));
        assertEquals("Hello\n- First\n- Second\n\nDone",f("hello bullet first next bullet second new paragraph done",TranscriptStyle.NOTES,null));
        assertEquals("um, Hi\nHere",f(" um,\tHi\n Here ",TranscriptStyle.VERBATIM,null));
        assertEquals(" hello.",f("Hello",TranscriptStyle.POLISHED,"well")); assertEquals("hello.",f("Hello",TranscriptStyle.POLISHED,"well "));
        assertEquals(" Hello.",f("hello",TranscriptStyle.POLISHED,"Well.”")); assertEquals("Hello.",f("hello",TranscriptStyle.POLISHED,"Well\n"));
        assertEquals(" NASA.",f("NASA",TranscriptStyle.POLISHED,"from")); assertEquals(" I’ll go.",f("I’ll go",TranscriptStyle.POLISHED,"and"));
        assertEquals("hello.",f("Hello",TranscriptStyle.POLISHED,"("));
    }
    @Test public void optionalCleanupAndSpellingReapplied() {
        List<String> v=List.of("hex","WeChat");
        assertEquals("hex uses WeChat.",TranscriptFormatter.applyPipeline("HEX uses we chat",TranscriptStyle.POLISHED,v,false,null));
        assertEquals("WeChat rocks",TranscriptFormatter.applyPipeline("WECHAT ROCKS.",TranscriptStyle.CASUAL,v,false,null));
        String repair="um, on Tuesday, I mean, Wednesday";
        assertEquals(repair,TranscriptFormatter.applyPipeline(repair,TranscriptStyle.VERBATIM,v,true,null));
        assertEquals(repair,TranscriptFormatter.applyPipeline(repair,TranscriptStyle.VERBATIM,v,false,null));
        assertEquals("I mean what I say",SpokenCleanupRules.apply("I mean what I say"));
        assertEquals("say no, we cannot",SpokenCleanupRules.apply("say no, we cannot"));
        assertEquals("Tuesday. We agreed, sorry, Wednesday",SpokenCleanupRules.apply("Tuesday. We agreed, sorry, Wednesday"));
    }
    @Test public void spokenAddressesAndLiteralGuards() {
        String[][] examples = {
            {"john dot doe at example dot com", "john.doe@example.com"},
            {"jane at acme dot io", "jane@acme.io"},
            {"Mail John at the rategmail.com.", "Mail john@gmail.com."},
            {"Priya at signyahoo.com!", "priya@yahoo.com!"},
            {"Joe at the rate, Acme dot co dot in, please", "joe@acme.co.in, please"},
            {"press the at-sign key", "press the @ key"},
        };
        for (String[] pair : examples) assertEquals(pair[0], pair[1], SpokenCleanupRules.apply(pair[0]));
        for (String literal : List.of("at sign language", "meet me at five", "at the rate of ten percent",
                "at the rate of dot com", "Look at that dot on the map.", "John at gmail.com.")) {
            assertEquals(literal, SpokenCleanupRules.apply(literal));
        }
        String raw = "jane at acme dot io";
        assertEquals("jane@acme.io", TranscriptFormatter.applyPipeline(raw, TranscriptStyle.POLISHED, List.of(), true, null));
        assertEquals(raw, TranscriptFormatter.applyPipeline(raw, TranscriptStyle.VERBATIM, List.of(), true, null));
    }
    @Test public void cleanupUsesDictationLanguage() {
        assertEquals("I saw the cat", SpokenCleanupRules.apply("uh I I saw the the cat"));
        assertEquals("hola mundo", SpokenCleanupRules.apply("eh, este pues hola hola mundo", "es-MX"));
        assertEquals("er geht", SpokenCleanupRules.apply("Äh, ähm er er geht", "de-DE"));
        for (String lang : List.of("nb-NO", "no")) assertEquals("det er bra", SpokenCleanupRules.apply("eh øh det det er bra", lang));
        assertEquals("Um dia.", TranscriptFormatter.format("um dia", TranscriptStyle.POLISHED, null, "pt"));
    }
    @Test public void vocabularyCanBePausedWithoutErasingTerms() {
        Memory context = new Memory();
        CustomVocabulary vocabulary = new CustomVocabulary(context);
        assertTrue(vocabulary.isEnabled()); vocabulary.addTerm("WeChat"); vocabulary.setEnabled(false);
        CustomVocabulary reopened = new CustomVocabulary(context);
        assertFalse(reopened.isEnabled()); reopened.setEnabled(true);
        assertEquals(List.of("WeChat"), reopened.terms());
    }
    @Test public void historyTextBoundedAtUtf8Boundary() {
        String prefix="a".repeat(16381); assertEquals(prefix,HexHistory.bounded(prefix+"😀"));
        assertEquals(16384,HexHistory.bounded(prefix+"語!").getBytes(StandardCharsets.UTF_8).length);
    }
}
