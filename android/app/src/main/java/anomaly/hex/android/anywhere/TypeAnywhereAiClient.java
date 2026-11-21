package anomaly.hex.android.anywhere;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import anomaly.hex.android.core.*;
import com.google.ai.edge.litertlm.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.*;
import org.json.*;

public final class TypeAnywhereAiClient {
  public interface Callback {
    void onAnswer(String sourceLabel, String answer);
    void onError(String message);
  }
  private static final ExecutorService worker =
      new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>());
  static String key(Context c) {
    return c.getSharedPreferences("hex_settings", 0).getString("type_anywhere_api_key", "").trim();
  }
  static void ask(Context context, Bitmap image, String text, String question, Callback cb) {
    Context c = context.getApplicationContext();
    Handler main = new Handler(Looper.getMainLooper());
    try {
      worker.execute(() -> {
        if (!new HexLabs(c).isEnabled(LabId.TYPE_ANYWHERE)) {
          main.post(() -> cb.onError("Type anywhere is off."));
          return;
        }
        String key = key(c), prompt = TypeAnywhereEngine.buildPrompt(text, question);
        File model = new File(c.getNoBackupFilesDir(), "gemma-4-E2B-it.litertlm");
        String label, answer;
        try {
          if (!key.isEmpty()) {
            label = "Gemini";
            answer = gemini(key, prompt, image);
          } else if (model.isFile()) {
            label = "On-device Gemma";
            answer = gemma(c, model, prompt);
          } else {
            label = "On-device screen analysis";
            answer = TypeAnywhereEngine.answerLocally(text, question);
          }
          main.post(() -> cb.onAnswer(label, answer));
        } catch (Exception | LinkageError | OutOfMemoryError e) {
          if (key.isEmpty())
            main.post(()
                          -> cb.onAnswer("On-device screen analysis",
                              TypeAnywhereEngine.answerLocally(text, question)));
          else
            main.post(()
                          -> cb.onError("Gemini couldn’t answer. Check your connection and API "
                                        + "key, then try again."));
        }
      });
    } catch (RejectedExecutionException busy) {
      cb.onError("Another answer is in progress. Try again shortly.");
    }
  }
  private static String gemini(String key, String prompt, Bitmap image) throws Exception {
    JSONArray parts = new JSONArray().put(new JSONObject().put("text", prompt));
    if (image != null) {
      ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
      if (!image.compress(Bitmap.CompressFormat.JPEG, 85, jpeg))
        throw new IOException();
      parts.put(new JSONObject().put("inline_data",
          new JSONObject()
              .put("mime_type", "image/jpeg")
              .put("data", Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP))));
    }
    byte[] body = new JSONObject()
                      .put("contents", new JSONArray().put(new JSONObject().put("parts", parts)))
                      .put("generationConfig", new JSONObject().put("maxOutputTokens", 1536))
                      .toString()
                      .getBytes(StandardCharsets.UTF_8);
    HttpURLConnection conn = (HttpURLConnection) new URL(
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent")
                                 .openConnection();
    try {
      conn.setConnectTimeout(15000);
      conn.setReadTimeout(15000);
      conn.setInstanceFollowRedirects(false);
      conn.setRequestMethod("POST");
      conn.setDoOutput(true);
      conn.setFixedLengthStreamingMode(body.length);
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setRequestProperty("x-goog-api-key", key);
      try (OutputStream out = conn.getOutputStream()) {
        out.write(body);
      }
      try (InputStream in = conn.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
          if (out.size() + n > 262144)
            throw new IOException();
          out.write(buf, 0, n);
        }
        String s = TypeAnywhereEngine.parseGeminiResponse(out.toString("UTF-8"));
        if (s.isEmpty())
          throw new IOException();
        return s;
      }
    } finally {
      conn.disconnect();
    }
  }
  private static String gemma(Context c, File model, String prompt) throws Exception {
    Engine.Companion.setNativeMinLogSeverity(LogSeverity.INFINITY);
    EngineConfig config = new EngineConfig(model.getPath(), new Backend.CPU(4, null), null, null,
        4096, null, c.getCacheDir().getPath());
    try (Engine engine = new Engine(config)) {
      engine.initialize();
      ConversationConfig cc = new ConversationConfig(
          Contents.Companion.of("Answer questions using the provided screen evidence."),
          Collections.emptyList(), Collections.emptyList(), new SamplerConfig(1, 1.0, 0.0, 23),
          false);
      try (Conversation cv = engine.createConversation(cc)) {
        Message answer = cv.sendMessage(
            prompt, Collections.emptyMap(), null, null, null, 512, new ThinkingConfig(false), null);
        StringBuilder out = new StringBuilder();
        for (Content x : answer.getContents().getContents())
          if (x instanceof Content.Text)
            out.append(((Content.Text) x).getText());
        if (out.toString().isBlank())
          throw new IOException();
        return out.toString().trim();
      }
    }
  }
}
