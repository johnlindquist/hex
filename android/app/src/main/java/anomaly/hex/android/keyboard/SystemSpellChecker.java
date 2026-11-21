package anomaly.hex.android.keyboard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.textservice.*;
import java.util.*;

/** Optional async bridge to Android's system SpellCheckerSession with bounded LRU caching. */
final class SystemSpellChecker implements SpellCheckerSession.SpellCheckerSessionListener {
    private static final int MAX_CACHE = 256;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable onUpdate;
    private final LinkedHashMap<String, List<String>> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) { return size() > MAX_CACHE; }
    };
    private final Map<Integer, String> inflight = Collections.synchronizedMap(new HashMap<>());
    private int nextCookie = 1;
    private SpellCheckerSession session;
    private Locale activeLocale;

    SystemSpellChecker(Runnable onUpdate) { this.onUpdate = onUpdate; }

    void open(Context ctx, Locale locale) {
        if (session != null && Objects.equals(activeLocale, locale)) return;
        close();
        activeLocale = locale;
        try {
            TextServicesManager tsm = (TextServicesManager) ctx.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE);
            if (tsm != null) session = tsm.newSpellCheckerSession(null, locale, this, true);
        } catch (Throwable ignored) {
            session = null;
        }
    }

    void close() {
        if (session != null) {
            try { session.close(); } catch (Throwable ignored) {}
            session = null;
        }
        inflight.clear();
    }

    /** Returns cached suggestions immediately if available; otherwise dispatches an async lookup. */
    List<String> lookupOrQuery(String word) {
        if (word == null || word.length() < 3) return List.of();
        String key = word.toLowerCase(Locale.ROOT);
        synchronized (cache) {
            List<String> hit = cache.get(key);
            if (hit != null) return hit;
        }
        SpellCheckerSession s = session;
        if (s != null && !inflight.containsValue(key)) {
            int cookie = nextCookie++;
            inflight.put(cookie, key);
            try {
                s.getSentenceSuggestions(new TextInfo[]{new TextInfo(word, cookie, 0)}, 3);
            } catch (Throwable ignored) {
                inflight.remove(cookie);
            }
        }
        return List.of();
    }

    @Override public void onGetSuggestions(SuggestionsInfo[] results) {
        if (results == null) return;
    }

    @Override public void onGetSentenceSuggestions(SentenceSuggestionsInfo[] results) {
        if (results == null) return;
        boolean changed = false;
        for (SentenceSuggestionsInfo ssi : results) {
            if (ssi == null) continue;
            for (int i = 0; i < ssi.getSuggestionsCount(); i++) {
                SuggestionsInfo info = ssi.getSuggestionsInfoAt(i);
                if (info == null) continue;
                String key = inflight.remove(info.getCookie());
                if (key == null) continue;
                int attrs = info.getSuggestionsAttributes();
                boolean typo = (attrs & SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0
                        || (attrs & SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS) != 0;
                List<String> list = new ArrayList<>(3);
                if (typo) {
                    int count = Math.min(3, info.getSuggestionsCount());
                    for (int j = 0; j < count; j++) {
                        String cand = info.getSuggestionAt(j);
                        if (cand != null && !cand.isBlank() && !cand.contains(" ")) list.add(cand);
                    }
                }
                synchronized (cache) { cache.put(key, List.copyOf(list)); }
                if (!list.isEmpty()) changed = true;
            }
        }
        if (changed) main.post(onUpdate);
    }
}
