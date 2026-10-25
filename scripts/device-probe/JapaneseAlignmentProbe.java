import dalvik.system.DexClassLoader;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.zip.*;

/** Exercise shipped DEX/dictionary in an independent shell process, never attach to the host. */
public final class JapaneseAlignmentProbe {
    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        android.content.Context system = (android.content.Context) activityThread.getMethod("getSystemContext").invoke(thread);
        android.app.Application application = new android.app.Application();
        Method attach = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext",android.content.Context.class);
        attach.setAccessible(true); attach.invoke(application,system);
        ClassLoader loader = new DexClassLoader(args[0] + File.pathSeparator + args[1], args[2], null,
            ClassLoader.getSystemClassLoader());
        ZipFile plugin = new ZipFile(args[3]);
        Class<?> context = loader.loadClass("dev.amenhancer.plugin.api.PluginContext");
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{context}, (self, method, values) -> {
            if (method.getName().equals("openAsset")) {
                ZipEntry entry = plugin.getEntry("assets/" + values[0]);
                if (entry == null) throw new FileNotFoundException(String.valueOf(values[0]));
                return plugin.getInputStream(entry);
            }
            if (method.getName().equals("onClose")) return null;
            if (method.getName().equals("getApplication")) return application;
            if (method.getName().equals("getDataDirectory") || method.getName().equals("getCacheDirectory")) {
                File folder = new File(args[2],"tcrrry-independent-probe");folder.mkdirs();return folder;
            }
            if (method.getName().equals("toString")) return "IndependentAlignmentProbe";
            throw new UnsupportedOperationException(method.getName());
        });
        Class<?> lifetime = loader.loadClass("com.tcrrry.lyrics.plugin.PluginLifetime");
        lifetime.getMethod("attach", context).invoke(lifetime.getField("INSTANCE").get(null), proxy);
        Class<?> alignment = loader.loadClass("com.tcrrry.lyrics.engine.lyrics.JapaneseRubyAlignment");
        Object instance = alignment.getField("INSTANCE").get(null);
        Method align = alignment.getMethod("align", String.class, String.class);
        String[][] cases = {
            {"自分に誓った約束だから", "ji bun ni qi ka 'ta yaku soku da ka la"},
            {"競争社会and I", "kiyou sou xia kai and I"},
            {"戦い続けて一人ぼっちだけど", "tataka i cuzu ke te hito li bo qi da ke do"},
            {"３６ヶ月の日々は", "sa n ju u ro 'k ka ge tsu no hi bi wa"},
            {"片っ方を探す靴下", "ka ta 'p po wo sa ga su ku tsu shi ta"}
        };
        for (String[] row : cases) {
            Object result = align.invoke(instance, row[0], row[1]);
            if (result == null) throw new AssertionError("Alignment failed: " + row[0]);
            List<?> units = (List<?>) result.getClass().getMethod("getUnits").invoke(result);
            StringBuilder actual = new StringBuilder();
            int previous = 0;
            for (Object unit : units) {
                Class<?> type = unit.getClass();
                int start = (Integer) type.getMethod("getStart").invoke(unit);
                int end = (Integer) type.getMethod("getEnd").invoke(unit);
                if (start < previous || end <= start || end > row[0].length()) throw new AssertionError("Invalid original ranges");
                previous = end;
                actual.append(type.getMethod("getReading").invoke(unit));
            }
            if (!letters(row[1]).equals(letters(actual.toString()))) throw new AssertionError("Provider letters changed");
            System.out.println("PASS " + row[0] + " units=" + units.size());
        }
        if (align.invoke(instance,"君light","kimi right") != null) throw new AssertionError("English rewritten");
        if (align.invoke(instance,"一人","hito") != null) throw new AssertionError("Incomplete reading accepted");
        System.out.println("PASS shipped Japanese alignment: 5 screenshot rows and 2 rejection guards");
        plugin.close();
    }
    private static String letters(String text) {
        StringBuilder result = new StringBuilder();
        for (char c : text.toCharArray()) if (Character.isLetterOrDigit(c)) result.append(c);
        return result.toString();
    }
}
