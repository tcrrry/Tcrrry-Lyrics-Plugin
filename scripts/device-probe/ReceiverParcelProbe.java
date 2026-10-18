import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.ResultReceiver;

/** Device-only transport regression: no installed app, host restart or model download. */
public final class ReceiverParcelProbe {
    private static ResultReceiver decode(ResultReceiver receiver) {
        Parcel parcel=Parcel.obtain();
        try {
            new Intent("probe").putExtra("reply",receiver).writeToParcel(parcel,0);
            parcel.setDataPosition(0);
            Intent received=Intent.CREATOR.createFromParcel(parcel);
            received.setExtrasClassLoader(new ClassLoader(ResultReceiver.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                    if(name.startsWith("ReceiverParcelProbe")) throw new ClassNotFoundException(name);
                    return super.loadClass(name,resolve);
                }
            });
            return received.getParcelableExtra("reply");
        } finally { parcel.recycle(); }
    }
    public static void main(String[] args) {
        Looper.prepare();
        Handler handler=new Handler(Looper.myLooper());
        boolean[] delivered={false};
        ResultReceiver sender=new ResultReceiver(handler) {
            @Override protected void onReceiveResult(int code,Bundle data) {
                if(code!=37 || !"ok".equals(data.getString("value"))) throw new AssertionError("Wrong callback");
                delivered[0]=true;
                System.out.println("PASS: framework receiver crosses isolated loader and delivers Binder callback");
                Looper.myLooper().quit();
            }
        };
        boolean reproduced=false;
        try { decode(sender); } catch(android.os.BadParcelableException expected) { reproduced=true; }
        if(!reproduced) throw new AssertionError("Old crash not reproduced");
        System.out.println("PASS: old anonymous Parcelable reproduces BadParcelableException");
        Parcel parcel=Parcel.obtain();
        ResultReceiver wire;
        try {
            sender.writeToParcel(parcel,0);parcel.setDataPosition(0);
            wire=ResultReceiver.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
        if(wire.getClass()!=ResultReceiver.class) throw new AssertionError("Sender class leaked");
        Bundle value=new Bundle();value.putString("value","ok");decode(wire).send(37,value);
        handler.postDelayed(() -> { if(!delivered[0]) throw new AssertionError("Callback timeout"); },3000);
        Looper.loop();
        if(!delivered[0]) throw new AssertionError("Missing callback");
    }
}
