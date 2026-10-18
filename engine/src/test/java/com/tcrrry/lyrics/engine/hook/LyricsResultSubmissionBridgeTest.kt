package com.tcrrry.lyrics.engine.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import com.tcrrry.lyrics.engine.CurrentSongDetails
import com.tcrrry.lyrics.plugin.TestPluginContext
import dev.amenhancer.plugin.api.PluginCall
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Method

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LyricsResultSubmissionBridgeTest {
    @org.junit.Before fun setup() { TestPluginContext.attach() }
    @org.junit.After fun cleanup() { TestPluginContext.current.close() }
    class Item(private val id:String) { fun getId()=id }
    class Native(val id:Long) {
        var script:String?=null
        fun getAdamId()=id
        fun setScript(value:String):Boolean { script=value;return true }
    }
    class Pointer(val native:Native,val document:String?=null) {
        var alive=true
        fun get()=native
    }
    class Results {
        private var stored:Any?=null
        private var pending:Any?=null
        var writes=0
        var observer:((Any?)->Unit)?=null
        var failAfterStore=false
        fun getValue():Any?=stored
        fun setValue(value:Any?) { dispatch(this,"setValue",arrayOf(value)) { args ->
            stored=args[0];writes++;observer?.invoke(stored)
            if(failAfterStore) throw IllegalStateException("observer failure after storage")
            null
        } }
        fun postValue(value:Any?) { dispatch(this,"postValue",arrayOf(value)) { args ->
            val schedule=pending==null;pending=args[0]
            if(schedule) Handler(Looper.getMainLooper()).post { val next=pending;pending=null;setValue(next) }
            null
        } }
    }
    class Model {
        @JvmField val results=Results()
        var indexed:Pointer?=null;var builds=0
        val order=mutableListOf<String>()
        fun loadLyrics(item:Item) { dispatch(this,"loadLyrics",arrayOf(item)) { null } }
        fun buildIndex(pointer:Pointer?) { dispatch(this,"buildIndex",arrayOf(pointer)) { args ->
            indexed=args[0] as? Pointer;builds++;order+="index";null
        } }
        fun language()="zh-CN"
        fun clear() { dispatch(this,"clear",emptyArray()) { null } }
    }
    class Fragment(@JvmField val model:Model,var id:Long) {
        private val content=View(RuntimeEnvironment.getApplication())
        fun resume() { dispatch(this,"resume",emptyArray()) { null } }
        fun update() { dispatch(this,"update",emptyArray()) { null } }
        fun getView():View=content
    }
    companion object {
        private fun dispatch(receiver:Any,name:String,args:Array<Any?>,body:(Array<Any?>)->Any?):Any? {
            val method=receiver.javaClass.declaredMethods.single { it.name==name }
            val callback=TestPluginContext.current.callbacks[method]
            val call=PluginCall(method,receiver,args,null,null)
            callback?.before(call)
            if(!call.isOutcomeChanged) try { call.returnResult(body(call.arguments)) } catch(error:Throwable) { call.throwException(error) }
            callback?.after(call)
            call.throwable?.let { throw it }
            return call.result
        }
    }
    private class Fixture(val current:CurrentSongIdentityCache,val model:Model,val fragment:Fragment,
        val bridge:LyricsResultSubmissionBridge,val scope:HookRegistrationScope,
        val choices:MutableMap<Long,Pointer>,val originals:MutableList<Any?>,val installed:MutableList<Pointer?>)
    private fun fixture():Fixture {
        val loader=javaClass.classLoader!!
        val section=JSONObject().put("methods",JSONObject()).put("fields",JSONObject())
        val methods=linkedMapOf(
            "resultSet" to Results::class.java.getDeclaredMethod("setValue",Any::class.java),
            "resultPost" to Results::class.java.getDeclaredMethod("postValue",Any::class.java),
            "resultValue" to Results::class.java.getDeclaredMethod("getValue"),
            "fragmentResume" to Fragment::class.java.getDeclaredMethod("resume"),
            "buildIndex" to Model::class.java.getDeclaredMethod("buildIndex",Pointer::class.java),
            "loadLyrics" to Model::class.java.getDeclaredMethod("loadLyrics",Item::class.java),
            "systemLyricsLanguage" to Model::class.java.getDeclaredMethod("language"),
            "pointerGet" to Pointer::class.java.getDeclaredMethod("get"),
            "pointerAdamId" to Native::class.java.getDeclaredMethod("getAdamId"),
            "pointerScript" to Native::class.java.getDeclaredMethod("setScript",String::class.java),
            "clear" to Model::class.java.getDeclaredMethod("clear"))
        methods.forEach { (role,m) -> section.getJSONObject("methods").put(role,JSONObject()
            .put("owner",m.declaringClass.name).put("name",m.name).put("parameters",org.json.JSONArray(m.parameterTypes.map { it.name }))
            .put("returns",m.returnType.name).put("static",false)) }
        mapOf("fragmentViewModel" to Fragment::class.java.getDeclaredField("model"),
            "lyricsResult" to Model::class.java.getDeclaredField("results")).forEach { (role,f) ->
            section.getJSONObject("fields").put(role,JSONObject().put("owner",f.declaringClass.name).put("name",f.name).put("type",f.type.name))
        }
        val members=FragmentProfileMembers(loader,section,"test",FragmentMemberRequirements(methods.keys,setOf("fragmentViewModel","lyricsResult")))
        val contract=LyricsResultSubmissionContract::class.java.declaredConstructors.single { !it.isSynthetic }.apply { isAccessible=true }
            .newInstance(Model::class.java,members,"test-exact",loader) as LyricsResultSubmissionContract
        val current=CurrentSongIdentityCache();current.publish(Item("1"),CurrentSongDetails(1))
        val choices=mutableMapOf<Long,Pointer>();val originals=mutableListOf<Any?>();val installed=mutableListOf<Pointer?>()
        val bridge=LyricsResultSubmissionBridge::class.java.declaredConstructors.single { !it.isSynthetic }.apply { isAccessible=true }.newInstance(
            contract,current,{ any:Any? -> (any as? Fragment)?.id },
            { id:Long,original:Any? -> originals.add(original);choices[id] },
            { any:Any? -> (any as? Pointer)?.alive==true },{ any:Any -> (any as Pointer).document },
            { _:String -> },Handler(Looper.getMainLooper())) as LyricsResultSubmissionBridge
        val scope=HookRegistrationScope()
        val install=LyricsResultSubmissionBridge::class.java.getDeclaredMethod("install",HookRegistrationScope::class.java,Method::class.java).apply { isAccessible=true }
        assertEquals(true,install.invoke(bridge,scope,Fragment::class.java.getDeclaredMethod("update")));scope.activate()
        val model=Model();val fragment=Fragment(model,1)
        model.results.observer={ value ->
            val pointer=(value as Pair<*,*>).first as? Pointer
            model.order+="observe";installed+=pointer
            bridge.onInstalled(fragment,pointer)
        }
        return Fixture(current,model,fragment,bridge,scope,choices,originals,installed)
    }
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun native(id:Long)=Pointer(Native(id))
    private fun start(f:Fixture,id:Long=1) {
        f.model.loadLyrics(Item(id.toString()));f.bridge.attach(f.fragment)
    }
    @Test fun replacementBuildsIndexBeforeNativeObserverAndKeepsDocumentUnmodified() {
        val f=fixture();val original=native(1)
        val ttml="<!--tcrrry-test--><tt timing=\"Word\"><p><span begin=\"1s\" end=\"2s\">頇橅暅</span></p><text>hwanhan</text><text>鏄庝寒</text></tt>"
        val replacement=Pointer(Native(1),ttml);f.choices[1]=replacement
        start(f);f.model.results.setValue(original to null)
        assertSame(replacement,(f.model.results.getValue() as Pair<*,*>).first)
        assertSame(replacement,f.model.indexed)
        assertEquals(listOf("index","observe"),f.model.order)
        assertEquals(ttml,replacement.document);assertEquals("zh-CN",replacement.native.script)
        assertSame(original,f.originals.last());assertSame(replacement,f.installed.last())
        // Native code often builds the same index again after delivering the result.
        f.model.buildIndex(original);assertEquals(1,f.model.builds)
        f.scope.close()
    }
    @Test fun lateReadyResultUsesContainerAndDeduplicatesEquivalentDocuments() {
        val f=fixture();val original=native(1);start(f);f.model.results.setValue(original to null)
        val replacement=Pointer(Native(1),"same TTML");f.choices[1]=replacement
        f.bridge.onReplacementPublished(1);idle()
        assertSame(replacement,f.installed.last());assertEquals(2,f.model.results.writes)
        f.choices[1]=Pointer(Native(1),"same TTML")
        repeat(3) { f.bridge.onReplacementPublished(1) };idle()
        assertEquals(2,f.model.results.writes);assertSame(original,f.originals.last())
        // Explicit native-source choice goes through exactly the same result path.
        f.choices[1]=original;f.bridge.onReplacementPublished(1);idle()
        assertSame(original,f.installed.last());assertSame(original,f.model.indexed)
        f.scope.close()
    }
    @Test fun rapidSongChangeRejectsOldDocumentAndQueuedEmptyFailure() {
        val f=fixture();start(f);f.model.results.setValue(native(1) to null)
        f.model.results.postValue(null to "old failure")
        f.current.publish(Item("2"),CurrentSongDetails(2));f.bridge.onSongChanged(2)
        f.fragment.id=2;f.model.loadLyrics(Item("2"));f.fragment.update()
        val replacement=Pointer(Native(2),"second song");f.choices[2]=replacement
        f.model.results.setValue(native(2) to null);val count=f.model.results.writes
        f.model.results.setValue(native(1) to null);idle()
        assertEquals(count,f.model.results.writes);assertSame(replacement,f.installed.last())
        f.model.results.setValue(null to "late error")
        assertSame(replacement,(f.model.results.getValue() as Pair<*,*>).first)
        assertSame(replacement,f.model.indexed);f.scope.close()
    }
    @Test fun nativePostValueCoalescesAndForeignContainersRemainUntouched() {
        val f=fixture();start(f)
        val first=native(1);val last=native(1)
        f.model.results.postValue(first to null);f.model.results.postValue(last to null);idle()
        assertEquals(1,f.model.results.writes);assertSame(last,f.installed.last())
        val unrelated=Results();unrelated.setValue("untouched")
        assertEquals("untouched",unrelated.getValue());f.scope.close()
    }
    @Test fun observerFailureReconcilesStoredPointerWithoutSecondRollbackWrite() {
        val f=fixture();start(f);f.model.results.setValue(native(1) to null)
        val replacement=Pointer(Native(1),"new document");f.choices[1]=replacement
        f.model.results.failAfterStore=true
        f.bridge.onReplacementPublished(1);idle()
        assertSame(replacement,(f.model.results.getValue() as Pair<*,*>).first)
        assertSame(replacement,f.model.indexed);assertEquals(2,f.model.results.writes)
        f.scope.close()
    }
    @Test fun managedJapaneseKoreanAndCantoneseAuxiliaryTracksSurviveResultPublication() {
        val f=fixture();start(f)
        for ((original,reading,translation) in listOf(
            Triple("僕にも","bo ku ni mo","我也一样"),
            Triple("환한 빛 조명이","hwanhan bich jomyeong-i","耀眼的灯光"),
            Triple("不要","ba yiu","不要"))) {
            if(original=="不要") com.tcrrry.lyrics.engine.lyrics.LanguagePronunciationSupplement.rememberChineseProviderRows(listOf(0L to original),listOf(0L to reading))
            val marker=com.tcrrry.lyrics.engine.lyrics.DesktopLyricsPresentation("QQ音乐",true,true,false,false,true).marker()
            val ttml=marker+"<tt timing=\"Word\"><head><metadata><transliterations><transliteration><text for=\"L1\">$reading</text></transliteration></transliterations>"+
                "<translations><translation><text for=\"L1\">$translation</text></translation></translations></metadata></head>"+
                "<body><div><p itunes:key=\"L1\"><span begin=\"1s\" end=\"2s\">$original</span></p></div></body></tt>"
            val prepared=Pointer(Native(1),ttml)
            NativeLyricsPronunciationSubtitle.remember(prepared,ttml)
            f.choices[1]=prepared;f.model.results.setValue(native(1) to null)
            val installed=requireNotNull(f.installed.last())
            assertEquals(ttml,installed.document)
            assertTrue(NativeLyricsPronunciationSubtitle.isManagedWord(installed))
            assertFalse("Original remains the primary highlight",NativeLyricsPronunciationSubtitle.isPronunciationPrimary(installed))
            val tracks=requireNotNull(NativeLyricsPronunciationSubtitle.lineSubtitles(installed,original))
            assertEquals(reading,tracks.pronunciation);assertEquals(translation,tracks.translation)
            val aligned=requireNotNull(com.tcrrry.lyrics.engine.lyrics.JapaneseRubyAlignment.align(original,tracks.pronunciation!!)) { original }
            assertTrue(aligned.units.size>=2)
            assertSame(installed,f.model.indexed)
        }
        f.scope.close()
    }
    @Test fun sameSongReloadAndModelClearRespectRequestBoundaries() {
        val f=fixture();start(f);f.model.results.setValue(native(1) to null)
        f.model.results.postValue(null to "previous load")
        f.model.loadLyrics(Item("1"))
        val replacement=Pointer(Native(1),"reloaded")
        f.choices[1]=replacement;f.model.results.setValue(native(1) to null)
        val writes=f.model.results.writes;idle()
        assertEquals(writes,f.model.results.writes);assertSame(replacement,f.installed.last())
        f.model.clear();f.bridge.onReplacementPublished(1);idle()
        assertEquals(writes,f.model.results.writes)
        f.scope.close()
    }
    @Test fun simplifiedTogglePublishesNewRenderedMixedLanguageDocumentInBothDirections() {
        val f=fixture();start(f)
        val marker=com.tcrrry.lyrics.engine.lyrics.DesktopLyricsPresentation("QQ音乐",true,true,false,false,true).marker()
        val raw=marker+"<tt xml:lang=\"ko\" xmlns:itunes=\"urn:itunes\"><head><metadata><transliterations><transliteration><text for=\"L1\">Where did you go sou sou do gau ba pung dou</text></transliteration></transliterations></metadata></head>"+
            "<body><div><p itunes:key=\"L1\"><span begin=\"1s\" end=\"2s\">Where did you go 數數多久不碰到</span></p><p>Whoa whoa</p></div></body></tt>"
        val traditional=Pointer(Native(1),raw);f.choices[1]=traditional
        f.model.results.setValue(native(1) to null)
        for(enabled in listOf(true,false,true)) {
            val display=ChineseLyricsDisplay.render(raw,enabled)
            val changed=Pointer(Native(1),display)
            NativeLyricsPronunciationSubtitle.remember(changed,display)
            f.choices[1]=changed;f.bridge.onReplacementPublished(1);idle()
            assertSame("enabled=$enabled display=$display",changed,f.installed.last())
            assertTrue(changed.document!!.contains(if(enabled) "数数多久不碰到" else "數數多久不碰到"))
            assertSame(changed,f.model.indexed)
            val text=if(enabled) "Where did you go 数数多久不碰到" else "Where did you go 數數多久不碰到"
            val reading=requireNotNull(NativeLyricsPronunciationSubtitle.lineSubtitles(changed,text)?.pronunciation)
            val aligned=requireNotNull(com.tcrrry.lyrics.engine.lyrics.JapaneseRubyAlignment.align(text,reading))
            assertTrue(aligned.perCharacter);assertEquals(11,aligned.units.size)
        }
        assertTrue(raw.contains("數數多久不碰到"));f.scope.close()
    }
    @Test fun clearedModelAndStoppedPluginNeverReceiveDelayedWrites() {
        val f=fixture();start(f);f.model.results.setValue(native(1) to null)
        f.choices[1]=Pointer(Native(1),"late");f.bridge.onReplacementPublished(1)
        f.bridge.close();val writes=f.model.results.writes;idle()
        assertEquals(writes,f.model.results.writes)
        f.scope.close();assertTrue(TestPluginContext.current.callbacks.isEmpty())
    }
}
