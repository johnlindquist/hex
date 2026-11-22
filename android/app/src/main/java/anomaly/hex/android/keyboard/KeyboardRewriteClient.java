package anomaly.hex.android.keyboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import anomaly.hex.android.anywhere.GeminiDirectClient;
import anomaly.hex.android.anywhere.TypeAnywhereAiClient;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Calls hex-api /api/rewrite to produce multiple screen-aware rewrites of the user's message. */
public final class KeyboardRewriteClient {
  public static final String DEFAULT_REWRITE_ENDPOINT = "https://hex-api-five.vercel.app/api/rewrite";

  public static final class Session {
    public final String originalDraft;
    public final String instruction;
    public final List<Option> options;
    public final boolean usedScreen;
    public final boolean usedWeb;
    public final String appliedText;

    public Session(String originalDraft, String instruction, List<Option> options,
                   boolean usedScreen, boolean usedWeb, String appliedText) {
      this.originalDraft = originalDraft == null ? "" : originalDraft;
      this.instruction = instruction;
      this.options = options == null ? List.of() : List.copyOf(options);
      this.usedScreen = usedScreen;
      this.usedWeb = usedWeb;
      this.appliedText = appliedText;
    }

    public Session withApplied(String text) {
      return new Session(originalDraft, instruction, options, usedScreen, usedWeb, text);
    }

    /** True when the current editor text still belongs to this rewrite batch (original or an applied option). */
    public boolean matchesEditorText(String current) {
      if (options.isEmpty() || current == null) return false;
      String trimmed = current.trim();
      if (trimmed.isEmpty()) return false;
      if (appliedText != null && trimmed.equals(appliedText.trim())) return true;
      if (!originalDraft.isEmpty() && trimmed.equals(originalDraft.trim())) return true;
      for (Option opt : options) {
        if (trimmed.equals(opt.text.trim())) return true;
      }
      return false;
    }
  }

  public static final class Option {
    public final String label;
    public final String text;
    public Option(String label, String text) {
      this.label = label;
      this.text = text;
    }
  }

  public interface Callback {
    void onOptions(List<Option> options, boolean usedScreen, boolean usedWeb);
    void onError(String message);
  }

  private static final ExecutorService worker =
      new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>());

  private KeyboardRewriteClient() {}

  public static String rewriteEndpoint(String askEndpoint) {
    if (askEndpoint == null || askEndpoint.isBlank()) return DEFAULT_REWRITE_ENDPOINT;
    String trimmed = askEndpoint.trim();
    if (trimmed.endsWith("/api/ask")) {
      return trimmed.substring(0, trimmed.length() - "/api/ask".length()) + "/api/rewrite";
    }
    if (trimmed.endsWith("/api/rewrite")) return trimmed;
    return DEFAULT_REWRITE_ENDPOINT;
  }

  public static List<Option> parseResponse(String json) {
    if (json == null || json.isBlank()) return Collections.emptyList();
    try {
      JSONObject root = new JSONObject(json);
      JSONArray arr = root.optJSONArray("options");
      if (arr == null) return Collections.emptyList();
      List<Option> out = new ArrayList<>();
      for (int i = 0; i < arr.length(); i++) {
        JSONObject item = arr.optJSONObject(i);
        if (item == null) continue;
        String label = item.optString("label", "").trim();
        String text = item.optString("text", "").trim();
        if (!label.isEmpty() && !text.isEmpty()) {
          out.add(new Option(label, text));
        }
      }
      return out;
    } catch (JSONException e) {
      return Collections.emptyList();
    }
  }

  public static boolean parseSearched(String json) {
    if (json == null || json.isBlank()) return false;
    try {
      return new JSONObject(json).optBoolean("searched", false);
    } catch (JSONException e) {
      return false;
    }
  }

  public static void rewrite(Context context, String draft, Bitmap screen, Callback cb) {
    rewrite(context, draft, null, screen, cb);
  }

  public static void rewrite(Context context, String draft, String instruction, Bitmap screen, Callback cb) {
    Context c = context.getApplicationContext();
    Handler main = new Handler(Looper.getMainLooper());
    boolean hasScreen = screen != null;
    String inst = instruction == null ? "" : instruction.trim();
    try {
      worker.execute(() -> {
        String geminiKey = TypeAnywhereAiClient.geminiKey(c);
        String proxyToken = TypeAnywhereAiClient.proxyToken(c);
        if (geminiKey.isEmpty() && proxyToken.isEmpty()) {
          main.post(() -> cb.onError("Add your Gemini API key in Hex › Labs › Type anywhere."));
          return;
        }
        try {
          String imgBase64 = null;
          if (screen != null && !screen.isRecycled()) {
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            if (screen.compress(Bitmap.CompressFormat.JPEG, 72, jpeg)) {
              imgBase64 = Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP);
            }
          }
          if (!geminiKey.isEmpty()) {
            GeminiDirectClient.RewriteResult res = GeminiDirectClient.rewrite(
                geminiKey, TypeAnywhereAiClient.model(c), draft, inst, imgBase64);
            main.post(() -> cb.onOptions(res.options, hasScreen, res.searched));
            return;
          }
          String url = rewriteEndpoint(TypeAnywhereAiClient.endpoint(c));
          GeminiDirectClient.validateHttpsUrl(url);
          JSONObject payload = new JSONObject().put("draft", draft == null ? "" : draft.trim());
          if (!inst.isEmpty()) payload.put("instruction", inst);
          if (imgBase64 != null) {
            payload.put("imageBase64", imgBase64);
            payload.put("imageMimeType", "image/jpeg");
          }
          byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
          HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
          try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(28000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + proxyToken);
            try (OutputStream out = conn.getOutputStream()) {
              out.write(body);
            }
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String resp = stream == null ? "" : readBounded(stream, 256 * 1024);
            if (code < 200 || code >= 300) {
              throw new IOException("HTTP " + code);
            }
            List<Option> options = parseResponse(resp);
            boolean usedWeb = parseSearched(resp);
            if (options.isEmpty()) {
              throw new IOException("No rewrite options returned.");
            }
            main.post(() -> cb.onOptions(options, hasScreen, usedWeb));
          } finally {
            conn.disconnect();
          }
        } catch (Exception | OutOfMemoryError e) {
          main.post(() -> cb.onError("Couldn’t rewrite right now. Check connection and API key."));
        } finally {
          if (screen != null && !screen.isRecycled()) {
            try { screen.recycle(); } catch (RuntimeException ignored) {}
          }
        }
      });
    } catch (RejectedExecutionException busy) {
      if (screen != null && !screen.isRecycled()) {
        try { screen.recycle(); } catch (RuntimeException ignored) {}
      }
      cb.onError("Already rewriting — one moment.");
    }
  }

  private static String readBounded(InputStream in, int maxBytes) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int total = 0, n;
    while ((n = in.read(buf)) > 0) {
      int take = Math.min(n, maxBytes - total);
      out.write(buf, 0, take);
      total += take;
      if (total >= maxBytes) break;
    }
    return out.toString(StandardCharsets.UTF_8);
  }
}
