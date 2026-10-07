package com.apple.android.music.player;
import java.lang.reflect.Method;
import java.util.List;
import android.widget.TextView;
import android.text.Html;
import androidx.recyclerview.widget.RecyclerView$D;
import com.apple.android.music.common.views.FullWidthAlphaGradientFlexboxLayout;
import com.apple.android.music.ttml.javanative.model.LyricsLine;
import dev.amenhancer.plugin.api.PluginCall;
import dev.amenhancer.plugin.api.PluginHook;
import com.tcrrry.lyrics.plugin.TestPluginContext;
/** Shape verified against actual 1606 DEX. Behavior models skipped cached glyph rebuilds. */
public class A extends n1 {
 public Object pointer=new Object();
 public Processor p=new Processor();
 public boolean nativeTranslation, nativePronunciation;
 public int glyphRebuilds;
 public boolean failBind;
 public Object y() { return pointer; }
 public int f(int position) { return 3; }
 public int E(int position,boolean main) { return position; }
 public static class LinePtr { public LyricsLine.LyricsLineNative line=new LyricsLine.LyricsLineNative(); public LyricsLine.LyricsLineNative get() { return line; } }
 public static class Processor { public LinePtr value=new LinePtr(); public LinePtr a(int index) { return value; } }
 public void U(String text, FullWidthAlphaGradientFlexboxLayout words,int layout,boolean rtl,int align) {
  TextView view=new TextView(words.getContext());view.setText(Html.fromHtml(text,0));words.addView(view);
 }
 public void b0(String text, int unused, FullWidthAlphaGradientFlexboxLayout words,int layout,int extra,boolean rtl,int align) {
  // Intentionally no nested hook dispatch: the outer bind must suffice.
  words.removeAllViews();TextView view=new TextView(words.getContext());view.setText(text);words.addView(view);glyphRebuilds++;
 }
 private void dispatch(String name,Class<?>[] types,Object[] args,Runnable body) {
  try {
   Method method=A.class.getDeclaredMethod(name,types);
   PluginHook hook=TestPluginContext.Companion.getCurrent().getCallbacks().get(method);
   PluginCall call=new PluginCall(method,this,args,null,null);
   if(hook!=null)hook.before(call);
   Throwable failure=call.getThrowable();
   if(!call.isOutcomeChanged()) {
    try { body.run(); } catch(Throwable error) { failure=error; }
   }
   PluginCall outcome=new PluginCall(method,this,call.getArguments(),call.getResult(),failure);
   if(hook!=null)hook.after(outcome);
   if(outcome.getThrowable()!=null)throw new RuntimeException(outcome.getThrowable());
  } catch(Exception error) { throw new RuntimeException(error); }
 }
 public void k(RecyclerView$D holder,int position) {
  dispatch("k",new Class[]{RecyclerView$D.class,int.class},new Object[]{holder,position},()-> {
   nativeTranslation=d;nativePronunciation=e;
   if(failBind)throw new IllegalStateException("native bind failed");
   b row=(b)holder;
   if(row.v==null) { b0(p.value.line.original,0,((q8.p9)row.u).a0,1,0,false,1);row.v=p.value.line.original; }
  });
 }
 public void l(RecyclerView$D holder,int position,List<?> changes) {
  dispatch("l",new Class[]{RecyclerView$D.class,int.class,List.class},new Object[]{holder,position,changes},()-> {
   nativeTranslation=d;nativePronunciation=e;
  });
 }
}
