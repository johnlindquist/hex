package anomaly.hex.android.keyboard;

import android.content.Context;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.widget.*;
import anomaly.hex.android.ui.DotScopeView;
import static anomaly.hex.android.keyboard.KeyboardPalette.*;

/** Occupies only the key rows, leaving the recording controls and their gesture untouched. */
final class PunctuationDrumsOverlay extends LinearLayout {
    private final DotScopeView scope;

    PunctuationDrumsOverlay(Context c, FullKeyboardView.Host host) {
        super(c); setOrientation(VERTICAL); setBackgroundColor(0xD91A1B20);
        setClickable(true); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(GONE);
        TextView hint=label(c,"Tap punctuation while speaking",13); hint.setTextColor(TEXT_MUTED);
        addView(hint,-1,dp(c,24));
        scope=new DotScopeView(c); scope.setAlpha(.65f); addView(scope,-1,dp(c,26));
        String[] marks={",",".","?","!","\n","\n\n"};
        String[] names={"Comma","Period","Question","Exclamation","New line","New paragraph"};
        for(int r=0;r<2;r++) {
            LinearLayout row=new LinearLayout(c); row.setGravity(Gravity.CENTER); row.setPadding(dp(c,6),0,dp(c,6),0);
            addView(row,new LayoutParams(-1,0,1));
            for(int col=0;col<3;col++) {
                int i=3*r+col; String mark=marks[i]; boolean line=i>=4;
                TextView pad=label(c,line?(i==4?"↵":"¶"):mark,26);
                pad.setContentDescription(names[i]); pad.setTextColor(line?ON_PILL_TEXT:TEXT);
                var states=new StateListDrawable();
                for(boolean down:new boolean[]{true,false}) {
                    var shape=pill(line?(down?RETURN_PILL_PRESSED:RETURN_PILL_FILL):(down?KEY_PRESSED:KEY_FILL));
                    shape.setCornerRadius(dp(c,12));
                    states.addState(down?new int[]{android.R.attr.state_pressed}:new int[0],shape);
                }
                pad.setBackground(states); pad.setOnClickListener(v->host.onLivePunctuation(mark));
                var p=new LayoutParams(0,-1,1); int m=dp(c,4); p.setMargins(m,m,m,m); row.addView(pad,p);
            }
        }
    }
    void update(boolean live,int[] levels) { setVisibility(live?VISIBLE:GONE); scope.update(live,levels); }
}
