package anomaly.hex.android.core;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;

public final class HexLabs {
    private static final Object LOCK = new Object();
    private final SharedPreferences p;
    public HexLabs(Context ctx) { p = ctx.getSharedPreferences("hex_labs", Context.MODE_PRIVATE); }
    public synchronized boolean isEnabled(LabId lab) { return p.getBoolean(lab.id, false); }
    public synchronized boolean isExplicitlySet(LabId lab) { return p.contains(lab.id); }
    public synchronized long revision() { return p.getLong("revision", 0); }
    public synchronized boolean setEnabled(LabId lab, boolean enabled, long expectedRevision) {
        synchronized (LOCK) {
            if (expectedRevision != revision()) return false;
            setEnabledUnchecked(lab, enabled);
            return true;
        }
    }
    public synchronized void setEnabledUnchecked(LabId lab, boolean enabled) {
        synchronized (LOCK) {
            if (isEnabled(lab) != enabled) p.edit().putBoolean(lab.id, enabled).putLong("revision", revision()+1).apply();
        }
    }
    public synchronized JSONObject toJson() {
        synchronized (LOCK) {
            try {
                JSONObject out = new JSONObject().put("revision", revision());
                for (LabId lab : LabId.values()) out.put(lab.id, isEnabled(lab));
                return out;
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }
}
