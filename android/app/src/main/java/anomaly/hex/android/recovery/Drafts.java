package anomaly.hex.android.recovery;

import android.app.job.*;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.security.keystore.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.*;

/** Recovery checkpoints; the only plaintext columns are bounded operational metadata. */
public final class Drafts extends SQLiteOpenHelper {
    private static final int JOB=143208, MAX=32;
    private static final long DAY=86400000L;
    private static final String ALIAS="hex_draft_key";
    private final Context ctx;
    public static final class Draft {
        public final String id,status,reason,text;
        public final int rev;
        public final long createdAtMs,durationMs;
        private Draft(Cursor c,String text) {
            id=c.getString(0);rev=c.getInt(1);createdAtMs=c.getLong(2);durationMs=c.getLong(3)/16;
            status=c.getInt(4)==0?"audio":"text";reason=c.getString(5);this.text=text;
        }
    }
    public Drafts(Context ctx) {super(ctx.getApplicationContext(),"hex_drafts.db",null,1);this.ctx=ctx.getApplicationContext();}
    public void onConfigure(SQLiteDatabase d){d.setForeignKeyConstraintsEnabled(true);d.execSQL("PRAGMA synchronous=FULL");try(Cursor c=d.rawQuery("PRAGMA secure_delete=ON",null)){c.moveToFirst();}}
    public void onCreate(SQLiteDatabase d){
        d.execSQL("CREATE TABLE draft(id TEXT PRIMARY KEY,rev INTEGER NOT NULL DEFAULT 1,created INTEGER NOT NULL,samples INTEGER NOT NULL DEFAULT 0,txt BLOB,reason TEXT NOT NULL)");
        d.execSQL("CREATE TABLE chunk(id TEXT NOT NULL REFERENCES draft(id) ON DELETE CASCADE,seq INTEGER NOT NULL,box BLOB NOT NULL,PRIMARY KEY(id,seq))");
    }
    public void onUpgrade(SQLiteDatabase d,int from,int to){throw new IllegalStateException("Unsupported recovery database");}
    private SQLiteDatabase db(){return getWritableDatabase();}
    private SecretKey key() throws Exception {

        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);SecretKey key=(SecretKey)ks.getKey(ALIAS,null);
        if(key==null){KeyGenerator kg=KeyGenerator.getInstance("AES","AndroidKeyStore");kg.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes("GCM").setEncryptionPaddings("NoPadding").setKeySize(256).build());key=kg.generateKey();}return key;
    }
    private byte[] crypt(boolean encrypt,String id,String part,byte[] input) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        if(encrypt)cipher.init(Cipher.ENCRYPT_MODE,key());else cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,input,0,12));
        cipher.updateAAD((id+":"+part).getBytes(StandardCharsets.US_ASCII));
        if(!encrypt)return cipher.doFinal(input,12,input.length-12);
        byte[] iv=cipher.getIV(), enc=cipher.doFinal(input);if(iv.length!=12)throw new IllegalStateException();
        byte[] box=Arrays.copyOf(iv,12+enc.length);System.arraycopy(enc,0,box,12,enc.length);return box;
    }

    private byte[] box(boolean encrypt,String id,String part,byte[] data) {
        try { return crypt(encrypt,id,part,data); } catch(Exception e){throw new IllegalStateException("Recovery storage unavailable",e);}
    }
    public synchronized int purgeExpired(){long now=System.currentTimeMillis();return db().delete("draft","created<=? OR created>?",new String[]{""+(now-DAY),""+now});}
    public synchronized String createAudioDraft(String reason){
        SQLiteDatabase d=db();d.beginTransaction();try{
            purgeExpired();if(DatabaseUtils.queryNumEntries(d,"draft")>=MAX) throw new IllegalStateException("draft_limit");
            JobScheduler s=ctx.getSystemService(JobScheduler.class);
            if(s.getPendingJob(JOB)==null && s.schedule(new JobInfo.Builder(JOB,new ComponentName(ctx,HexExpiryJobService.class)).setPeriodic(3600000L).setPersisted(true).build())!=JobScheduler.RESULT_SUCCESS) throw new IllegalStateException("Expiry unavailable");
            String id=UUID.randomUUID().toString();ContentValues v=new ContentValues();v.put("id",id);v.put("created",System.currentTimeMillis());
            v.put("reason",reason!=null && reason.matches("[a-z_]{1,40}")?reason:"interrupted");d.insertOrThrow("draft",null,v);d.setTransactionSuccessful();return id;
        }finally{d.endTransaction();}
    }
    public synchronized void appendChunk(String id,byte[] pcm){
        if(id==null || pcm==null || pcm.length==0)return;if((pcm.length&1)!=0) throw new IllegalArgumentException("Incomplete PCM sample");
        SQLiteDatabase d=db();d.beginTransaction();try{
            purgeExpired();if(DatabaseUtils.queryNumEntries(d,"draft","id=? AND txt IS NULL",new String[]{id})!=0){
                long seq=DatabaseUtils.longForQuery(d,"SELECT coalesce(max(seq),-1)+1 FROM chunk WHERE id=?",new String[]{id});
                for(int offset=0;offset<pcm.length;){int end=offset+Math.min(262144,pcm.length-offset);byte[] slice=Arrays.copyOfRange(pcm,offset,end);
                    try {ContentValues v=new ContentValues();v.put("id",id);v.put("seq",seq);v.put("box",box(true,id,"pcm/"+seq,slice));d.insertOrThrow("chunk",null,v);} finally {Arrays.fill(slice,(byte)0);} seq++;offset=end;}
                d.execSQL("UPDATE draft SET samples=samples+?, rev=rev+1 WHERE id=?",new Object[]{pcm.length/2,id});
            }d.setTransactionSuccessful();
        }finally{d.endTransaction();}
    }
    public synchronized void markText(String id,String text){
        if(id==null || text==null || text.isBlank()) return;
        SQLiteDatabase d=db();d.beginTransaction();try{purgeExpired();if(DatabaseUtils.queryNumEntries(d,"draft","id=?",new String[]{id})!=0){
            d.execSQL("UPDATE draft SET txt=?,rev=rev+1,reason='check_insertion' WHERE id=?",new Object[]{box(true,id,"text",text.getBytes(StandardCharsets.UTF_8)),id});
            d.delete("chunk","id=?",new String[]{id});
        }d.setTransactionSuccessful();}finally{d.endTransaction();}
    }
    public synchronized void removeConfirmed(String id){if(id!=null) db().delete("draft","id=?",new String[]{id});}
    public synchronized boolean delete(String id,int expectedRev){purgeExpired();return id!=null && db().delete("draft","id=? AND rev=?",new String[]{id,""+expectedRev})==1;}
    private List<Draft> rows(String id,boolean withText){
        purgeExpired();List<Draft> rows=new ArrayList<>();try(Cursor c=db().rawQuery("SELECT id,rev,created,samples,txt IS NOT NULL,reason"+(withText?",txt":"")+" FROM draft"+(id==null?"":" WHERE id=?")+" ORDER BY created DESC,rowid DESC",id==null?null:new String[]{id})){
            while(c.moveToNext()) rows.add(new Draft(c,withText && !c.isNull(6)?new String(box(false,c.getString(0),"text",c.getBlob(6)),StandardCharsets.UTF_8):null));
        }return rows;
    }
    public synchronized List<Draft> list(){return rows(null,true);}
    public synchronized Draft get(String id){if(id==null)return null;List<Draft> r=rows(id,true);return r.isEmpty()?null:r.get(0);}
    public synchronized JSONArray listMetadataJson(){JSONArray arr=new JSONArray();for(Draft d:rows(null,false))try{arr.put(new JSONObject().put("id",d.id).put("rev",d.rev).put("status",d.status).put("reason",d.reason).put("createdAtMs",d.createdAtMs).put("durationMs",d.durationMs));}catch(JSONException e){throw new IllegalStateException(e);}return arr;}
    public synchronized byte[] readAudio(String id){
        if(id==null)return null;SQLiteDatabase d=db();d.beginTransaction();try{purgeExpired();
            try(Cursor row=d.rawQuery("SELECT samples FROM draft WHERE id=? AND txt IS NULL",new String[]{id})){
                if(!row.moveToFirst()){d.setTransactionSuccessful();return null;}byte[] out=new byte[Math.toIntExact(row.getLong(0)*2)];int pos=0,seq=0;
                try(Cursor c=d.rawQuery("SELECT seq,box FROM chunk WHERE id=? ORDER BY seq",new String[]{id})){while(c.moveToNext()){
                    if(c.getInt(0)!=seq)throw new IllegalStateException("Missing checkpoint");byte[] raw=box(false,id,"pcm/"+seq++,c.getBlob(1));
                    try{System.arraycopy(raw,0,out,pos,raw.length);pos+=raw.length;}finally{Arrays.fill(raw,(byte)0);}
                }}if(pos!=out.length)throw new IllegalStateException("Incomplete checkpoint");d.setTransactionSuccessful();return out;
            }
        }finally{d.endTransaction();}
    }
    public synchronized int purgeAll(){SQLiteDatabase d=db();d.beginTransaction();try{
        int count=d.delete("draft",null,null);KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);ks.deleteEntry(ALIAS);
        ctx.getSystemService(JobScheduler.class).cancel(JOB);d.setTransactionSuccessful();return count;
    }catch(Exception e){throw new IllegalStateException("Could not erase recovery data",e);}finally{d.endTransaction();}}
}
