package anomaly.hex.android.core;

public enum HistoryRetention {
    OFF(0), DAYS_7(7), DAYS_30(30);
    private final int days;
    HistoryRetention(int days) { this.days = days; }
    public String id() { return days == 0 ? "off" : days + "d"; }
    public String label() { return days == 0 ? "Off" : days + " days"; }
    public long millis() { return days * 86_400_000L; }
    public static HistoryRetention fromId(String id) {
        for (HistoryRetention r : values()) if (r.id().equals(id)) return r;
        return DAYS_7;
    }
}
