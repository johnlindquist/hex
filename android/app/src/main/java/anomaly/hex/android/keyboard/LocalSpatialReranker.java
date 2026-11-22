package anomaly.hex.android.keyboard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.google.ai.edge.litertlm.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Optional on-device Gemma 4 E2B contextual reranker for Thumb-position autocorrect.
 * Never blocks typing: runs asynchronously on a single background thread with latest-word coalescing
 * and caches picks for (contextTail, typedWord). Constrained to return only one of the spatial candidates.
 */
final class LocalSpatialReranker {
  private static final String MODEL_FILE = "gemma-4-E2B-it.litertlm";
  private static final int MAX_CACHE = 64;

  private record PendingJob(Context ctx, String cacheKey, String contextTail, String typed,
                            List<String> candidates, List<String> screenHints, Runnable onReady) {}

  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "hex-spatial-gemma");
    t.setDaemon(true);
    return t;
  });
  private final Map<String, String> cache = Collections.synchronizedMap(
      new LinkedHashMap<>(MAX_CACHE, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
          return size() > MAX_CACHE;
        }
      });
  private final AtomicReference<PendingJob> latest = new AtomicReference<>();
  private Engine engine;

  /** O(1) non-blocking lookup on the UI thread. Returns null if no Gemma rerank is cached yet. */
  String lookupCached(String contextBeforeWord, String typedWord, List<String> candidates) {
    if (typedWord == null || typedWord.isBlank() || candidates == null || candidates.size() < 2) return null;
    String key = cacheKey(contextBeforeWord, typedWord);
    String pick = cache.get(key);
    if (pick == null) return null;
    for (String c : candidates) {
      if (c.equalsIgnoreCase(pick)) return c;
    }
    return null;
  }

  /** Schedules an async local Gemma rerank if the model is installed and no cached answer exists. */
  void requestRerank(Context context, String contextBeforeWord, String typedWord,
                     List<String> candidates, List<String> screenHints, Runnable onReady) {
    if (context == null || typedWord == null || typedWord.length() < 3
        || candidates == null || candidates.size() < 2) return;
    Context appCtx = context.getApplicationContext();
    File model = new File(appCtx.getNoBackupFilesDir(), MODEL_FILE);
    if (!model.isFile()) return;
    String tail = contextTail(contextBeforeWord);
    List<String> hints = screenHints == null ? List.of() : List.copyOf(screenHints);
    if (tail.isEmpty() && hints.isEmpty()) return;
    String key = tail + "|" + typedWord.toLowerCase(Locale.ROOT);
    if (cache.containsKey(key)) return;

    PendingJob job = new PendingJob(appCtx, key, tail, typedWord, List.copyOf(candidates), hints, onReady);
    if (latest.getAndSet(job) == null) {
      worker.execute(this::drainLoop);
    }
  }

  void release() {
    latest.set(null);
    worker.execute(() -> {
      if (engine != null) {
        try { engine.close(); } catch (Throwable ignored) {}
        engine = null;
      }
    });
  }

  private void drainLoop() {
    while (true) {
      PendingJob job = latest.getAndSet(null);
      if (job == null) return;
      if (cache.containsKey(job.cacheKey())) continue;
      try {
        String pick = runGemma(job.ctx(), job.contextTail(), job.typed(), job.candidates(), job.screenHints());
        if (pick != null) {
          cache.put(job.cacheKey(), pick);
          if (job.onReady() != null) main.post(job.onReady());
        }
      } catch (Throwable ignored) {
        // Never crash or disturb typing if local inference fails or runs OOM.
      }
    }
  }

  private String runGemma(Context c, String tail, String typed, List<String> candidates, List<String> screenHints) throws Exception {
    File model = new File(c.getNoBackupFilesDir(), MODEL_FILE);
    if (!model.isFile()) return null;
    if (engine == null) {
      Engine.Companion.setNativeMinLogSeverity(LogSeverity.INFINITY);
      EngineConfig cfg = new EngineConfig(
          model.getPath(), new Backend.CPU(4, null), null, null, 512, null, c.getCacheDir().getPath());
      Engine created = new Engine(cfg);
      created.initialize();
      engine = created;
    }
    String prompt = buildPrompt(tail, typed, candidates, screenHints);
    ConversationConfig cc = new ConversationConfig(
        Contents.Companion.of("You choose the best word from the given choices. Reply with one word only."),
        Collections.emptyList(), Collections.emptyList(), new SamplerConfig(1, 1.0, 0.0, 17), false);
    try (Conversation cv = engine.createConversation(cc)) {
      Message msg = cv.sendMessage(
          prompt, Collections.emptyMap(), null, null, null, 8, new ThinkingConfig(false), null);
      StringBuilder sb = new StringBuilder();
      for (Content x : msg.getContents().getContents()) {
        if (x instanceof Content.Text t) sb.append(t.getText());
      }
      return matchCandidate(sb.toString(), candidates);
    }
  }

  static String buildPrompt(String tail, String typed, List<String> candidates, List<String> screenHints) {
    StringBuilder sb = new StringBuilder(256);
    sb.append("Pick the single best word to replace the fat-thumb typo based on context. ")
        .append("Reply with ONLY one word from the choices.\n");
    if (tail != null && !tail.isEmpty()) {
      sb.append("Context: \"").append(tail).append("\"\n");
    }
    if (screenHints != null && !screenHints.isEmpty()) {
      sb.append("Words on screen: ").append(String.join(", ", screenHints)).append('\n');
    }
    sb.append("Typed: \"").append(typed).append("\"\n");
    sb.append("Choices: ").append(String.join(", ", candidates));
    return sb.toString();
  }

  static String matchCandidate(String rawReply, List<String> candidates) {
    if (rawReply == null || rawReply.isBlank() || candidates == null) return null;
    String cleaned = rawReply.trim().replaceAll("^[^\\p{L}']+|[^\\p{L}']+$", "");
    for (String c : candidates) {
      if (c.equalsIgnoreCase(cleaned)) return c;
    }
    return null;
  }

  private static String cacheKey(String contextBeforeWord, String typedWord) {
    return contextTail(contextBeforeWord) + "|" + typedWord.toLowerCase(Locale.ROOT);
  }

  static String contextTail(String beforeWord) {
    if (beforeWord == null) return "";
    String trimmed = beforeWord.trim();
    if (trimmed.isEmpty()) return "";
    String[] words = trimmed.split("\\s+");
    int start = Math.max(0, words.length - 10);
    StringBuilder sb = new StringBuilder();
    for (int i = start; i < words.length; i++) {
      if (sb.length() > 0) sb.append(' ');
      sb.append(words[i]);
    }
    return sb.toString();
  }
}
