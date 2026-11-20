package anomaly.hex.android.core;

public enum TranscriptStyle {
    POLISHED("Polished"), CASUAL("Casual"), NOTES("Notes"), VERBATIM("Verbatim");
    private final String label;
    TranscriptStyle(String label) { this.label = label; }
    public String label() { return label; }
    public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
    public static TranscriptStyle fromId(String id) {
        for (TranscriptStyle s : values()) if (s.id().equals(id)) return s;
        return POLISHED;
    }
}
