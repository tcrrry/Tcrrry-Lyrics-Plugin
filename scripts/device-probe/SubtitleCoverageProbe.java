import dalvik.system.DexClassLoader;
import java.lang.reflect.*;

/** Read-only app-process test of the shipped auxiliary lookup under Han display conversion. */
public final class SubtitleCoverageProbe {
    public static void main(String[] args) throws Exception {
        ClassLoader loader=new DexClassLoader(args[0],args[1],null,ClassLoader.getSystemClassLoader());
        Class<?> lookup=loader.loadClass("com.tcrrry.lyrics.engine.hook.NativeSubtitleLookup");
        String[] traditional={"難離難捨想抱緊些","茫茫人生好像荒野","常情如此不可推卸","任世間再冷酷想起這單車"};
        String[] simplified={"难离难舍想抱紧些","茫茫人生好像荒野","常情如此不可推卸","任世间再冷酷想起这单车"};
        StringBuilder body=new StringBuilder(),reading=new StringBuilder();
        for(int i=0;i<traditional.length;i++) {
            body.append("<p itunes:key=\"L").append(i).append("\">").append(traditional[i]).append("</p>");
            reading.append("<text for=\"L").append(i).append("\">reading").append(i).append("</text>");
        }
        String ttml="<tt><head><metadata><transliterations><transliteration>"+reading+
            "</transliteration></transliterations></metadata></head><body><div>"+body+"</div></body></tt>";
        Constructor<?> constructor=lookup.getDeclaredConstructor(String.class);constructor.setAccessible(true);
        Object instance=constructor.newInstance(ttml);Method find=lookup.getMethod("forOriginal",String.class);
        int covered=0;
        for(int i=0;i<simplified.length;i++) {
            Object tracks=find.invoke(instance,simplified[i]);
            if(tracks!=null && ("reading"+i).equals(tracks.getClass().getMethod("getPronunciation").invoke(tracks))) covered++;
        }
        System.out.println("Traditional source / simplified display pronunciation coverage="+covered+"/4");
        if(covered!=4) throw new AssertionError("Partial pronunciation coverage");
        System.out.println("PASS: auxiliary lookup retains every screenshot row across Han variants");
    }
}
