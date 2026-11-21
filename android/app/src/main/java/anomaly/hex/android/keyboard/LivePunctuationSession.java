package anomaly.hex.android.keyboard;

/** SpeechSession delivers disjoint, ordered phrases, including its final flush. Append each
 * one after the latest confirmed edit; never replace a previously delivered phrase or pad. */
final class LivePunctuationSession {
    record Edit(int trim, String text) {}
    private int start, end;
    private boolean capitalize;
    private char phraseEnding;

    LivePunctuationSession(int start,int end) { this.start=start; this.end=end; }
    boolean matches(int a,int b) { return start==a && end==b; }
    int start() { return start; }
    int end() { return end; }
    Edit tap(String mark,String before) {
        boolean line="\n".equals(mark)||"\n\n".equals(mark);
        if(!line && !(mark.length()==1 && ",.?!".contains(mark))) throw new IllegalArgumentException("Unknown pad");
        int n=before.length();
        while(n>0 && before.charAt(n-1)==' ') n--;
        // A decoded sentence commonly already ends in a guessed period. An explicit pad wins,
        // but never replace punctuation from a previous pad or from the editor before dictation.
        if(!line && phraseEnding!=0 && n>0 && before.charAt(n-1)==phraseEnding) n--;
        return new Edit(before.length()-n,line?mark:mark+" ");
    }
    Edit phrase(String text) {
        if(capitalize && !text.isEmpty()) {
            int cp=text.codePointAt(0);
            text=Character.toString(Character.toUpperCase(cp))+text.substring(Character.charCount(cp));
        }
        return new Edit(0,text);
    }
    void committed(Edit edit,boolean tap) {
        start=end=Math.min(start,end)-edit.trim()+edit.text().length();
        capitalize=tap && !edit.text().equals(", ");
        String phrase=tap?"":edit.text().stripTrailing();
        char last=phrase.isEmpty()?0:phrase.charAt(phrase.length()-1);
        phraseEnding=",.?!".indexOf(last)>=0?last:0;
    }
}
