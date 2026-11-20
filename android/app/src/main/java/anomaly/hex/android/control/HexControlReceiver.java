package anomaly.hex.android.control;
import android.app.*;
import android.content.*;
import anomaly.hex.android.core.*;
import anomaly.hex.android.recovery.Drafts;
import anomaly.hex.android.service.HexBubbleService;
import anomaly.hex.android.speech.ModelStore;
import anomaly.hex.android.ui.Placement;
import org.json.*;

/**
 * Manifest requires android.permission.DUMP. No transcript, clipboard, editor text or PCM is
 * exported.
 */
public final class HexControlReceiver extends BroadcastReceiver {
  private static final String OPS = "describe,status,start,stop,cancel,settings,labs,vocabulary,"
                                    + "history,drafts,moveMic,reviewCleanup,closeCleanup";
  @Override
  public void onReceive(Context c, Intent i) {
    if (!isOrderedBroadcast())
      return;
    String op = i.getStringExtra("op");
    if (op == null)
      op = "status";
    JSONObject o = new JSONObject();
    String result = "ok";
    try {
      HexBubbleService b = HexBubbleService.active;
      HexSettings s = new HexSettings(c);
      HexLabs labs = new HexLabs(c);
      boolean locked = c.getSystemService(KeyguardManager.class).isKeyguardLocked();
      if (locked && !op.equals("describe") && !op.equals("status"))
        result = "locked";
      else
        switch (op) {
          case "describe":
            o.put("operations", OPS)
                .put("schemaVersion", 1)
                .put("coordinates", "x,y: integer 0..1000")
                .put("guards",
                    "start: target; stop/cancel: target and session; settings/labs writes: "
                    + "expectedRevision; moveMic: expected position rev; "
                    + "reviewCleanup/closeCleanup: passage");
            break;
          case "status":
            if (b != null && !locked)
              o = b.status();
            else
              o.put("state", locked ? "locked" : "service_disabled")
                  .put("target", "")
                  .put("session", "");
            o.put("model", ModelStore.inspect(c).state.name())
                .put("connected", b != null)
                .put("locked", locked);
            if (!o.has("position"))
              o.put("position", new Placement(c).info());
            break;
          case "start":
          case "stop":
          case "cancel":
            if (b == null)
              result = "service_disabled";
            else if (b.targetId().isEmpty() || !b.targetId().equals(i.getStringExtra("target")))
              result = "stale_target";
            else if (op.equals("start"))
              result = b.start();
            else if (b.sessionId().isEmpty() || !b.sessionId().equals(i.getStringExtra("session")))
              result = "stale_session";
            else if (op.equals("stop"))
              result = b.stop();
            else
              b.cancel("cancelled");
            if (b != null)
              o.put("target", b.targetId()).put("session", b.sessionId());
            break;
          case "settings":
            result = settings(i, s);
            o.put("settings", s.toJson());
            break;
          case "labs":
            if (i.hasExtra("id") || i.hasExtra("enabled")) {
              LabId lab = LabId.fromId(i.getStringExtra("id"));
              if (lab == null || !i.hasExtra("enabled"))
                result = "invalid_lab";
              else if (!labs.setEnabled(lab, i.getBooleanExtra("enabled", false), revision(i)))
                result = "stale_revision";
              else {
                HexBubbleService.refreshLabs();
                if (lab == LabId.RECOVERY_DRAFTS && !labs.isEnabled(lab))
                  new Drafts(c).purgeAll();
              }
            }
            o.put("labs", labs.toJson());
            break;
          case "vocabulary":
            CustomVocabulary vocab = new CustomVocabulary(c);
            if (i.hasExtra("add") && !vocab.addTerm(i.getStringExtra("add")))
              result = "invalid_term";
            else if (i.hasExtra("remove") && !vocab.removeTerm(i.getStringExtra("remove")))
              result = "term_not_found";
            o.put("count", vocab.terms().size());
            break;
          case "history":
            HexHistory h = new HexHistory(c);
            h.pruneExpired(s.historyRetention());
            JSONArray items = new JSONArray();
            for (HexHistory.Entry e :
                h.list(i.getStringExtra("query") == null ? "" : i.getStringExtra("query"),
                    Math.min(100, Math.max(1, i.getIntExtra("limit", 20)))))
              items.put(new JSONObject()
                      .put("id", e.id)
                      .put("style", e.style)
                      .put("durationMs", e.durationMs)
                      .put("createdAtMs", e.createdAtMs));
            o.put("count", h.count()).put("history", items);
            break;
          case "drafts":
            if (!labs.isEnabled(LabId.RECOVERY_DRAFTS))
              result = "lab_disabled";
            else {
              Drafts d = new Drafts(c);
              d.purgeExpired();
              o.put("drafts", d.listMetadataJson());
            }
            break;
          case "moveMic":
            Placement pos = b == null ? new Placement(c) : b.placement;
            result = pos.set(
                i.getStringExtra("expected"), i.getIntExtra("x", -1), i.getIntExtra("y", -1));
            if (b != null && result.equals("ok"))
              b.positionStrip();
            o.put("position", pos.info());
            break;
          case "reviewCleanup":
          case "closeCleanup":
            result = b == null               ? "service_disabled"
                : op.equals("reviewCleanup") ? b.reviewCleanup(i.getStringExtra("passage"))
                                             : b.closeCleanup(i.getStringExtra("passage"));
            break;
          default:
            result = "unknown_operation";
        }
    } catch (Exception e) {
      result = "invalid_request";
    }
    try {
      o.put("result", result);
    } catch (JSONException ignored) {
    }
    setResult(
        result.equals("ok") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, o.toString(), null);
  }
  private static long revision(Intent i) {
    Object n = i.getExtras() == null ? null : i.getExtras().get("expectedRevision");
    return n instanceof Number ? ((Number) n).longValue() : -1;
  }
  private static String settings(Intent i, HexSettings s) {
    if (!i.hasExtra("style") && !i.hasExtra("retention") && !i.hasExtra("haptics"))
      return "ok";
    if (revision(i) != s.revision())
      return "stale_revision";
    String style = i.getStringExtra("style"), retention = i.getStringExtra("retention");
    TranscriptStyle st = TranscriptStyle.fromId(style);
    HistoryRetention hr = HistoryRetention.fromId(retention);
    if (style != null && !style.equals(st.id()) || retention != null && !retention.equals(hr.id()))
      return "invalid_setting";
    if (style != null)
      s.setStyle(st);
    if (retention != null)
      s.setHistoryRetention(hr);
    if (i.hasExtra("haptics"))
      s.setHapticsEnabled(i.getBooleanExtra("haptics", false));
    return "ok";
  }
}
