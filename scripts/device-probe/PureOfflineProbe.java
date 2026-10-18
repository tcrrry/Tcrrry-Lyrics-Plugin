import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** app_process-only probe; no app install, no host process attachment, no companion IPC. */
public final class PureOfflineProbe {
    public static void main(String[] args) throws Exception {
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object activityThread = thread.getMethod("systemMain").invoke(null);
        Context system = (Context) thread.getMethod("getSystemContext").invoke(activityThread);
        Context shell = system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        android.app.Application application = android.app.Instrumentation.newApplication(android.app.Application.class,shell);
        java.lang.reflect.Field initial = thread.getDeclaredField("mInitialApplication"); initial.setAccessible(true);
        initial.set(activityThread,application);
        Context attributedShell = system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        java.lang.reflect.Field op = attributedShell.getClass().getDeclaredField("mOpPackageName"); op.setAccessible(true);
        op.set(attributedShell,"com.android.shell");
        java.lang.reflect.Field attribution = attributedShell.getClass().getDeclaredField("mAttributionSource"); attribution.setAccessible(true);
        attribution.set(attributedShell,new android.content.AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build());
        Context context = new ContextWrapper(attributedShell) {
            @Override public Context getApplicationContext() { return this; }
        };
        System.out.println("PROBE_PACKAGE="+context.getPackageName()+" OP="+context.getOpPackageName());
        new Thread(() -> {
            try {
                File root = new File(args[3]); root.mkdirs();
                File odex = new File(root,"odex"); odex.mkdirs();
                ClassLoader engine = new DexClassLoader(args[0], odex.getPath(), null, PureOfflineProbe.class.getClassLoader());
                Class<?> staged = engine.loadClass("com.tcrrry.lyrics.plugin.StagedOfflineRuntime");
                Object payload = staged.getConstructor(File.class,File.class,File.class).newInstance(new File(args[1]),new File(args[2]),odex);
                ClassLoader runtimeLoader = (ClassLoader) engine.loadClass("com.tcrrry.lyrics.plugin.OfflineRuntimeClassLoader")
                    .getConstructor(staged,ClassLoader.class).newInstance(payload,engine);
                Class<?> runtime = runtimeLoader.loadClass("com.tcrrry.lyrics.offline.runtime.PluginTranslationRuntime");
                Object instance = runtime.getConstructor(Context.class,File.class,File.class,File.class)
                    .newInstance(context,new File(root,"model-data"),new File(args[1]),new File(args[5]));
                System.out.println("RUNTIME_READY classLoader="+runtime.getClassLoader().getClass().getSimpleName());
                if(args[4].equals("diagnose")) {
                    diagnose(runtimeLoader,instance);
                } else if(args[4].equals("download")) {
                    Consumer<String> complete = error -> finish(instance,error==null?"DOWNLOAD_OK":"DOWNLOAD_ERROR: "+error,error==null?0:2);
                    runtime.getMethod("downloadModels",String.class,Consumer.class).invoke(instance,"en",complete);
                } else if(args[4].equals("translate")) {
                    Object text = runtime.getMethod("translate",String.class,String.class).invoke(instance,"en","Hello, world");
                    finish(instance,"TRANSLATION_OK: "+text,0);
                } else {
                    BiConsumer<Set<String>,String> complete = (models,error) -> finish(instance,error==null?"MODELS_OK: "+models:"MODELS_ERROR: "+error,error==null?0:2);
                    runtime.getMethod("queryModels",BiConsumer.class).invoke(instance,complete);
                }
            } catch(Throwable error) {
                while(error instanceof InvocationTargetException) error=((InvocationTargetException)error).getTargetException();
                error.printStackTrace(); System.exit(1);
            }
        },"PureOfflineProbe").start();
        Looper.loop();
    }
    private static void diagnose(ClassLoader loader,Object instance) throws Exception {
        Class<?> mlkit=loader.loadClass("com.google.mlkit.common.sdkinternal.MlKitContext");
        Object global=mlkit.getMethod("getInstance").invoke(null);
        Class<?> registry=loader.loadClass("com.google.mlkit.nl.translate.internal.zzg");
        Object factory=mlkit.getMethod("get",Class.class).invoke(global,registry);
        Class<?> builder=loader.loadClass("com.google.mlkit.nl.translate.TranslateRemoteModel$Builder");
        Object model=builder.getMethod("build").invoke(builder.getConstructor(String.class).newInstance("zh"));
        Object manager=registry.getMethod("zza",loader.loadClass("com.google.mlkit.nl.translate.TranslateRemoteModel")).invoke(factory,model);
        Class<?> conditionsBuilder=loader.loadClass("com.google.mlkit.common.model.DownloadConditions$Builder");
        Object conditions=conditionsBuilder.getMethod("build").invoke(conditionsBuilder.getConstructor().newInstance());
        Class<?> executor=loader.loadClass("com.google.mlkit.common.sdkinternal.MLTaskExecutor");
        android.os.Handler handler=(android.os.Handler)executor.getMethod("getHandler").invoke(executor.getMethod("getInstance").invoke(null));
        handler.post(() -> {
            try {
                java.lang.reflect.Method raw=manager.getClass().getDeclaredMethod("zza",loader.loadClass("com.google.mlkit.common.model.DownloadConditions"));raw.setAccessible(true);
                Object task=raw.invoke(manager,conditions);
                Class<?> listener=loader.loadClass("com.google.android.gms.tasks.OnCompleteListener");
                Class<?> taskType=loader.loadClass("com.google.android.gms.tasks.Task");
                Object callback=java.lang.reflect.Proxy.newProxyInstance(loader,new Class[]{listener},(proxy,method,args) -> {
                    if(method.getName().equals("onComplete")) {
                        Exception error=(Exception)taskType.getMethod("getException").invoke(args[0]);
                        if(error!=null) error.printStackTrace();
                        finish(instance,error==null?"RAW_DOWNLOAD_OK":"RAW_DOWNLOAD_FAILED",error==null?0:3);
                    }
                    return null;
                });
                taskType.getMethod("addOnCompleteListener",listener).invoke(task,callback);
            } catch(Throwable error) { error.printStackTrace();System.exit(3); }
        });
    }
    private static void finish(Object instance,String message,int code) {
        try { ((AutoCloseable)instance).close(); } catch(Exception e) { e.printStackTrace(); }
        System.out.println(message); System.out.flush(); System.exit(code);
    }
}
