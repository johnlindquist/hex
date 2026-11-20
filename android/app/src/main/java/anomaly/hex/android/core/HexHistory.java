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
        public final String rawText, finalText, style;
        Entry(Cursor c) {
            id=c.getLong(0); rawText=c.getString(1); finalText=c.getString(2);
            style=c.getString(3); durationMs=c.getLong(4); createdAtMs=c.getLong(5);
        }
    }
    private static final class Store extends SQLiteOpenHelper {
        Store(Context ctx) { super(ctx,"hex_history.db",null,1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT,raw TEXT NOT NULL,final TEXT NOT NULL,style TEXT NOT NULL,duration INTEGER NOT NULL,created INTEGER NOT NULL,bytes INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX history_age ON entries(created)");
            db.execSQL("CREATE TABLE totals(n INTEGER NOT NULL,size INTEGER NOT NULL)");
            db.execSQL("INSERT INTO totals VALUES(0,0)");
            db.execSQL("CREATE TRIGGER added AFTER INSERT ON entries BEGIN UPDATE totals SET n=n+1,size=size+NEW.bytes; END");
            db.execSQL("CREATE TRIGGER removed AFTER DELETE ON entries BEGIN UPDATE totals SET n=n-1,size=size-OLD.bytes; END");
        }
        @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { throw new IllegalStateException("Unknown history version"); }
    }
    private static Store shared;
    private final Store store;
    private final HexSettings settings;
    public HexHistory(Context ctx) {
        synchronized (HexHistory.class) {
            if (shared==null) shared=new Store(ctx.getApplicationContext());
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
        if (retention==HistoryRetention.OFF) return -1;
        SQLiteDatabase db=store.getWritableDatabase(); db.beginTransaction();
        try {
            long now=System.currentTimeMillis(); prune(db,retention,now);
            String raw=bounded(rawText), result=bounded(finalText);
            ContentValues v=new ContentValues(); v.put("raw",raw); v.put("final",result); v.put("style",style.id());
            v.put("duration",Math.max(0,durationMs)); v.put("created",now);
            v.put("bytes",raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            long id=db.insertOrThrow("entries",null,v);
            while (DatabaseUtils.longForQuery(db,"SELECT n>10000 OR size>16777216 FROM totals",null)!=0)
                db.execSQL("DELETE FROM entries WHERE id=(SELECT MIN(id) FROM entries)");
            db.setTransactionSuccessful(); return id;
        } finally { db.endTransaction(); }
    }
    public synchronized List<Entry> list(String query,int limit) {
        pruneExpired(settings.historyRetention());
        int max=Math.min(200,Math.max(0,limit)); List<Entry> result=new ArrayList<>();
        String q=query==null?"":query.strip().toLowerCase(Locale.ROOT);
        try (Cursor c=store.getReadableDatabase().rawQuery("SELECT id,raw,final,style,duration,created FROM entries ORDER BY id DESC",null)) {
            while (result.size()<max && c.moveToNext()) {
                Entry e=new Entry(c);
                if (q.isEmpty() || e.rawText.toLowerCase(Locale.ROOT).contains(q) || e.finalText.toLowerCase(Locale.ROOT).contains(q)) result.add(e);
            }
        }
        return result;
    }
    private static int prune(SQLiteDatabase db,HistoryRetention retention,long now) {
        return retention==HistoryRetention.OFF?0:db.delete("entries","created < ?",new String[]{Long.toString(now-(retention==null?HistoryRetention.DAYS_7:retention).millis())});
    }
    public synchronized int pruneExpired(HistoryRetention retention) { return prune(store.getWritableDatabase(),retention,System.currentTimeMillis()); }
    public synchronized int clearAll() { return store.getWritableDatabase().delete("entries",null,null); }
    public synchronized int count() {
        pruneExpired(settings.historyRetention());
        return (int)DatabaseUtils.longForQuery(store.getReadableDatabase(),"SELECT n FROM totals",null);
    }
}
