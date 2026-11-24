package anomaly.hex.android.keyboard;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Opt-in local NDJSON typing telemetry log (Labs › Typing telemetry log).
 * Records keystrokes, tap (x, y) coordinates vs key center, corrections, undos, and tail fixes
 * strictly on non-password / non-incognito fields, with a 4 MB rolling cap and a single daemon
 * writer thread so typing latency is never affected.
 */
public final class TypingTelemetryLog {
    public static final String FILE_NAME = "hex-typing-telemetry.ndjson";
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final long TRIM_TARGET_BYTES = 2L * 1024 * 1024;
    private static volatile TypingTelemetryLog instance;

    public record Stats(long bytes, int events, int corrections, int undos) {}

    private final File file;
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(512);
    private volatile boolean running;

    private TypingTelemetryLog(File file) {
        this.file = file;
        startWriter();
    }

    /** For unit tests with a temp file. */
    static TypingTelemetryLog forFile(File file) {
        return new TypingTelemetryLog(file);
    }

    public static TypingTelemetryLog get(Context ctx) {
        TypingTelemetryLog cur = instance;
        if (cur != null) return cur;
        synchronized (TypingTelemetryLog.class) {
            if (instance == null) {
                File f = new File(ctx.getApplicationContext().getNoBackupFilesDir(), FILE_NAME);
                instance = new TypingTelemetryLog(f);
            }
            return instance;
        }
    }

    public File file() { return file; }

    public void logTap(String keyLabel, String kind, float tapXdp, float tapYdp,
                       float keyCenterXdp, float keyCenterYdp, String layer, float kbWidthDp) {
        logTap(keyLabel, kind, tapXdp, tapYdp, keyCenterXdp, keyCenterYdp, null, layer, kbWidthDp);
    }

    public void logTap(String keyLabel, String kind, float tapXdp, float tapYdp,
                       float keyCenterXdp, float keyCenterYdp, String rawKeyLabel, String layer, float kbWidthDp) {
        try {
            JSONObject o = base("tap")
                    .put("key", keyLabel == null ? "" : keyLabel)
                    .put("kind", kind == null ? "" : kind)
                    .put("x", round1(tapXdp))
                    .put("y", round1(tapYdp))
                    .put("kx", round1(keyCenterXdp))
                    .put("ky", round1(keyCenterYdp))
                    .put("dx", round1(tapXdp - keyCenterXdp))
                    .put("dy", round1(tapYdp - keyCenterYdp))
                    .put("layer", layer == null ? "" : layer)
                    .put("w", round1(kbWidthDp));
            if (rawKeyLabel != null && !rawKeyLabel.isEmpty()) o.put("rawKey", rawKeyLabel);
            enqueue(o);
        } catch (JSONException ignored) {}
    }

    public void logRetype(String erased, String retyped) {
        try {
            enqueue(base("retype")
                    .put("from", erased == null ? "" : erased)
                    .put("to", retyped == null ? "" : retyped));
        } catch (JSONException ignored) {}
    }

    public void logSpace(String rawWord, String fixedWord, String via) {
        try {
            JSONObject o = base("space")
                    .put("raw", rawWord == null ? "" : rawWord)
                    .put("via", via == null ? "clean" : via);
            if (fixedWord != null && !fixedWord.isEmpty()) o.put("fix", fixedWord);
            enqueue(o);
        } catch (JSONException ignored) {}
    }

    public void logBackspace(String currentWord, String undoneFix, String restoredRaw) {
        try {
            JSONObject o = base("backspace");
            if (undoneFix != null) {
                o.put("undid", undoneFix).put("restored", restoredRaw == null ? "" : restoredRaw);
            } else {
                o.put("word", currentWord == null ? "" : currentWord);
            }
            enqueue(o);
        } catch (JSONException ignored) {}
    }

    public void logSuggestionPick(String typed, String picked) {
        try {
            enqueue(base("pick")
                    .put("typed", typed == null ? "" : typed)
                    .put("picked", picked == null ? "" : picked));
        } catch (JSONException ignored) {}
    }

    public void logTailFix(String mode, String fromText, String toText) {
        try {
            enqueue(base("tail_fix")
                    .put("mode", mode == null ? "" : mode)
                    .put("from", fromText == null ? "" : fromText)
                    .put("to", toText == null ? "" : toText));
        } catch (JSONException ignored) {}
    }

    public void logSession(String pkg, boolean spatial, boolean twoThumb, String tailMode) {
        try {
            enqueue(base("session")
                    .put("pkg", pkg == null ? "" : pkg)
                    .put("spatial", spatial)
                    .put("twoThumb", twoThumb)
                    .put("tailMode", tailMode == null ? "off" : tailMode));
        } catch (JSONException ignored) {}
    }

    public synchronized void clear() {
        queue.clear();
        if (file.exists()) file.delete();
    }

    public synchronized Stats stats() {
        if (!file.isFile()) return new Stats(0, 0, 0, 0);
        long bytes = file.length();
        int events = 0, corrections = 0, undos = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                events++;
                if (line.contains("\"fix\":") || line.contains("\"ev\":\"tail_fix\"") || line.contains("\"ev\":\"pick\"")) {
                    corrections++;
                }
                if (line.contains("\"undid\":")) undos++;
            }
        } catch (IOException ignored) {}
        return new Stats(bytes, events, corrections, undos);
    }

    public static String formatStats(Stats s) {
        if (s == null || s.events() == 0) return "No events recorded yet";
        String size = s.bytes() < 1024
                ? s.bytes() + " B"
                : String.format(Locale.US, "%.1f KB", s.bytes() / 1024.0);
        return s.events() + " events · " + s.corrections() + " fixes · " + s.undos() + " undos · " + size;
    }

    /** Flushes queued lines synchronously (useful before export or in unit tests). */
    public void flushSync() {
        StringBuilder batch = new StringBuilder();
        String line;
        while ((line = queue.poll()) != null) {
            batch.append(line).append('\n');
        }
        if (batch.length() > 0) writeBatch(batch.toString());
    }

    private JSONObject base(String ev) throws JSONException {
        return new JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("ev", ev);
    }

    private static double round1(float v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private void enqueue(JSONObject obj) {
        queue.offer(obj.toString());
    }

    private void startWriter() {
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    String first = queue.take();
                    StringBuilder sb = new StringBuilder(first.length() + 256);
                    sb.append(first).append('\n');
                    String next;
                    while (sb.length() < 16384 && (next = queue.poll()) != null) {
                        sb.append(next).append('\n');
                    }
                    writeBatch(sb.toString());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "hex-telemetry-log");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void writeBatch(String text) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (file.length() > MAX_BYTES) trimInPlace();
        } catch (IOException ignored) {}
    }

    private void trimInPlace() {
        try {
            long len = file.length();
            if (len <= TRIM_TARGET_BYTES) return;
            long skip = len - TRIM_TARGET_BYTES;
            byte[] keep;
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(skip);
                // Advance to next newline so we keep valid NDJSON lines
                int b;
                while ((b = raf.read()) != -1 && b != '\n') {}
                long start = raf.getFilePointer();
                int remaining = (int) Math.max(0, len - start);
                keep = new byte[remaining];
                raf.readFully(keep);
            }
            File tmp = new File(file.getParentFile(), FILE_NAME + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(keep);
            }
            tmp.renameTo(file);
        } catch (IOException ignored) {}
    }
}
