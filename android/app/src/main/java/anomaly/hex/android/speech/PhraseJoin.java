package anomaly.hex.android.speech;

import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import java.util.*;
import java.util.regex.*;

/**
 * Map model token times back to the original formatted text; only the core owns words. Audio
 * context on either side is for inference only. Never deduplicate across a real pause.
 */
final class PhraseJoin {
  static final class Word {
    int from, to;
    double time;
    String key;
  }

  final ArrayList<Word> tail = new ArrayList<>();

  String select(OfflineRecognizerResult result, long coreStart, int size, int left, int right) {
    String text = result.getText().trim();
    if (text.isEmpty()) {
      tail.clear();
      return "";
    }
    if (left == 0 && right == 0) {
      tail.clear();
      return text;
    }
    String[] tokens = result.getTokens();
    float[] times = result.getTimestamps(), dur = result.getDurations();
    if (tokens.length != times.length || times.length == 0) return null;
    double[] at = new double[text.length()], ends = new double[text.length()];
    int pos = 0;
    double origin = (coreStart - left) / 16000.;
    for (int t = 0; t < tokens.length; t++) {
      String piece = tokens[t].replace('▁', ' ');
      if (piece.startsWith("<|") && piece.endsWith("|>")) continue;
      for (int k = 0; k < piece.length(); k++) {
        char ch = piece.charAt(k);
        if (Character.isWhitespace(ch)) continue;
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
        if (pos == text.length()
            || Character.toLowerCase(text.charAt(pos)) != Character.toLowerCase(ch)) return null;
        at[pos] = origin + times[t];
        ends[pos++] =
            origin
                + times[t]
                + (dur.length == times.length ? Math.min(.6, Math.max(.01, dur[t])) : .06);
      }
    }
    if (pos != text.length()) return null;
    ArrayList<Word> words = new ArrayList<>();
    Matcher m = Pattern.compile("\\S+").matcher(text);
    while (m.find()) {
      Word w = new Word();
      w.from = m.start();
      w.to = m.end();
      w.time = (at[w.from] + ends[w.to - 1]) * .5;
      w.key = m.group().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
      words.add(w);
    }
    double begin = coreStart / 16000., end = (coreStart + size) / 16000.;
    int a = 0, b = words.size();
    if (left > 0) {
      while (a < b && words.get(a).time < begin) a++;
      // Reconcile only hypotheses about the SAME sound. Keep intentional repeated words.
      int best = 0, index = -1;
      double distance = 1;
      for (int i = 0; i < b && !tail.isEmpty(); i++) {
        Word w = words.get(i);
        double dt = Math.abs(w.time - tail.get(tail.size() - 1).time);
        if (w.time > begin + .4) break;
        if (dt > .30 || w.key.isEmpty()) continue;
        int count = 0;
        while (i - count >= 0
            && tail.size() > count
            && words.get(i - count).key.equals(tail.get(tail.size() - 1 - count).key)) count++;
        if (count > 0 && (count > best || count == best && dt < distance)) {
          index = i;
          best = count;
          distance = dt;
        }
      }
      if (index >= 0) a = index + 1;
    }
    if (right > 0) {
      while (b > a && words.get(b - 1).time >= end) b--;
    }
    tail.clear();
    if (a >= b) return "";
    if (right > 0) for (int i = Math.max(a, b - 5); i < b; i++) tail.add(words.get(i));
    return text.substring(words.get(a).from, words.get(b - 1).to);
  }
}
