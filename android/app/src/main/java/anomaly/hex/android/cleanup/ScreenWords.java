package anomaly.hex.android.cleanup;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.*;

/** Single app window only. No file, display-wide fallback or clipboard. */
final class ScreenWords {
    private static final Set<String> COMMON=new HashSet<>(Arrays.asList("a an as at be by do for from in is it of on or that the this to your with you are can after before my me if not so no us and which what where was we out there then them has i".split(" ")));
    interface Result { boolean current(); void done(List<String> words,String note); }
    static void read(HexHintWindowService b,int id,Result sink){
        if(android.os.Build.VERSION.SDK_INT<34||id<0){sink.done(null,"This editor window is unavailable for capture.");return;}
        try{b.takeScreenshotOfWindow(id,b.getMainExecutor(),new AccessibilityService.TakeScreenshotCallback(){
            public void onFailure(int code){if(sink.current())sink.done(null,code==6?"Android protects this window from capture.":"Android did not share this window. You can clean up without hints.");}
            public void onSuccess(AccessibilityService.ScreenshotResult result){Bitmap pixels=null;
                try(HardwareBuffer buffer=result.getHardwareBuffer()){
                    if(!sink.current())return;Bitmap wrapped=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());if(wrapped==null)throw new IllegalStateException();
                    try{pixels=wrapped.copy(Bitmap.Config.ARGB_8888,false);}finally{wrapped.recycle();}
                    final Bitmap image=pixels;final TextRecognizer ocr=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                    try{ocr.process(InputImage.fromBitmap(image,0)).addOnCompleteListener(task->{try{
                        if(!sink.current())return;LinkedHashSet<String> found=new LinkedHashSet<>();
                        if(!task.isSuccessful()){sink.done(null,"Text recognition failed.");return;}for(Text.TextBlock block:task.getResult().getTextBlocks())for(Text.Line line:block.getLines())for(Text.Element e:line.getElements()){
                            String w=e.getText().replaceAll("^[('“‘]+|[.,:;!?)’”]+$","");if(w.matches("[\\p{L}][\\p{L}'’-]{1,29}")&&!COMMON.contains(w.toLowerCase(Locale.ROOT))&&found.size()<600)found.add(w);
                        }
                        ArrayList<String> list=new ArrayList<>(found);list.sort(Comparator.comparingInt(w->Character.isUpperCase(w.codePointAt(0))?0:1));if(list.size()>40)list=new ArrayList<>(list.subList(0,40));
                        sink.done(list,list.isEmpty()?"No spelling suggestions found.":"Tap any spellings to include. None selected by default.");
                    }finally{ocr.close();image.recycle();}});}catch(RuntimeException e){ocr.close();throw e;}
                }catch(Exception|OutOfMemoryError e){if(pixels!=null&&!pixels.isRecycled())pixels.recycle();if(sink.current())sink.done(null,"This window could not be read.");}
            }
        });}catch(RuntimeException e){if(sink.current())sink.done(null,"Window access unavailable.");}
    }
}
