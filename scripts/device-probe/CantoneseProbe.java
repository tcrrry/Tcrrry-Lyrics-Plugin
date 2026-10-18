import android.icu.text.Transliterator;
import dalvik.system.DexClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.lang.reflect.*;
import java.util.*;

/** App-process-only dictionary test; no host process attachment or app install. */
public final class CantoneseProbe {
    public static void main(String[] args) throws Exception {
        ClassLoader loader=new DexClassLoader(args[0],args[1],null,ClassLoader.getSystemClassLoader());
        Class<?> function=loader.loadClass("kotlin.jvm.functions.Function1");
        Transliterator simplify=Transliterator.getInstance("Traditional-Simplified");
        Object converter=Proxy.newProxyInstance(loader,new Class<?>[]{function},(proxy,method,values) -> {
            if(method.getName().equals("invoke")) return simplify.transliterate((String)values[0]);
            if(method.getName().equals("toString")) return "ProbeSimplifier";
            throw new UnsupportedOperationException(method.getName());
        });
        List<String> dictionaries=Arrays.asList(new String(Files.readAllBytes(Paths.get(args[2])),java.nio.charset.StandardCharsets.UTF_8),new String(Files.readAllBytes(Paths.get(args[3])),java.nio.charset.StandardCharsets.UTF_8));
        Class<?> type=loader.loadClass("com.tcrrry.lyrics.engine.lyrics.LanguagePronunciationSupplement$Cantonese");
        Constructor<?> constructor=type.getDeclaredConstructor(List.class,function);constructor.setAccessible(true);
        long started=System.nanoTime();Object dictionary=constructor.newInstance(dictionaries,converter);
        System.out.println("Dictionary initialization ms="+(System.nanoTime()-started)/1000000);
        Method generate=type.getMethod("units",String.class);
        Method align=type.getMethod("alignedUnits",String.class,String.class);
        String text="不要不要假设我知道";
        String reading="bat1 jiu3 bat1 jiu3 gaa2 cit3 ngo5 zi1 dou6";
        List<?> units=(List<?>)generate.invoke(dictionary,text);
        if(units==null || units.size()!=text.codePointCount(0,text.length())) throw new AssertionError("Missing generated units");
        StringBuilder joined=new StringBuilder();List<String> display=new ArrayList<>();
        for(Object unit:units) {
            int start=(Integer)unit.getClass().getMethod("getStart").invoke(unit);
            int end=(Integer)unit.getClass().getMethod("getEnd").invoke(unit);
            String phonetic=(String)unit.getClass().getMethod("getText").invoke(unit);
            if(phonetic.matches(".*[0-9].*")) throw new AssertionError("Tone digit leaked");
            joined.append(text.substring(start,end));display.add(phonetic);
        }
        if(!text.contentEquals(joined)) throw new AssertionError("Character offsets changed");
        List<?> nativeUnits=(List<?>)align.invoke(dictionary,text,reading);
        if(nativeUnits==null || nativeUnits.size()!=units.size()) throw new AssertionError("Provider alignment failed");
        if(align.invoke(dictionary,"你好","hou2 nei5")!=null) throw new AssertionError("Wrong syllables accepted");
        List<?> mixed=(List<?>)generate.invoke(dictionary,"你好 Hello!");
        if(mixed==null || mixed.size()!=3) throw new AssertionError("Mixed line lost");
        if(args.length>4) {
            int checked=0,aligned=0,providerAligned=0,generatedRows=0;
            for(String line:new String(Files.readAllBytes(Paths.get(args[4])),java.nio.charset.StandardCharsets.UTF_8).split("\\n")) {
                String[] pair=line.split("\\t",2);if(pair.length!=2) continue;
                checked++;if(generate.invoke(dictionary,pair[0])!=null) generatedRows++;
                if(align.invoke(dictionary,pair[0],pair[1])!=null) aligned++;
                if(type.getMethod("providerUnits",String.class,String.class).invoke(dictionary,pair[0],pair[1])!=null) providerAligned++;
            }
            System.out.println("Generated offline rows="+generatedRows+"; Actual QQ track rows="+checked+", standard Jyutping="+aligned+", explicit Cantonese provider alignment="+providerAligned+", safe whole-line fallback="+(checked-providerAligned));
        }
        System.out.println("PASS: generated "+units.size()+" toneless readings with exact character offsets: "+String.join(" ",display));
        System.out.println("PASS: provider Jyutping aligned; reversed syllables rejected; mixed Latin line retained");
    }
}
