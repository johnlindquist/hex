package anomaly.hex.android.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class HexHistory {
    public static final class Entry {
        public final long id, durationMs, createdAtMs;
        public final String rawText, finalText, style, audioPath;
        Entry(Cursor c) {
            id=c.getLong(0); rawText=c.getString(1); finalText=c.getString(2);
            style=c.getString(3); durationMs=c.getLong(4); createdAtMs=c.getLong(5);
            audioPath=c.isNull(6) ? null : c.getString(6);
        }
    }
    private static final class Store extends SQLiteOpenHelper {
        private long lastPruneMs;
        private HistoryRetention lastRetention;
        Store(Context ctx) { super(ctx,"hex_history.db",null,2); setWriteAheadLoggingEnabled(true); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT,raw TEXT NOT NULL,final TEXT NOT NULL,style TEXT NOT NULL,duration INTEGER NOT NULL,created INTEGER NOT NULL,bytes INTEGER NOT NULL,audio_path TEXT)");
            db.execSQL("CREATE INDEX history_age ON entries(created)");
            db.execSQL("CREATE TABLE totals(n INTEGER NOT NULL,size INTEGER NOT NULL)");
            db.execSQL("INSERT INTO totals VALUES(0,0)");
            db.execSQL("CREATE TRIGGER added AFTER INSERT ON entries BEGIN UPDATE totals SET n=n+1,size=size+NEW.bytes; END");
            db.execSQL("CREATE TRIGGER removed AFTER DELETE ON entries BEGIN UPDATE totals SET n=n-1,size=size-OLD.bytes; END");
        }
        @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE entries ADD COLUMN audio_path TEXT");
            }
        }
    }
    private static Store shared;
    private final Store store;
    private final HexSettings settings;
    private final Context appCtx;
    public HexHistory(Context ctx) {
        appCtx = ctx.getApplicationContext();
        synchronized (HexHistory.class) {
            if (shared==null) shared=new Store(appCtx);
            store=shared;
        }
        settings=new HexSettings(ctx);
    }
    static String bounded(String s) {
        if (s==null) return "";
        int bytes=0,i=0;
        while (i<s.length()) {
            int c=s.codePointAt(i);
            bytes+=c<=127?1:c<=2047?2:c<=65535?3:4;
            if (bytes>16384) return s.substring(0,i);
            i+=Character.charCount(c);
        }
        return s;
    }
    public synchronized long record(String rawText,String finalText,TranscriptStyle style,long durationMs,HistoryRetention retention) {
        return record(rawText, finalText, style, durationMs, retention, null);
    }
    public synchronized long record(String rawText,String finalText,TranscriptStyle style,long durationMs,HistoryRetention retention,String audioPath) {
        if (retention==HistoryRetention.OFF) {
            anomaly.hex.android.speech.RecordingStore.deleteFile(audioPath);
            return -1;
        }
        SQLiteDatabase db=store.getWritableDatabase(); db.beginTransaction();
        try {
            long now=System.currentTimeMillis(); prune(db,retention,now);
            String raw=bounded(rawText), result=bounded(finalText);
            ContentValues v=new ContentValues(); v.put("raw",raw); v.put("final",result); v.put("style",style.id());
            v.put("duration",Math.max(0,durationMs)); v.put("created",now);
            v.put("bytes",raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            if (audioPath!=null && !audioPath.isEmpty()) v.put("audio_path",audioPath);
            long id=db.insertOrThrow("entries",null,v);
            while (DatabaseUtils.longForQuery(db,"SELECT n>10000 OR size>16777216 FROM totals",null)!=0) {
                try (Cursor old=db.rawQuery("SELECT id,audio_path FROM entries WHERE id=(SELECT MIN(id) FROM entries)",null)) {
                    if (old.moveToFirst()) {
                        anomaly.hex.android.speech.RecordingStore.deleteFile(old.isNull(1)?null:old.getString(1));
                        db.delete("entries","id=?",new String[]{Long.toString(old.getLong(0))});
                    } else break;
                }
            }
            db.setTransactionSuccessful(); return id;
        } finally { db.endTransaction(); }
    }
    public synchronized boolean updateTranscript(long id,String rawText,String finalText) {
        SQLiteDatabase db=store.getWritableDatabase(); db.beginTransaction();
        try {
            long oldBytes=0;
            try (Cursor c=db.rawQuery("SELECT bytes FROM entries WHERE id=?",new String[]{Long.toString(id)})) {
                if (!c.moveToFirst()) return false;
                oldBytes=c.getLong(0);
            }
            String raw=bounded(rawText), result=bounded(finalText);
            long newBytes=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            ContentValues v=new ContentValues(); v.put("raw",raw); v.put("final",result); v.put("bytes",newBytes);
            int n=db.update("entries",v,"id=?",new String[]{Long.toString(id)});
            if (n>0) db.execSQL("UPDATE totals SET size=size+?",new Object[]{newBytes-oldBytes});
            db.setTransactionSuccessful();
            return n>0;
        } finally { db.endTransaction(); }
    }
    static String likePattern(String q) {
        return "%"+q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
    }
    public synchronized List<Entry> list(String query,int limit) {
        pruneOnRead();
        int max=Math.min(200,Math.max(0,limit)); List<Entry> result=new ArrayList<>();
        String q=query==null?"":query.strip().toLowerCase(Locale.ROOT);
        String sql="SELECT id,raw,final,style,duration,created,audio_path FROM entries";
        String[] args={Integer.toString(max)};
        if (!q.isEmpty()) {
            String pattern=likePattern(q);
            sql+=" WHERE lower(raw) LIKE ? ESCAPE '\\' OR lower(final) LIKE ? ESCAPE '\\'";
            args=new String[]{pattern,pattern,args[0]};
        }
        try (Cursor c=store.getReadableDatabase().rawQuery(sql+" ORDER BY id DESC LIMIT ?",args)) {
            while (c.moveToNext()) result.add(new Entry(c));
        }
        return result;
    }
    private static int prune(SQLiteDatabase db,HistoryRetention retention,long now) {
        if (retention==HistoryRetention.OFF) return 0;
        String cutoff=Long.toString(now-(retention==null?HistoryRetention.DAYS_7:retention).millis());
        try (Cursor c=db.rawQuery("SELECT audio_path FROM entries WHERE created < ? AND audio_path IS NOT NULL",new String[]{cutoff})) {
            while (c.moveToNext()) anomaly.hex.android.speech.RecordingStore.deleteFile(c.getString(0));
        }
        return db.delete("entries","created < ?",new String[]{cutoff});
    }
    private void pruneOnRead() {
        synchronized (store) {
            HistoryRetention retention=settings.historyRetention();
            long elapsed=System.currentTimeMillis()-store.lastPruneMs;
            if (retention==store.lastRetention && elapsed>=0 && elapsed<60_000) return;
            pruneExpired(retention);
        }
    }
    public int pruneExpired(HistoryRetention retention) {
        synchronized (store) {
            long now=System.currentTimeMillis();
            int removed=prune(store.getWritableDatabase(),retention,now);
            store.lastPruneMs=now;
            store.lastRetention=retention;
            return removed;
        }
    }
    public synchronized int clearAll() {
        anomaly.hex.android.speech.RecordingStore.deleteAll(appCtx);
        return store.getWritableDatabase().delete("entries",null,null);
    }
    public synchronized int count() {
        pruneOnRead();
        return (int)DatabaseUtils.longForQuery(store.getReadableDatabase(),"SELECT n FROM totals",null);
    }
}
