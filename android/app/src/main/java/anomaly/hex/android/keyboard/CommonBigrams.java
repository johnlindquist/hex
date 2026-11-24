package anomaly.hex.android.keyboard;

import java.util.Locale;
import java.util.Set;

/** Compact O(1) table of high-frequency English conversational bigrams for sentence-level disambiguation. */
final class CommonBigrams {
    private static final Set<String> PAIRS = Set.of(
            // "how" / "well" / "now" collocations
            "how well", "how much", "how many", "how long", "how far", "how good", "how bad",
            "how about", "how are", "how is", "how was", "how did", "how do", "how does",
            "how can", "how could", "how would", "how should", "how to", "just how", "see how",
            "know how", "show how", "wonder how",
            "as well", "very well", "pretty well", "really well", "so well", "too well",
            "works well", "work well", "went well", "going well", "doing well",
            "right now", "for now", "by now", "until now", "from now", "works now", "work now",
            "here now", "there now", "done now", "ready now", "good now", "better now",
            // Contraction & phrase starters
            "let's see", "let's go", "let's do", "let's try", "let's get", "let's make",
            "let's check", "let's test", "let's talk", "let's meet", "let's start", "let's keep",
            "let's take", "let's run", "let's fix", "let's look", "let's wait",
            // High-frequency everyday bigrams
            "want to", "need to", "have to", "going to", "trying to", "used to", "able to",
            "to do", "do this", "do that", "do it", "what do", "where do", "why do",
            "can do", "will do", "would do", "could do", "should do", "fix this", "fix that",
            "back to", "up to", "out of", "kind of", "sort of", "a lot", "at all",
            "in the", "on the", "for the", "to the", "of the", "at the", "with the",
            "from the", "by the", "and the", "is the", "was the", "that the",
            "this is", "that is", "it is", "there is", "here is", "what is", "who is",
            "where is", "when is", "this works", "that works", "it works",
            "thank you", "thanks for", "see you", "talk to", "let me", "tell me", "show me",
            "give me", "call me", "text me", "send me", "if you", "do you", "are you",
            "can you", "will you", "would you", "could you", "should we", "can we",
            "will we", "do we", "did you", "have you", "i think", "i know", "i want",
            "i need", "i have", "i will", "i would", "i can", "i just", "i really",
            "i love", "i like", "just a", "just got", "just want", "just need", "just saw",
            "just sent", "just made", "not sure", "make sure", "pretty sure", "so much",
            "too much", "very much", "more than", "less than", "better than", "other than",
            "each other", "any more", "no more", "as soon", "too late", "on time", "in time",
            "next week", "last week", "this week", "next time", "last time", "this time",
            "every time", "all good", "sounds good", "looks good", "feels good", "pretty good",
            "really good", "very good", "so good", "no worries", "no problem", "of course",
            "at least", "in case", "right away", "right here", "right there", "over there",
            "over here", "come back", "go back", "get back", "be back", "find out",
            "figure out", "work out", "check out", "hang out", "turn out", "point out",
            "set up", "pick up", "wake up", "catch up", "follow up", "sign up", "clean up",
            "give up", "end up", "show up", "come on", "hold on", "keep on", "move on",
            "work on", "based on", "depends on", "focus on"
    );

    private CommonBigrams() {}

    static boolean isCommon(String prevLower, String nextLower) {
        if (prevLower == null || nextLower == null || prevLower.isEmpty() || nextLower.isEmpty()) {
            return false;
        }
        return PAIRS.contains(prevLower.toLowerCase(Locale.ROOT) + " " + nextLower.toLowerCase(Locale.ROOT));
    }
}
