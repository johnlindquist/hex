package anomaly.hex.android.speech;

import android.content.Context;
import android.media.*;
import java.util.List;

/** Owns exactly one Android recording stream. Capture and recovery use the delivered PCM clock. */
public final class Microphone implements SpeechSession.Input {
  volatile boolean interrupted;
  final AudioRecord rec;
  final AudioManager.AudioRecordingCallback cb;

  public Microphone(Context ctx) {
    int rate = SpeechSession.RATE;
    int min =
        AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    rec =
        new AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            Math.max(64000, min));
    int sid = rec.getAudioSessionId();
    cb =
        new AudioManager.AudioRecordingCallback() {
          public void onRecordingConfigChanged(List<AudioRecordingConfiguration> list) {
            for (AudioRecordingConfiguration c : list)
              if (c.getClientAudioSessionId() == sid && c.isClientSilenced()) interrupted = true;
          }
        };
    try {
      rec.registerAudioRecordingCallback(ctx.getMainExecutor(), cb);
      rec.startRecording();
      if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
        throw new IllegalStateException();
    } catch (RuntimeException e) {
      close();
      throw e;
    }
  }

  public int read(short[] block, int offset, int len) {
    int n = rec.read(block, offset, len);
    if (n <= 0) throw new IllegalStateException();
    return n;
  }

  public boolean isSilenced() {
    AudioRecordingConfiguration c = rec.getActiveRecordingConfiguration();
    return interrupted || c != null && c.isClientSilenced();
  }

  public void close() {
    try {
      rec.unregisterAudioRecordingCallback(cb);
    } catch (RuntimeException ignored) {
    }
    try {
      rec.stop();
    } catch (RuntimeException ignored) {
    }
    rec.release();
  }
}
