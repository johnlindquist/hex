package anomaly.hex.android.cleanup;

import android.content.*;
import android.os.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;

/** One ephemeral receipt. Produces a preview; never writes to an editor. */
public final class Cleanup {
    public static final class Passage {
        public final String id, original, packageName;
        public final int start, end;
        private Passage(String text,String pkg,int a,int z) {
            id=UUID.randomUUID().toString(); original=text; packageName=pkg; start=a; end=z;
        }
    }
    private final Context ctx;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Passage passage;
    private Request active;
    public Cleanup(Context ctx) { this.ctx=ctx.getApplicationContext(); }
    public synchronized Passage recordConfirmed(String text,String packageName,int start,int end) {
        invalidate();
        if(text!=null && !text.isBlank() && text.length()<=HexPolisherService.LIMIT && packageName!=null && !packageName.isEmpty()
            && start>=0 && end>=start && end-start==text.length()) passage=new Passage(text,packageName,start,end);
        return passage;
    }
    public synchronized Passage current() { return passage; }
    public synchronized void invalidate() { passage=null; if(active!=null) active.release(); }
    public synchronized boolean isModelInstalled() { return HexPolisherService.installed(ctx); }
    public synchronized void requestPolish(Passage p,List<String> hints,Consumer<String> onResult,Consumer<String> onError) {
        if(p==null || p!=passage || active!=null || !isModelInstalled()) { onError.accept("Cleanup unavailable."); return; }
        ArrayList<String> words=new ArrayList<>();
        if(hints!=null) for(String w:hints) if(w!=null && w.length()<=60 && words.size()<40) words.add(w);
        Request r=new Request(p,words,onResult,onError); active=r;
        try {
            r.bound=ctx.bindService(new Intent(ctx,HexPolisherService.class),r,Context.BIND_AUTO_CREATE);
            if(r.bound) main.postDelayed(r.timeout,180000); else r.finish(null);
        } catch(RuntimeException unavailable) { r.finish(null); }
    }
    public synchronized JSONObject statusJson(boolean gemmaLabEnabled,boolean wordsLabEnabled) {
        try { return new JSONObject().put("gemmaLabEnabled",gemmaLabEnabled).put("wordsLabEnabled",wordsLabEnabled)
            .put("modelInstalled",isModelInstalled()).put("busy",active!=null).put("hasPassage",passage!=null)
            .put("hintsConnected",HexHintWindowService.isConnected()); }
        catch(JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private final class Request implements ServiceConnection {
        final Passage p; final ArrayList<String> words; final Consumer<String> result,error;
        boolean bound; final Runnable timeout=()->finish(null);
        Request(Passage p,ArrayList<String> words,Consumer<String> result,Consumer<String> error) {
            this.p=p;this.words=words;this.result=result;this.error=error;
        }
        void release() { active=null; main.removeCallbacks(timeout); if(bound) ctx.unbindService(this); }
        void finish(String suggestion) {
            synchronized(Cleanup.this) {
                if(active!=this) return;
                release();
                if(suggestion==null) error.accept("Cleanup unavailable. Your original is unchanged.");
                else result.accept((p.original.startsWith(" ")?" ":"")+suggestion+(p.original.endsWith(" ")?" ":""));
            }
        }
        public void onServiceConnected(ComponentName n,IBinder binder) {
            synchronized(Cleanup.this) {
                if(active!=this) return;
                Message m=Message.obtain(); Bundle b=new Bundle(); b.putString("original",p.original);b.putStringArrayList("words",words);m.setData(b);
                m.replyTo=new Messenger(new Handler(main.getLooper(),reply->{ finish(reply.getData().getString("suggestion"));return true; }));
                try { new Messenger(binder).send(m); } catch(RemoteException e) { finish(null); }
            }
        }
        public void onServiceDisconnected(ComponentName n) { finish(null); }
        public void onBindingDied(ComponentName n) { finish(null); }
        public void onNullBinding(ComponentName n) { finish(null); }
    }
}
