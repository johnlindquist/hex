package anomaly.hex.android.keyboard;

import java.util.ArrayList;
import java.util.List;

/** Immutable dp geometry, measured from the top of the suggestion strip. */
public final class KeyboardLayout {
    public enum Layer { LETTERS, NUMBERS, SYMBOLS }
    public enum FieldKind { TEXT, URL, EMAIL }
    public enum KeyKind { CHAR, SHIFT, LAYER, DELETE, SPACE, RETURN, GLOBE, EMOJI }

    /** Numeric/phone/datetime fields open on NUMBERS; all other fields open on LETTERS. */
    public static Layer initialLayerForInputClass(int inputClass) {
        return (inputClass == 2 || inputClass == 3 || inputClass == 4) ? Layer.NUMBERS : Layer.LETTERS;
    }
    public record Key(KeyKind kind, int row, String label, String text,
                      float left, float top, float right, float bottom,
                      float hitLeft, float hitTop, float hitRight, float hitBottom) {
        public boolean hitTest(float x, float y) {
            return hitLeft <= x && x < hitRight && hitTop <= y && y < hitBottom;
        }
    }
    private record Face(KeyKind kind, String label, float left, float width) {}
    private final List<Key> keys;
    private final boolean landscape;
    public float stripHeight() { return landscape ? 42 : 50; }
    public float keyHeight() { return landscape ? 30 : 47; }
    public float horizontalGap() { return 5.7f; }
    public float verticalGap() { return landscape ? 8 : 12; }
    public float cornerRadius() { return 6.0f; }
    public float bottomInset() { return landscape ? 4 : 10; }
    public float totalHeight() { return stripHeight() + 4 * keyHeight() + 3 * verticalGap() + bottomInset(); }
    public List<Key> keys() { return keys; }
    public Key hitTest(float x, float y) {
        for (Key key : keys) if (key.hitTest(x, y)) return key;
        return null;
    }

    public Key keyForChar(char lower) {
        char target = Character.toLowerCase(lower);
        for (Key key : keys) {
            if (key.kind() == KeyKind.CHAR && key.label() != null && key.label().length() == 1
                    && Character.toLowerCase(key.label().charAt(0)) == target) {
                return key;
            }
        }
        return null;
    }

    /**
     * Hit-test that applies learned per-key thumb offsets to letter keys while keeping
     * non-character keys (Space, Delete, Shift, Return, etc.) on exact geometric bounds.
     */
    public Key hitTestAdaptive(float x, float y, PersonalAutocorrectStore store) {
        Key geom = hitTest(x, y);
        if (geom == null || geom.kind() != KeyKind.CHAR || store == null
                || geom.label() == null || geom.label().length() != 1) {
            return geom;
        }
        Key best = geom;
        float bestDistSq = Float.MAX_VALUE;
        float geomDistSq = Float.MAX_VALUE;

        for (Key key : keys) {
            if (key.kind() != KeyKind.CHAR || key.label() == null || key.label().length() != 1) continue;
            float kw = Math.max(1f, key.right() - key.left());
            float kh = Math.max(1f, key.bottom() - key.top());
            float cx = (key.left() + key.right()) * 0.5f;
            float cy = (key.top() + key.bottom()) * 0.5f;
            // Only consider the geometric hit key and its immediate neighbors.
            if (Math.abs(x - cx) > kw * 1.15f || Math.abs(y - cy) > kh * 1.15f) continue;

            float[] off = store.thumbOffset(key.label().charAt(0));
            float acx = cx + (off != null ? off[0] : 0f);
            float acy = cy + (off != null ? off[1] : 0f);
            float nx = (x - acx) / kw;
            float ny = (y - acy) / kh;
            float dSq = nx * nx + ny * ny;
            if (key == geom) geomDistSq = dSq;
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = key;
            }
        }
        if (best != geom && bestDistSq < 0.72f && bestDistSq + 0.04f < geomDistSq) {
            return best;
        }
        return geom;
    }

    public KeyboardLayout(float width, boolean landscape, Layer layer, FieldKind field,
                          boolean globe, boolean emoji, KeyboardLanguage language) {
        this.landscape = landscape;
        List<String> letters = switch (layer) {
            case LETTERS -> language.letterRows();
            case NUMBERS -> List.of("1234567890", "@#$_&-+()/", "*\"':;!?");
            case SYMBOLS -> List.of("~`|•√π÷×§Δ", "£¢€¥^°={}\\", "%©®™✓[]");
        };
        float side = landscape ? 4 : 4.5f;
        float gap = horizontalGap(), inner = width - 2 * side;
        float ref = (inner - 9 * gap) / 10, mod = 1.5f * ref + 0.5f * gap;
        int cols = Math.max(10, Math.max(letters.get(0).length(), letters.get(1).length()));
        float character = (inner - (cols - 1) * gap) / cols;
        List<Key> result = new ArrayList<>();
        for (int row = 0; row < 4; row++) {
            List<Face> faces = new ArrayList<>();
            if (row < 3) {
                String chars = letters.get(row);
                float w = character;
                if (row == 2) {
                    faces.add(new Face(layer == Layer.LETTERS ? KeyKind.SHIFT : KeyKind.LAYER,
                            layer == Layer.LETTERS ? "shift" : layer == Layer.NUMBERS ? "=\\<" : "?123", side, mod));
                }
                float x = (width - chars.length() * w - (chars.length() - 1) * gap) / 2;
                for (int i = 0; i < chars.length(); i++, x += w + gap)
                    faces.add(new Face(KeyKind.CHAR, chars.substring(i, i + 1), x, w));
                if (row == 2) faces.add(new Face(KeyKind.DELETE, "delete", width - side - mod, mod));
            } else {
                faces.add(new Face(KeyKind.LAYER, layer == Layer.LETTERS ? "?123" : "ABC", side, mod));
                float x = side + mod + gap;
                String leftPunct = layer == Layer.SYMBOLS ? "<" : field == FieldKind.EMAIL ? "@" : field == FieldKind.URL ? "/" : ",";
                faces.add(new Face(KeyKind.CHAR, leftPunct, x, ref)); x += ref + gap;
                if (globe) { faces.add(new Face(KeyKind.GLOBE, "globe", x, ref)); x += ref + gap; }
                if (emoji) { faces.add(new Face(KeyKind.EMOJI, "emoji", x, ref)); x += ref + gap; }
                float returnX = width - side - mod;
                float rightPunctX = returnX - gap - ref;
                faces.add(new Face(KeyKind.SPACE, "space", x, rightPunctX - gap - x));
                String rightPunct = layer == Layer.SYMBOLS ? ">" : ".";
                faces.add(new Face(KeyKind.CHAR, rightPunct, rightPunctX, ref));
                faces.add(new Face(KeyKind.RETURN, "return", returnX, mod));
            }
            float top = stripHeight() + row * (keyHeight() + verticalGap());
            float bottom = top + keyHeight();
            float hitLeft = 0;
            for (int i = 0; i < faces.size(); i++) {
                Face f = faces.get(i);
                float right = f.left + f.width;
                float hitRight = i + 1 == faces.size() ? width : (right + faces.get(i + 1).left) / 2;
                String text = switch (f.kind) { case CHAR -> f.label; case SPACE -> " "; case RETURN -> "\n"; default -> ""; };
                result.add(new Key(f.kind, row, f.label, text, f.left, top, right, bottom,
                        hitLeft, row == 0 ? top : top - verticalGap() / 2, hitRight,
                        row == 3 ? totalHeight() : bottom + verticalGap() / 2));
                hitLeft = hitRight;
            }
        }
        keys = List.copyOf(result);
    }
}
