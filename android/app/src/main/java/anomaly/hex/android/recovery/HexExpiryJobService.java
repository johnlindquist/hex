package anomaly.hex.android.recovery;
import android.app.job.*;
public final class HexExpiryJobService extends JobService {
    @Override public boolean onStartJob(JobParameters p){new Thread(()->{boolean retry=false;try(Drafts drafts=new Drafts(this)){drafts.purgeExpired();}catch(RuntimeException e){retry=true;}jobFinished(p,retry);},"draft-expiry").start();return true;}
    @Override public boolean onStopJob(JobParameters p){return true;}
}
