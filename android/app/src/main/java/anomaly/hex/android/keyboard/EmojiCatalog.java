package anomaly.hex.android.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EmojiCatalog {
    private static final Map<String, List<String>> groups = new LinkedHashMap<>();
    private static final Map<String, List<String>> keywords = new LinkedHashMap<>();
    private static final Pattern LAST = Pattern.compile("([\\p{L}]+)[\\s.!?,]*\\z");
    static {
        group("Smileys & people", "😀:smile,happy,grin 😃:joy 😄:cheerful 😁:beam 😆:laugh 😂:lol,laugh,tears 🤣:rofl,laugh 😊:blush,smile 😇:angel 🥰:love,adore 😍:heart,eyes 😘:kiss 😎:cool 🤔:think 🤗:hug 😴:sleep 😭:cry 😡:angry 🥳:party 🙏:thanks,thank,please 👏:clap,bravo 👍:yes,like 👎:no 👋:hello,bye 💪:strong 🤞:luck");
        group("Animals & nature", "🐶:dog,puppy 🐱:cat,kitten 🐭:mouse 🐹:hamster 🐰:rabbit 🦊:fox 🐻:bear 🐼:panda 🐨:koala 🦁:lion 🐯:tiger 🐮:cow 🐷:pig 🐸:frog 🐵:monkey 🐝:bee 🦋:butterfly 🌱:plant 🌳:tree 🌷:flower 🌹:rose 🌻:sunflower 🔥:fire,hot 🌈:rainbow ☀️:sun");
        group("Food & drink", "🍕:pizza 🍎:apple 🍊:orange 🍋:lemon 🍌:banana 🍉:watermelon 🍇:grapes 🍓:strawberry 🥑:avocado 🥕:carrot 🥐:croissant 🍞:bread 🍔:burger 🍟:fries 🌮:taco 🍣:sushi 🍜:noodles 🍰:cake 🎂:birthday 🍪:cookie 🍫:chocolate ☕:coffee 🍵:tea 🍺:beer 🍷:wine");
        group("Activity", "⚽:soccer,football 🏀:basketball 🏈:football ⚾:baseball 🎾:tennis 🏐:volleyball 🏉:rugby 🎱:pool 🏓:pingpong 🏸:badminton 🥊:boxing 🏒:hockey 🎣:fishing ⛷️:ski 🎮:game 🎯:target 🏆:trophy,win 🥇:gold 🎸:guitar 🎨:art");
        group("Travel & places", "🚗:car 🚕:taxi 🚌:bus 🚑:ambulance 🚒:firetruck 🚲:bike 🛴:scooter 🚂:train 🚇:metro ✈️:plane,flight 🚁:helicopter 🚀:rocket ⛵:sailboat 🚢:ship 🏠:home 🏕️:camping ⛰️:mountain 🏖️:beach 🏝️:island 🌍:earth");
        group("Objects", "💡:idea,light 📱:phone 💻:laptop,computer ⌨️:keyboard 📷:camera 📺:television 📻:radio ⏰:alarm ⌚:watch 🔋:battery 🔑:key 🔒:lock ✏️:pencil 📖:book 📝:note 📌:pin 📎:paperclip 🎁:gift 🎈:balloon 🎉:congratulations,celebrate");
        group("Symbols", "❤️:heart,love 🧡:orangeheart 💛:yellowheart 💚:greenheart 💙:blueheart 💜:purpleheart 🖤:blackheart 🤍:whiteheart 💔:brokenheart 💕:hearts 💯:hundred ✅:check,done ❌:cross ⚠️:warning ❓:question ❗:exclamation ♻️:recycle ⭐:star ✨:sparkles 🎵:music");
        group("Flags", "🏳️:whiteflag 🏴:blackflag 🏁:finish 🚩:flag 🏳️‍🌈:pride 🇺🇸:usa,america 🇬🇧:uk,britain 🇨🇦:canada 🇪🇸:spain 🇫🇷:france 🇩🇪:germany 🇮🇹:italy 🇵🇹:portugal 🇳🇴:norway 🇧🇷:brazil 🇲🇽:mexico 🇯🇵:japan 🇰🇷:korea 🇮🇳:india 🇦🇺:australia");
    }

    private EmojiCatalog() {}
    private static void group(String name, String entries) {
        List<String> group = new ArrayList<>();
        for (String entry : entries.split(" ")) {
            String[] pair = entry.split(":");
            group.add(pair[0]);
            for (String word : pair[1].split(",")) keywords.computeIfAbsent(word, k -> new ArrayList<>()).add(pair[0]);
        }
        groups.put(name, List.copyOf(group));
    }
    public static Map<String, List<String>> categories() { return Collections.unmodifiableMap(groups); }
    public static List<String> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        String q = query.trim().toLowerCase(Locale.ROOT);
        LinkedHashSet<String> hits = new LinkedHashSet<>(keywords.getOrDefault(q, List.of()));
        keywords.forEach((word, emoji) -> { if (word.startsWith(q)) hits.addAll(emoji); });
        return List.copyOf(hits);
    }
    public static String suggestForContext(String recentText) {
        if (recentText == null) return null;
        Matcher last = LAST.matcher(recentText);
        if (!last.find()) return null;
        List<String> matches = keywords.get(last.group(1).toLowerCase(Locale.ROOT));
        return matches == null ? null : matches.get(0);
    }

    /** Ranked by use count; a fresh use wins ties. Only emoji selections are persisted. */
    public static final class FrequentlyUsed {
        private final SharedPreferences prefs;
        public FrequentlyUsed(Context context) { prefs = context.getSharedPreferences("hex_emoji_usage", Context.MODE_PRIVATE); }
        private record Entry(String emoji, int count) {}
        private List<Entry> entries() {
            List<Entry> result = new ArrayList<>();
            for (String item : prefs.getString("ranked", "").split(" ")) {
                String[] pair = item.split(":", 2);
                if (pair.length == 2) try { result.add(new Entry(pair[0], Integer.parseInt(pair[1]))); } catch (NumberFormatException ignored) {}
            }
            return result;
        }
        public void record(String emoji) {
            if (groups.values().stream().noneMatch(g -> g.contains(emoji))) return;
            synchronized (prefs) {
                List<Entry> entries = entries();
                int count = 1;
                for (Entry e : entries) if (e.emoji.equals(emoji)) { count = e.count == Integer.MAX_VALUE ? e.count : e.count + 1; break; }
                entries.removeIf(e -> e.emoji.equals(emoji));
                entries.add(0, new Entry(emoji, count));
                entries.sort((a, b) -> Integer.compare(b.count, a.count));
                StringBuilder value = new StringBuilder();
                for (Entry e : entries.subList(0, Math.min(120, entries.size()))) value.append(e.emoji).append(':').append(e.count).append(' ');
                prefs.edit().putString("ranked", value.toString()).apply();
            }
        }
        public List<String> list() {
            List<String> result = new ArrayList<>();
            for (Entry e : entries()) result.add(e.emoji);
            return List.copyOf(result);
        }
    }
}
