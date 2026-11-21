package anomaly.hex.android.keyboard;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.Map;
import org.junit.BeforeClass;
import org.junit.Test;

public class EmojiCatalogParityTest {
    @BeforeClass public static void loadFullEmojiAsset() throws Exception {
        File f = new File("src/main/assets/keyboard/emoji-index-en.txt");
        if (!f.exists()) f = new File("app/src/main/assets/keyboard/emoji-index-en.txt");
        assertTrue("Expected emoji-index-en.txt asset at " + f.getAbsolutePath(), f.isFile());
        try (FileInputStream in = new FileInputStream(f)) {
            EmojiCatalog.load(in, false);
        }
    }

    @Test public void fullUnicodeCatalogHasAllCategoriesAnd1800PlusEmojis() {
        assertTrue("Expected >= 1800 emojis, got " + EmojiCatalog.size(), EmojiCatalog.size() >= 1800);
        Map<String, List<String>> cats = EmojiCatalog.categories();
        assertEquals(8, cats.size());
        for (var e : cats.entrySet()) {
            assertTrue("Category " + e.getKey() + " should have >= 40 emojis, got " + e.getValue().size(),
                    e.getValue().size() >= 40);
        }
        assertFalse("Thumbs up should have skin tones", EmojiCatalog.tonesFor("👍").isEmpty());
    }

    @Test public void multiWordAndChatAliasSearchMatchesGboardExpectations() {
        List<String> redHeart = EmojiCatalog.search("red heart");
        assertFalse(redHeart.isEmpty());
        assertEquals("❤️", redHeart.get(0));

        List<String> thumbsUp = EmojiCatalog.search("thumbs up");
        assertTrue("Expected 👍 in top 3 for 'thumbs up', got " + thumbsUp.subList(0, Math.min(5, thumbsUp.size())),
                thumbsUp.subList(0, Math.min(3, thumbsUp.size())).contains("👍"));

        List<String> shrug = EmojiCatalog.search("shrug");
        assertTrue("Expected 🤷 in shrug search", shrug.stream().anyMatch(e -> e.startsWith("🤷")));

        List<String> mindBlown = EmojiCatalog.search("mind blown");
        assertTrue("Expected 🤯 in mind blown search", mindBlown.contains("🤯"));

        List<String> skull = EmojiCatalog.search("skull");
        assertTrue("Expected 💀 in skull search", skull.contains("💀"));

        List<String> rocket = EmojiCatalog.search("rocket");
        assertTrue("Expected 🚀 in rocket search", rocket.contains("🚀"));
    }

    @Test public void contextualPhraseAndStemmedSuggestionsWork() {
        String bday = EmojiCatalog.suggestForContext("happy birthday ");
        assertNotNull("Expected birthday emoji", bday);
        assertTrue("Unexpected birthday emoji: " + bday, List.of("🎂", "🥳", "🎈", "🎉", "🎁").contains(bday));

        String pizza = EmojiCatalog.suggestForContext("want some pizza ");
        assertEquals("🍕", pizza);

        String dogs = EmojiCatalog.suggestForContext("I love dogs ");
        assertNotNull("Expected dog emoji for plural 'dogs'", dogs);

        assertNull("Stopwords alone must not trigger noise", EmojiCatalog.suggestForContext("the and for "));
    }
}
