package com.tcrrry.lyrics.engine.hook

import android.app.Activity
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeLyricsSmoothReturnTest {
    class Calls { fun offset(position: Int, offset: Int) = Unit; fun arrived(view: View, state: Any, action: Any) = Unit }
    @org.junit.Before fun attach() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun close() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun returnSuppressesInstantJumpThenUsesNativeTopOffsetOnArrival() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val root=View(activity);activity.setContentView(root)
        val fragment=Any();val token=Any();val list=Any();val manager=Any();val scroller=Any()
        var started=-1;var animated: Int?=null
        val port=NativeLyricsSmoothReturn({manager},{owner,ptr -> owner===fragment && ptr===token},
            {owner,target -> assertSame(list,owner);started=target},{list},{0},{_,_->-80},
            {_,delta,_->animated=delta})
        val offset=ModernMethodHook.MethodHookParam(Calls::class.java.getMethod("offset",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType),manager,arrayOf(0,20))
        assertTrue(port.run(fragment,list,root,token) { port.redirect(offset) })
        assertEquals(0,started);assertTrue(offset.shouldReturnEarly());assertNull(animated)
        val arrive=ModernMethodHook.MethodHookParam(Calls::class.java.getMethod("arrived",View::class.java,Any::class.java,Any::class.java),scroller,arrayOf(root,Any(),Any()))
        port.arrive(arrive)
        assertEquals(60,animated);assertTrue(arrive.shouldReturnEarly())
    }
    @Test fun ordinaryScrollIsUntouchedAndReplacedSongCannotFinishOldAnimation() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val root=View(activity);activity.setContentView(root)
        val list=Any();val manager=Any();var valid=true;var animations=0
        val port=NativeLyricsSmoothReturn({manager},{_,_->valid},{_,_->},{list},{0},{_,_->-80},{_,_,_->animations++})
        fun offset()=ModernMethodHook.MethodHookParam(Calls::class.java.getMethod("offset",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType),manager,arrayOf(0,20))
        val ordinary=offset();port.redirect(ordinary);assertFalse(ordinary.shouldReturnEarly())
        assertTrue(port.run(Any(),list,root,Any()) { port.redirect(offset()) })
        valid=false
        val arrive=ModernMethodHook.MethodHookParam(Calls::class.java.getMethod("arrived",View::class.java,Any::class.java,Any::class.java),Any(),arrayOf(root,Any(),Any()))
        port.arrive(arrive);assertEquals(0,animations);assertFalse(arrive.shouldReturnEarly())
    }
    @Test fun nativeCallbacksCannotTeleportAheadOfLoopAnimationAndReleaseRestoresThem() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val root=View(activity);activity.setContentView(root)
        val manager=Any();val owner=Any();val token=Any();val list=Any();var starts=0
        val port=NativeLyricsSmoothReturn({manager},{fragment,pointer->fragment===owner && pointer===token},
            {_,_->starts++},{list},{0},{_,_->0},{_,_,_->})
        fun offset()=ModernMethodHook.MethodHookParam(Calls::class.java.getMethod("offset",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType),manager,arrayOf(0,20))
        port.hold(owner,token)
        val native=offset();port.redirect(native)
        assertTrue(native.shouldReturnEarly());assertEquals(0,starts)
        assertTrue(port.run(owner,list,root,token) { port.redirect(offset()) })
        assertEquals(1,starts)
        port.release(owner)
        val released=offset();port.redirect(released);assertFalse(released.shouldReturnEarly())
    }
    @Test fun compactReturnDoesNotStartLongDistanceRecyclerScrolling() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val root=View(activity);activity.setContentView(root)
        val manager=Any();var shortTrips=0;var cancellations=0
        val port=NativeLyricsSmoothReturn({manager},{_,_->true},{_,_->fail("Long distance scrolling must not start")},
            {null},{0},{_,_->0},{_,_,_->},
            {_,_,_,_,target,offset->assertEquals(0,target);assertEquals(20,offset);shortTrips++;true},
            {cancellations++})
        val owner=Any()
        val call=ModernMethodHook.MethodHookParam(Calls::class.java.getMethod("offset",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType),manager,arrayOf(0,20))
        assertTrue(port.run(owner,Any(),root,Any()) { port.redirect(call) })
        assertTrue(call.shouldReturnEarly());assertEquals(1,shortTrips)
        port.release(owner);assertEquals(1,cancellations)
    }
}
