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
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class TypeAnywhereAiClient {
  public static final String DEFAULT_ENDPOINT = "https://hex-api-five.vercel.app/api/ask";

  public static final class Turn {
    public final String role;
    public final String text;
    public Turn(String role, String text) {
      this.role = role;
      this.text = text;
    }
  }

  public static final class Source {
    public final String title;
    public final String uri;
    public Source(String title, String uri) {
      this.title = title;
      this.uri = uri;
    }
  }

  public interface Callback {
    void onAnswer(String sourceLabel, String answer, List<Source> sources);
    void onError(String message);
  }

  private static final ExecutorService worker =
      new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>());

  public static String key(Context c) {
    return c.getSharedPreferences("hex_settings", 0).getString("type_anywhere_api_key", "").trim();
  }

  public static String endpoint(Context c) {
    String custom = c.getSharedPreferences("hex_settings", 0).getString("type_anywhere_endpoint", "").trim();
    return custom.isEmpty() ? DEFAULT_ENDPOINT : custom;
  }

  static void ask(Context context, Bitmap image, Bitmap secondImage, String text, String question,
      List<Turn> history, Callback cb) {
    Context c = context.getApplicationContext();
    Handler main = new Handler(Looper.getMainLooper());
    List<Turn> historyCopy = history == null ? Collections.emptyList() : new ArrayList<>(history);
    try {
      worker.execute(() -> {
        if (!new HexLabs(c).isEnabled(LabId.TYPE_ANYWHERE)) {
          main.post(() -> cb.onError("Type anywhere is off."));
          return;
        }
        String token = key(c), url = endpoint(c);
        String prompt = TypeAnywhereEngine.buildPrompt(text, question);
        File model = new File(c.getNoBackupFilesDir(), "gemma-4-E2B-it.litertlm");
        try {
          if (!token.isEmpty()) {
            ProxyResult res = askProxy(url, token, text, question, image, secondImage, historyCopy);
            main.post(() -> cb.onAnswer(res.sourceLabel, res.answer, res.sources));
          } else if (model.isFile()) {
            String answer = gemma(c, model, prompt);
            main.post(() -> cb.onAnswer("On-device Gemma", answer, Collections.emptyList()));
          } else {
            String answer = TypeAnywhereEngine.answerLocally(text, question);
            main.post(() -> cb.onAnswer("On-device screen analysis", answer, Collections.emptyList()));
          }
        } catch (Exception | LinkageError | OutOfMemoryError e) {
          if (token.isEmpty())
            main.post(() -> cb.onAnswer("On-device screen analysis",
                TypeAnywhereEngine.answerLocally(text, question), Collections.emptyList()));
          else
            main.post(() -> cb.onError(
                "Hex API couldn’t answer. Check your connection and proxy token, then try again."));
        }
      });
    } catch (RejectedExecutionException busy) {
      cb.onError("Another answer is in progress. Try again shortly.");
    }
  }

  private static final class ProxyResult {
    final String sourceLabel;
    final String answer;
    final List<Source> sources;
    ProxyResult(String sourceLabel, String answer, List<Source> sources) {
      this.sourceLabel = sourceLabel;
      this.answer = answer;
      this.sources = sources;
    }
  }

  private static String encodeJpeg(Bitmap bmp) throws IOException {
    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
    if (!bmp.compress(Bitmap.CompressFormat.JPEG, 82, jpeg))
      throw new IOException("JPEG encode failed");
    return Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP);
  }

  private static ProxyResult askProxy(String endpointUrl, String token, String screenText,
      String question, Bitmap image, Bitmap secondImage, List<Turn> history) throws Exception {
    JSONObject payload = new JSONObject()
        .put("question", question == null ? "" : question)
        .put("screenText", screenText == null ? "" : screenText);
    if (image != null) {
      payload.put("imageBase64", encodeJpeg(image));
      payload.put("imageMimeType", "image/jpeg");
    }
    if (secondImage != null) {
      payload.put("secondImageBase64", encodeJpeg(secondImage));
      payload.put("secondImageMimeType", "image/jpeg");
    }
    if (history != null && !history.isEmpty()) {
      JSONArray arr = new JSONArray();
      for (Turn t : history) {
        arr.put(new JSONObject().put("role", t.role).put("text", t.text));
      }
      payload.put("history", arr);
    }
    byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
    HttpURLConnection conn = (HttpURLConnection) new URL(endpointUrl).openConnection();
    try {
      conn.setConnectTimeout(15000);
      conn.setReadTimeout(28000);
      conn.setInstanceFollowRedirects(false);
      conn.setRequestMethod("POST");
      conn.setDoOutput(true);
      conn.setFixedLengthStreamingMode(body.length);
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setRequestProperty("Authorization", "Bearer " + token);
      try (OutputStream out = conn.getOutputStream()) {
        out.write(body);
      }
      int status = conn.getResponseCode();
      if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
      try (InputStream in = conn.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
          if (out.size() + n > 262144) throw new IOException("Response too large");
          out.write(buf, 0, n);
        }
        JSONObject json = new JSONObject(out.toString("UTF-8"));
        String answer = json.optString("answer", "").trim();
        String label = json.optString("sourceLabel", "Gemini 4 Hydrogen").trim();
        if (answer.isEmpty()) throw new IOException("Empty answer");
        List<Source> sources = new ArrayList<>();
        JSONArray srcArr = json.optJSONArray("sources");
        if (srcArr != null) {
          for (int i = 0; i < srcArr.length(); i++) {
            JSONObject s = srcArr.optJSONObject(i);
            if (s != null) {
              String uri = s.optString("uri", "").trim();
              String title = s.optString("title", uri).trim();
              if (!uri.isEmpty()) sources.add(new Source(title, uri));
            }
          }
        }
        return new ProxyResult(label.isEmpty() ? "Gemini 4 Hydrogen" : label, answer, sources);
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
