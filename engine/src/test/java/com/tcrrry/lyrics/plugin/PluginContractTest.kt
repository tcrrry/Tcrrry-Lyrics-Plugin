package com.tcrrry.lyrics.plugin

import android.os.Looper
import com.tcrrry.lyrics.engine.hook.*
import dev.amenhancer.plugin.api.PluginCall
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class PluginContractTest {
    @Before fun attach()=TestPluginContext.attach()
    @After fun stop()=TestPluginContext.current.close()
    class Host { fun method(arg:String):String=arg }
    private val method=Host::class.java.getDeclaredMethod("method",String::class.java)
    @Test fun adapterPreservesNestedInvocationExtrasAndOriginalFailure() {
        ModernXposedRuntime.hookMethod(method,object:ModernMethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam) { param.extras["arg"]=param.args[0];param.args[0]="rewritten" }
            override fun afterHookedMethod(param:MethodHookParam) {
                if(param.throwable==null) param.result=param.extras["arg"].toString()+param.result
            }
        })
        val hook=TestPluginContext.current.callbacks[method]!!
        val outer=PluginCall(method,Host(),arrayOf("outer"),null,null);hook.before(outer)
        val inner=PluginCall(method,Host(),arrayOf("inner"),null,null);hook.before(inner)
        assertEquals("rewritten",inner.arguments[0])
        val innerAfter=PluginCall(method,inner.receiver,inner.arguments,"!",null);hook.after(innerAfter)
        assertEquals("inner!",innerAfter.result)
        val error=IllegalStateException("original")
        val outerAfter=PluginCall(method,outer.receiver,outer.arguments,null,error);hook.after(outerAfter)
        assertSame(error,outerAfter.throwable);assertFalse(outerAfter.isOutcomeChanged)
    }
    @Test fun adapterCanShortCircuitThenRecoverExceptionExplicitly() {
        ModernXposedRuntime.hookMethod(method,object:ModernMethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam) { param.result="early" }
            override fun afterHookedMethod(param:MethodHookParam) { param.result="recovered" }
        })
        val hook=TestPluginContext.current.callbacks[method]!!
        val call=PluginCall(method,Host(),arrayOf("arg"),null,null);hook.before(call)
        assertTrue(call.isOutcomeChanged);assertEquals("early",call.result)
        val after=PluginCall(method,Host(),call.arguments,null,IllegalStateException());hook.after(after)
        assertNull(after.throwable);assertEquals("recovered",after.result)
    }
    @Test fun closedScopeDoesNotMutateCallAndClosesSubscriptions() {
        val scope=HookRegistrationScope();var closed=false;scope.onClose { closed=true }
        ModernXposedRuntime.hookMethod(method,object:ModernMethodHook(){override fun beforeHookedMethod(param:MethodHookParam){param.result="bad"}},scope)
        val hook=TestPluginContext.current.callbacks[method]!!
        val call=PluginCall(method,Host(),arrayOf("arg"),"host",null);hook.before(call);assertFalse(call.isOutcomeChanged)
        scope.activate();scope.close();hook.before(call);assertFalse(call.isOutcomeChanged);assertTrue(closed)
    }
    @Test fun failingBeforeDoesNotPoisonNextInvocation() {
        var fail=true
        ModernXposedRuntime.hookMethod(method,object:ModernMethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){if(fail)throw IllegalStateException("callback");param.extras["x"]=param.args[0]}
            override fun afterHookedMethod(param:MethodHookParam){param.result=param.extras["x"]}
        })
        val hook=TestPluginContext.current.callbacks[method]!!
        try { hook.before(PluginCall(method,Host(),arrayOf("bad"),null,null));fail("Expected exception") } catch(_:IllegalStateException){}
        fail=false
        val call=PluginCall(method,Host(),arrayOf("good"),null,null);hook.before(call);hook.after(call);assertEquals("good",call.result)
    }
    @Test fun stopCancelsHandlersAndExecutorAndClosesScopes() {
        val handler=PluginHandler();var invoked=false;handler.postDelayed({invoked=true},10)
        val executor=PluginExecutors.newSingleThreadExecutor()
        val scope=HookRegistrationScope();scope.activate()
        ModernXposedRuntime.hookMethod(method,object:ModernMethodHook(){},scope)
        TestPluginContext.current.close();Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        assertFalse(invoked);assertTrue(executor.isShutdown);assertFalse(scope.isActive);assertFalse(PluginLifetime.alive)
    }
    @Test fun nestedSettingsDialogClosesWithSdkLifetime() {
        val activity=org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val dialog=com.tcrrry.lyrics.engine.ui.SettingsUiTheme.dialogBuilder(activity).setMessage("独立设置").show()
        assertTrue(dialog.isShowing);TestPluginContext.current.close();assertFalse(dialog.isShowing)
    }
    @Test fun settingsPersistTypesAndStayInsideSdkDirectory() {
        val file=File(TestPluginContext.current.dataDirectory,"preferences/test.json")
        val prefs=FilePreferences(file)
        assertTrue(prefs.edit().putString("s","值").putInt("i",3).putLong("l",3_000_000_000L).putFloat("f",1.5f).putBoolean("b",true).putStringSet("set",mutableSetOf("a","b")).commit())
        val restored=FilePreferences(file)
        assertEquals("值",restored.getString("s",null));assertEquals(3,restored.getInt("i",0));assertEquals(3_000_000_000L,restored.getLong("l",0));assertEquals(1.5f,restored.getFloat("f",0f),0f)
        val copy=restored.getStringSet("set",null)!!;copy.clear();assertEquals(2,restored.getStringSet("set",null)!!.size)
        assertTrue(restored.edit().remove("i").commit());assertFalse(FilePreferences(file).contains("i"))
    }
    @Test fun failedAtomicSaveDoesNotPublishUnpersistedSettings() {
        val parent=File(TestPluginContext.current.dataDirectory,"not-directory").apply { writeText("file") }
        val prefs=FilePreferences(File(parent,"bad.json"))
        assertFalse(prefs.edit().putBoolean("flag",true).commit());assertFalse(prefs.contains("flag"))
    }
    @Test fun standaloneJapaneseDictionaryAndAllProfileAssetsAreLoadable() {
        assertEquals("kimi wa toukyou e iku",com.tcrrry.lyrics.engine.lyrics.JapanesePronunciationSupplement.reading("君は東京へ行く"))
        val profiles=com.tcrrry.lyrics.host.applemusic.AppleMusicHostProfiles.all
        assertNotNull(profiles.singleOrNull { it.versionCode==1606L })
    }
    @Test fun oldOrUnrelatedHostFailsBeforeAnyHookOrDataAccess() {
        val fake=TestPluginContext()
        val incompatible=object:dev.amenhancer.plugin.api.PluginContext by fake {override fun getHostVersionCode()=1599L}
        try { LyricsPlugin().onLoad(incompatible);fail("Expected incompatibility") } catch(_:dev.amenhancer.plugin.api.PluginUnsupportedException){}
        assertTrue(fake.callbacks.isEmpty());fake.close()
    }
}
