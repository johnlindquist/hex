package anomaly.hex.android.core;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;

public final class HexSettings {
    public static final int ANYWHERE_SIZE_MIN_DP = 20;
    public static final int ANYWHERE_SIZE_MAX_DP = 56;
    public static final int ANYWHERE_SIZE_DEFAULT_DP = 28;
    private static final Object LOCK = new Object();
    private final SharedPreferences p;
    public HexSettings(Context ctx) { p = ctx.getSharedPreferences("hex_settings", Context.MODE_PRIVATE); }
    public synchronized TranscriptStyle style() { return TranscriptStyle.fromId(p.getString("style", null)); }
    public synchronized void setStyle(TranscriptStyle value) {
        synchronized (LOCK) {
            if (style() != value) p.edit().putString("style", value.id()).putLong("revision", revision()+1).apply();
        }
    }
    public synchronized HistoryRetention historyRetention() { return HistoryRetention.fromId(p.getString("historyRetention", null)); }
    public synchronized void setHistoryRetention(HistoryRetention value) {
        synchronized (LOCK) {
            if (historyRetention() != value) p.edit().putString("historyRetention", value.id()).putLong("revision", revision()+1).apply();
        }
    }
    public synchronized boolean onboardingCompleted() { return p.getBoolean("onboardingCompleted", false); }
    public synchronized void setOnboardingCompleted(boolean value) {
        synchronized (LOCK) {
            if (onboardingCompleted() != value) p.edit().putBoolean("onboardingCompleted", value).putLong("revision", revision()+1).apply();
        }
    }
    public synchronized int onboardingPage() { return p.getInt("onboardingPage", 0); }
    public synchronized void setOnboardingPage(int value) {
        synchronized (LOCK) {
            if (onboardingPage() != Math.max(0, value)) p.edit().putInt("onboardingPage", Math.max(0, value)).putLong("revision", revision()+1).apply();
        }
    }
    public synchronized boolean hapticsEnabled() { return p.getBoolean("hapticsEnabled", true); }
    public synchronized void setHapticsEnabled(boolean value) {
        synchronized (LOCK) {
            if (hapticsEnabled() != value) p.edit().putBoolean("hapticsEnabled", value).putLong("revision", revision()+1).apply();
        }
    }
    public synchronized boolean preferBuiltInMic() { return p.getBoolean("preferBuiltInMic", false); }
    public synchronized void setPreferBuiltInMic(boolean value) {
        synchronized (LOCK) {
            if (preferBuiltInMic() != value) p.edit().putBoolean("preferBuiltInMic", value).putLong("revision", revision()+1).apply();
        }
    }
    public synchronized boolean numbersAsDigits() { return p.getBoolean("numbersAsDigits", true); }
    public synchronized void setNumbersAsDigits(boolean value) {
        synchronized (LOCK) {
            if (numbersAsDigits() != value) p.edit().putBoolean("numbersAsDigits", value).putLong("revision", revision()+1).apply();
        }
    }
    public synchronized String signatureText() {
        return p.getString("signatureText", DictationSignature.DEFAULT_TEXT);
    }
    public synchronized void setSignatureText(String value) {
        String next = value == null ? "" : value.trim();
        synchronized (LOCK) {
            if (!signatureText().equals(next)) p.edit().putString("signatureText", next).putLong("revision", revision()+1).apply();
        }
    }
    public synchronized int anywhereButtonSizeDp() {
        return clampAnywhereSize(p.getInt("anywhereButtonSizeDp", ANYWHERE_SIZE_DEFAULT_DP));
    }
    public synchronized void setAnywhereButtonSizeDp(int value) {
        int clamped = clampAnywhereSize(value);
        synchronized (LOCK) {
            if (anywhereButtonSizeDp() != clamped)
                p.edit().putInt("anywhereButtonSizeDp", clamped).putLong("revision", revision() + 1).apply();
        }
    }
    public static int clampAnywhereSize(int value) {
        return Math.max(ANYWHERE_SIZE_MIN_DP, Math.min(ANYWHERE_SIZE_MAX_DP, value));
    }
    public synchronized long revision() { return p.getLong("revision", 0); }
    public synchronized JSONObject toJson() {
        synchronized (LOCK) {
            try {
                return new JSONObject().put("revision", revision())
                    .put("style", style().id())
                    .put("historyRetention", historyRetention().id())
                    .put("onboardingCompleted", onboardingCompleted())
                    .put("onboardingPage", onboardingPage())
                    .put("hapticsEnabled", hapticsEnabled())
                    .put("preferBuiltInMic", preferBuiltInMic())
                    .put("anywhereButtonSizeDp", anywhereButtonSizeDp());
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }
}
