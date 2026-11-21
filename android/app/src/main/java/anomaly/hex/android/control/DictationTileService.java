package anomaly.hex.android.control;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;
import anomaly.hex.android.service.HexBubbleService;

public final class DictationTileService extends TileService {
  @Override
  public void onClick() {
    super.onClick();
    if (isLocked()) {
      unlockAndRun(this::toggle);
      return;
    }
    toggle();
  }
  private void toggle() {
    HexBubbleService h = HexBubbleService.active;
    if (h != null && h.isBusy()) {
      h.stop();
      return;
    }
    Intent i = new Intent(this, CopyDictationActivity.class)
                   .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    if (Build.VERSION.SDK_INT >= 34)
      startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
    else
      startActivityAndCollapse(i);
  }
}
