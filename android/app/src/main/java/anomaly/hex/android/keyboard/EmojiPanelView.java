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
    private static final String[] ICONS={"◷","☺","♧","☕","⚽","✈","♢","♡","⚑"};

    EmojiPanelView(Context c,FullKeyboardView.Host host,Runnable close,Runnable toggleSearch) {
        super(c); this.host=host; this.close=close; this.toggleSearch=toggleSearch;
        recent=new EmojiCatalog.FrequentlyUsed(c); setOrientation(VERTICAL); setBackgroundColor(BACKDROP);
        top=new LinearLayout(c); addView(top,-1,dp(c,44));
        scroll=new ScrollView(c); addView(scroll,new LayoutParams(-1,0,1));
        groups=new LinearLayout(c); groups.setOrientation(VERTICAL); scroll.addView(groups);
        tabs=new LinearLayout(c); addView(tabs,-1,dp(c,40));
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
        TextView search=label(c,searching?"‹":"⌕",24); search.setContentDescription(searching?"Close emoji search":"Search emoji");
        top.addView(search,dp(c,44),-1); search.setOnClickListener(v->toggleSearch.run());
        if(searching) {
            TextView field=label(c,query.length()==0?"Search":query.toString(),15); field.setTextColor(TEXT_MUTED);
            top.addView(field,dp(c,74),-1);
        }
        HorizontalScrollView rail=new HorizontalScrollView(c); rail.setHorizontalScrollBarEnabled(false); top.addView(rail,-1,-1);
        LinearLayout results=new LinearLayout(c); rail.addView(results);
        List<String> items=searching ? EmojiCatalog.search(query.toString()) : contextEmoji==null?List.of():List.of(contextEmoji);
        for(String s:items) {
            TextView b=label(c,s,26); results.addView(b,dp(c,44),-1); b.setOnClickListener(v->pick(s));
        }
    }
    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH) {
        super.onSizeChanged(w,h,oldW,oldH); int count=Math.max(1,w/dp(getContext(),43));
        if(columns!=count) { columns=count; if(!searching) populate(); }
    }
    private void populate() {
        if(columns==0) return;
        groups.removeAllViews(); tabs.removeAllViews(); Context c=getContext();
        button(tabs,"Back to letters","ABC",14,close);
        Map<String,List<String>> all=new LinkedHashMap<>(); all.put("Frequently used",recent.list()); all.putAll(EmojiCatalog.categories());
        int i=0;
        for(var entry:all.entrySet()) {
            String name=entry.getKey(); TextView header=label(c,name,13); header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(c,8),0,0,0); header.setTextColor(TEXT_MUTED); groups.addView(header,-1,dp(c,28));
            button(tabs,name,ICONS[i++],17,()->scroll.smoothScrollTo(0,header.getTop()));
            int col=0; LinearLayout row=null;
            for(String s:entry.getValue()) {
                if(col==0) { row=new LinearLayout(c); row.setWeightSum(Math.max(columns,1)); groups.addView(row,-1,dp(c,42)); }
                button(row,s,s,28,()->pick(s)); col=(col+1)%Math.max(1,columns);
            }
        }
        button(tabs,"Delete","⌫",18,host::onDeleteChar);
    }
}
