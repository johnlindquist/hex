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
    private final HorizontalScrollView scroll;
    private final LinearLayout field;
    private static final List<String> DEFAULTS=List.of("😂","❤️","👍","🔥","🎉","🙏","😊","✨");
    private final StringBuilder query=new StringBuilder();
    private String contextEmoji;
    private boolean searching;
    private int viewport, rows;
    private final List<View> headers=new ArrayList<>();
    private int active;
    private android.graphics.drawable.GradientDrawable cap(int color) {
        var bg=pill(color); bg.setCornerRadius(dp(getContext(),8.5f)); return bg;
    }
    private final android.graphics.Paint indicator=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private View icon(int index,String name) {
        View b=new View(getContext()) {
            protected void onDraw(android.graphics.Canvas canvas) {
                float x=getWidth()/2f,y=getHeight()/2f,s=dp(getContext(),20);
                if(index==-1) KeyIcons.drawDelete(canvas,x,y,s,TEXT);
                else if(index==-2) KeyIcons.drawSearch(canvas,x,y,s,TEXT_MUTED);
                else if(index==-3) {
                    indicator.setColor(MOD_FILL);
                    canvas.drawCircle(x,y,dp(getContext(),10),indicator);
                    indicator.setColor(CHIP_FILL);
                    KeyIcons.drawCancel(canvas,x,y,dp(getContext(),11),TEXT);
                }
                else { if(index==active){android.graphics.Paint paint=indicator; canvas.drawCircle(x,y,dp(getContext(),15),paint);}
                    KeyIcons.drawEmojiCategory(canvas,index,x,y,s,index==active?TEXT:TEXT_MUTED); }
            }
        }; b.setContentDescription(name); return b;
    }
    private void highlight(int which) {
        active=which;
        for(int i=0;i<headers.size();i++) tabs.getChildAt(i+1).invalidate();
    }

    EmojiPanelView(Context c,FullKeyboardView.Host host,Runnable close,Runnable toggleSearch) {
        super(c); this.host=host; this.close=close; this.toggleSearch=toggleSearch;
        recent=new EmojiCatalog.FrequentlyUsed(c); setOrientation(VERTICAL); setBackgroundColor(BACKDROP);
        indicator.setColor(CHIP_FILL); int inset=dp(c,6.5f); int keyInset=dp(c,4.5f);
        top=new LinearLayout(c); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(inset,0,inset,0); addView(top,-1,dp(c,48));
        field=new LinearLayout(c); field.setPadding(keyInset,0,keyInset,dp(c,6)); field.setGravity(Gravity.CENTER_VERTICAL); addView(field,-1,dp(c,48));
        scroll=new HorizontalScrollView(c); scroll.setHorizontalScrollBarEnabled(false); addView(scroll,new LayoutParams(-1,0,1));
        groups=new LinearLayout(c); scroll.addView(groups,new FrameLayout.LayoutParams(-2,-1));
        tabs=new LinearLayout(c); tabs.setPadding(inset,0,inset,0); tabs.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams bar=new LayoutParams(-1,dp(c,38)); bar.bottomMargin=dp(c,5); addView(tabs,bar);
        scroll.setOnScrollChangeListener((v,x,y,ox,oy)->{
            int index=0; for(int i=1;i<headers.size();i++) if(headers.get(i).getLeft()<=x) index=i;
            if(index!=active) highlight(index);
        });
    }
    void open() { search(false); scroll.scrollTo(0,0); }
    void contextEmoji(String value) { if(!Objects.equals(contextEmoji,value)){contextEmoji=value; if(isShown()) refreshTop();} }
    boolean searching() { return searching; }
    void search(boolean value) {
        searching=value; query.setLength(0); refreshTop();
        scroll.setVisibility(value?GONE:VISIBLE); tabs.setVisibility(value?GONE:VISIBLE);
        if(!value) populate();
    }
    void append(String s) { if(query.length()<64) query.append(s); refreshTop(); }
    void erase() { if(query.length()>0) query.delete(query.offsetByCodePoints(query.length(),-1),query.length()); refreshTop(); }
    void eraseWord() {
        int i=query.length();
        while(i>0 && Character.isWhitespace(query.charAt(i-1))) i--;
        while(i>0 && !Character.isWhitespace(query.charAt(i-1))) i--;
        query.setLength(i); refreshTop();
    }
    private void pick(String s) { recent.record(s); host.onEmojiPick(s); if(searching) close.run(); else {populate(); refreshTop();} }
    private void refreshTop() {
        top.removeAllViews(); Context c=getContext();
        field.setVisibility(searching?VISIBLE:GONE);
        if(!searching){View search=icon(-2,"Search emoji"); var bg=pill(CHIP_FILL); bg.setCornerRadius(dp(c,10)); search.setBackground(bg);
            top.addView(search,dp(c,40),dp(c,34)); search.setOnClickListener(v->toggleSearch.run());}
        HorizontalScrollView rail=new HorizontalScrollView(c); rail.setHorizontalScrollBarEnabled(false); top.addView(rail,new LayoutParams(0,-1,1));
        LinearLayout results=new LinearLayout(c); rail.addView(results,new FrameLayout.LayoutParams(-2,-1));
        LinkedHashSet<String> items=new LinkedHashSet<>();
        if(searching && query.length()>0) items.addAll(EmojiCatalog.search(query.toString()));
        else {if(contextEmoji!=null) items.add(contextEmoji); items.addAll(recent.list()); items.addAll(DEFAULTS);}
        if(items.isEmpty()){ TextView none=label(c,"No emoji",14); none.setTextColor(TEXT_MUTED); results.addView(none,-2,-1); }
        for(String s:items){TextView b=label(c,s,26); results.addView(b,dp(c,42),-1); b.setOnClickListener(v->pick(s));}
        if(searching) renderField(c);
    }
    private void renderField(Context c) {
        field.removeAllViews(); LinearLayout pill=new LinearLayout(c); pill.setGravity(Gravity.CENTER_VERTICAL); pill.setBackground(pill(CHIP_FILL));
        field.addView(pill,-1,dp(c,36)); pill.addView(icon(-2,"Search"),dp(c,38),-1);
        LinearLayout input=new LinearLayout(c); input.setGravity(Gravity.CENTER_VERTICAL); pill.addView(input,new LayoutParams(0,-1,1));
        TextView value=label(c,query.length()==0?"Search emoji":query.toString(),16); value.setTextColor(query.length()==0?TEXT_MUTED:TEXT);
        View caret=new View(c); caret.setBackgroundColor(ACTION_FILL); if(query.length()==0) input.addView(caret,dp(c,2),dp(c,20));
        value.setMaxWidth(Math.max(dp(c,32),viewport-dp(c,100))); input.addView(value,-2,-1); if(query.length()>0) input.addView(caret,dp(c,2),dp(c,20));
        View clear=icon(-3,query.length()==0?"Close emoji search":"Clear search");
        pill.addView(clear,dp(c,38),-1); clear.setOnClickListener(v->{if(query.length()==0) toggleSearch.run(); else {query.setLength(0); refreshTop();}});
    }
    @Override protected void onMeasure(int w,int h) {
        int count=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE?3:4;
        if(viewport!=MeasureSpec.getSize(w)||rows!=count){viewport=MeasureSpec.getSize(w);rows=count;if(!searching) populate();}
        super.onMeasure(w,h);
    }
    private void populate() {
        if(rows==0) return;
        groups.removeAllViews(); tabs.removeAllViews(); Context c=getContext();
        headers.clear(); TextView abc=label(c,"ABC",15); abc.setTextSize(15.5f); abc.setContentDescription("Back to letters");
        abc.setBackground(cap(KEY_FILL)); abc.setOnClickListener(v->close.run()); tabs.addView(abc,dp(c,50),-1);
        Map<String,List<String>> all=new LinkedHashMap<>();
        LinkedHashSet<String> freq=new LinkedHashSet<>(recent.list());
        for(String d:DEFAULTS) { if(freq.size()>=8) break; freq.add(d); }
        all.put("Frequently used",new ArrayList<>(freq)); all.putAll(EmojiCatalog.categories());
        int i=0,sectionWidth=0;
        for(var entry:all.entrySet()) {
            String name=entry.getKey(); LinearLayout section=new LinearLayout(c); section.setOrientation(VERTICAL);
            TextView header=label(c,name,12); header.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0)); header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(c,6.5f),0,0,0); header.setTextColor(TEXT_MUTED); section.addView(header,-1,dp(c,20));
            List<String> emojis=entry.getValue(); sectionWidth=Math.max((emojis.size()+rows-1)/rows*dp(c,42),dp(c,12)+(int)header.getPaint().measureText(name));
            groups.addView(section,sectionWidth,-1); LinearLayout grid=new LinearLayout(c); section.addView(grid,-1,-1);
            int index=i++; headers.add(section); View tab=icon(index,name); tabs.addView(tab,new LayoutParams(0,-1,1));
            tab.setOnClickListener(v->{scroll.scrollTo(section.getLeft(),0); highlight(index);});
            for(int start=0;start<emojis.size();start+=rows){ LinearLayout column=new LinearLayout(c); column.setOrientation(VERTICAL); column.setWeightSum(rows); grid.addView(column,dp(c,42),-1);
                for(int j=start;j<Math.min(start+rows,emojis.size());j++){String s=emojis.get(j); TextView key=label(c,s,rows==3?23:28);key.setIncludeFontPadding(false);
                    column.addView(key,new LayoutParams(-1,0,1)); key.setOnClickListener(v->pick(s));}}
        }
        groups.addView(new View(c),Math.max(0,viewport-sectionWidth),1);
        highlight(active); View delete=icon(-1,"Delete"); delete.setBackground(cap(KEY_FILL));
        tabs.addView(delete,dp(c,50),-1); delete.setOnClickListener(v->host.onDeleteChar());
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
