package anomaly.hex.android.control;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import java.util.UUID;

/** Ephemeral action addresses: a delayed tap can never act on a later take. */
public final class DictationNotification {
  private final Context ctx;
  private final int id;
  private final String action = "anomaly.hex.dictation." + UUID.randomUUID();
  private final BroadcastReceiver receiver;
  private boolean registered;
  public DictationNotification(Context ctx, int id, Runnable finish, Runnable cancel) {
    this.ctx = ctx;
    this.id = id;
    receiver = new BroadcastReceiver() {
      public void onReceive(Context c, Intent i) {
        if (!registered || !action.equals(i.getAction()))
          return;
        if (i.getBooleanExtra("finish", false))
          finish.run();
        else
          cancel.run();
      }
    };
  }
  private PendingIntent button(boolean finish) {
    Intent i = new Intent(action).setPackage(ctx.getPackageName()).putExtra("finish", finish);
    return PendingIntent.getBroadcast(ctx, finish ? 1 : 2, i, PendingIntent.FLAG_IMMUTABLE);
  }
  public void show(boolean listening) {
    if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED)
      return;
    try {
      if (!registered) {
        ctx.registerReceiver(receiver, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
        registered = true;
      }
      NotificationManager nm = ctx.getSystemService(NotificationManager.class);
      nm.createNotificationChannel(
          new NotificationChannel("dictation", "Dictation", NotificationManager.IMPORTANCE_LOW));
      Notification.Builder n =
          new Notification.Builder(ctx, "dictation")
              .setSmallIcon(android.R.drawable.ic_btn_speak_now)
              .setContentTitle(listening ? "Hex is listening" : "Hex is transcribing")
              .setOnlyAlertOnce(true)
              .setOngoing(true)
              .setTimeoutAfter(86400000L);
      if (listening)
        n.addAction(0, "Finish", button(true));
      nm.notify(id, n.addAction(0, "Cancel", button(false)).build());
    } catch (SecurityException ignored) {
    }
  }
  public void dismiss() {
    if (registered) {
      registered = false;
      ctx.unregisterReceiver(receiver);
    }
    ctx.getSystemService(NotificationManager.class).cancel(id);
  }
}
