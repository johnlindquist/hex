package anomaly.hex.android.keyboard;

import android.content.Context;
import android.view.*;
import android.widget.*;
import java.util.*;
import static anomaly.hex.android.keyboard.KeyboardPalette.*;

/** Search is local to the IME: its key plane never types a query into the editor. */
public final class EmojiPanelView extends LinearLayout {
    private final FullKeyboardView.Host host;
    private final Runnable close, toggleSearch;
    private final EmojiCatalog.FrequentlyUsed recent;
    private final LinearLayout top, groups, tabs;
    private final ScrollView scroll;
    private final StringBuilder query=new StringBuilder();
    private String contextEmoji;
    private boolean searching;
    private int columns;
    private final List<View> headers=new ArrayList<>();
    private int active;
    private android.graphics.drawable.GradientDrawable cap(int color) {
        var bg=pill(color); bg.setCornerRadius(dp(getContext(),8.5f)); return bg;
    }
    private View icon(int index,String name) {
        View b=new View(getContext()) {
            protected void onDraw(android.graphics.Canvas canvas) {
                float x=getWidth()/2f,y=getHeight()/2f,s=dp(getContext(),20);
                if(index==-1) KeyIcons.drawDelete(canvas,x,y,s,TEXT);
                else if(index==-2) KeyIcons.drawSearch(canvas,x,y,s,TEXT_MUTED);
                else KeyIcons.drawEmojiCategory(canvas,index,x,y,s,TEXT_MUTED);
            }
        }; b.setContentDescription(name); return b;
    }
    private void highlight(int which) {
        active=which;
        for(int i=0;i<headers.size();i++) tabs.getChildAt(i+1).setBackground(i==active?cap(CHIP_FILL):null);
    }

    EmojiPanelView(Context c,FullKeyboardView.Host host,Runnable close,Runnable toggleSearch) {
        super(c); this.host=host; this.close=close; this.toggleSearch=toggleSearch;
        recent=new EmojiCatalog.FrequentlyUsed(c); setOrientation(VERTICAL); setBackgroundColor(BACKDROP);
        top=new LinearLayout(c); addView(top,-1,dp(c,44));
        scroll=new ScrollView(c); addView(scroll,new LayoutParams(-1,0,1));
        groups=new LinearLayout(c); groups.setOrientation(VERTICAL); scroll.addView(groups);
        tabs=new LinearLayout(c); tabs.setGravity(Gravity.CENTER_VERTICAL); addView(tabs,-1,dp(c,49));
        scroll.setOnScrollChangeListener((v,x,y,ox,oy)->{
            int index=0; for(int i=1;i<headers.size();i++) if(headers.get(i).getTop()<=y) index=i;
            if(index!=active) highlight(index);
        });
    }
    private TextView button(LinearLayout row,String name,String glyph,int size,Runnable action) {
        TextView b=label(getContext(),glyph,size); b.setContentDescription(name); b.setOnClickListener(v->action.run());
        row.addView(b,new LayoutParams(0,-1,1)); return b;
    }
    void open() { search(false); scroll.scrollTo(0,0); }
    void contextEmoji(String value) { contextEmoji=value; if(isShown()) refreshTop(); }
    boolean searching() { return searching; }
    void search(boolean value) {
        searching=value; query.setLength(0); refreshTop();
        scroll.setVisibility(value?GONE:VISIBLE); tabs.setVisibility(value?GONE:VISIBLE);
        if(!value) populate();
    }
    void append(String s) { if(query.length()<64) query.append(s); refreshTop(); }
    void erase() { if(query.length()>0) query.delete(query.offsetByCodePoints(query.length(),-1),query.length()); refreshTop(); }
    private void pick(String s) { recent.record(s); host.onEmojiPick(s); if(searching) toggleSearch.run(); else populate(); }
    private void refreshTop() {
        top.removeAllViews(); Context c=getContext();
        View search=searching?label(c,"‹",24):icon(-2,"Search emoji"); search.setContentDescription(searching?"Close emoji search":"Search emoji");
        top.addView(search,dp(c,44),-1); search.setOnClickListener(v->toggleSearch.run());
        if(searching) {
            TextView field=label(c,query.length()==0?"Search":query.toString(),15); field.setTextColor(TEXT_MUTED);
            top.addView(field,dp(c,74),-1);
        }
        HorizontalScrollView rail=new HorizontalScrollView(c); rail.setHorizontalScrollBarEnabled(false); top.addView(rail,new LayoutParams(0,-1,1f));
        LinearLayout results=new LinearLayout(c); rail.addView(results);
        List<String> items=searching ? EmojiCatalog.search(query.toString()) : contextEmoji==null?List.of():List.of(contextEmoji);
        for(String s:items) {
            TextView b=label(c,s,26); results.addView(b,dp(c,44),-1); b.setOnClickListener(v->pick(s));
        }
    }
    @Override protected void onMeasure(int w,int h) {
        int count=Math.max(1,MeasureSpec.getSize(w)/dp(getContext(),43));
        if(columns!=count || (!searching && groups.getChildCount()==0)) { columns=count; populate(); }
        super.onMeasure(w,h);
    }
    private void populate() {
        if(columns==0) return;
        groups.removeAllViews(); tabs.removeAllViews(); Context c=getContext();
        headers.clear(); TextView abc=label(c,"ABC",15); abc.setContentDescription("Back to letters");
        abc.setBackground(cap(KEY_FILL)); abc.setOnClickListener(v->close.run()); tabs.addView(abc,dp(c,54),dp(c,45));
        Map<String,List<String>> all=new LinkedHashMap<>(); all.put("Frequently used",recent.list()); all.putAll(EmojiCatalog.categories());
        int i=0;
        for(var entry:all.entrySet()) {
            String name=entry.getKey(); TextView header=label(c,name,13); header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(c,8),0,0,0); header.setTextColor(TEXT_MUTED); groups.addView(header,-1,dp(c,28));
            int index=i++; headers.add(header); View tab=icon(index,name);
            tabs.addView(tab,new LayoutParams(0,dp(c,36),1));
            tab.setOnClickListener(v->{scroll.scrollTo(0,header.getTop()); highlight(index);});
            int col=0; LinearLayout row=null;
            for(String s:entry.getValue()) {
                if(col==0) { row=new LinearLayout(c); row.setWeightSum(Math.max(columns,1)); groups.addView(row,-1,dp(c,42)); }
                button(row,s,s,28,()->pick(s)); col=(col+1)%Math.max(1,columns);
            }
        }
        highlight(active); View delete=icon(-1,"Delete"); delete.setBackground(cap(KEY_FILL));
        tabs.addView(delete,dp(c,54),dp(c,45)); delete.setOnClickListener(v->host.onDeleteChar());
        Runnable repeat=new Runnable(){ public void run(){
            if(delete.isPressed() && delete.isShown()){host.onDeleteChar(); delete.postDelayed(this,75);}
        }};
        delete.setOnTouchListener((v,e)->{
            switch(e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {v.setPressed(true); v.performClick(); v.postDelayed(repeat,360);}
                case MotionEvent.ACTION_MOVE -> {if(e.getX()<0 || e.getX()>=v.getWidth() || e.getY()<0 || e.getY()>=v.getHeight()) {v.setPressed(false); v.removeCallbacks(repeat);}}
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {v.setPressed(false); v.removeCallbacks(repeat);}
            }
            return true;
        });
    }
}
