package com.tcrrry.lyrics.plugin

import com.tcrrry.lyrics.engine.hook.NativeLyricsHostContract
import com.tcrrry.lyrics.engine.hook.AppleMusicProfiles
import com.tcrrry.lyrics.engine.hook.TargetBuild
import dev.amenhancer.plugin.api.PluginContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class NativeLyricsHostContractTest {
    @Before fun setup() { TestPluginContext.attach() }
    @After fun cleanup() { TestPluginContext.current.close() }
    private fun host(code: Long) {
        val original = TestPluginContext.current
        val context = object: PluginContext by original { override fun getHostVersionCode() = code }
        PluginLifetime::class.java.getDeclaredField("context").apply { isAccessible=true }.set(null,context)
    }
    @Test fun originalHostKeepsAllPreviouslyAcceptedNames() {
        assertEquals("ja.i0",NativeLyricsHostContract.className("ja.i0"))
        assertEquals("n0",NativeLyricsHostContract.fieldName("com.apple.android.music.player.fragment.PlayerLyricsViewFragment","n0"))
        assertEquals("V",NativeLyricsHostContract.fieldName("com.apple.android.music.common.views.FullWidthAlphaGradientFlexboxLayout","V"))
    }
    @Test fun newHostUsesExactMetadataAdapterAndGradientNames() {
        host(1609)
        assertEquals("z3.y",NativeLyricsHostContract.className("z3.w"))
        assertEquals("ja.h0",NativeLyricsHostContract.className("ja.i0"))
        assertEquals("com.apple.android.music.player.f1",NativeLyricsHostContract.className("com.apple.android.music.player.n1"))
        assertEquals("l0",NativeLyricsHostContract.fieldName("com.apple.android.music.player.fragment.PlayerLyricsViewFragment","n0"))
        assertEquals("n0",NativeLyricsHostContract.fieldName("com.apple.android.music.player.fragment.PlayerLyricsViewFragment","p0"))
        assertEquals("T",NativeLyricsHostContract.fieldName("com.apple.android.music.common.views.FullWidthAlphaGradientFlexboxLayout","V"))
        assertEquals("U",NativeLyricsHostContract.fieldName("com.apple.android.music.common.views.FullWidthAlphaGradientFlexboxLayout","W"))
    }
    @Test fun unrelatedMembersAndFutureBuildsNeverReceiveAliases() {
        host(1609)
        assertEquals("n0",NativeLyricsHostContract.fieldName("unrelated.Type","n0"))
        assertEquals("unrelated.Type",NativeLyricsHostContract.className("unrelated.Type"))
        host(1610)
        assertEquals("ja.i0",NativeLyricsHostContract.className("ja.i0"))
    }
    @Test fun bothProfilesResolveTheirOwnCompleteMetadataContracts() {
        val original=AppleMusicProfiles.match(TargetBuild("com.apple.android.music","7.0.0-beta",1606))!!
        val newer=AppleMusicProfiles.match(TargetBuild("com.apple.android.music","7.0.0-beta",1609))!!
        assertEquals(listOf("z3.w"),original.methodContracts.getValue("metadata-to-playback-item-method").parameters)
        assertEquals(listOf("z3.y"),newer.methodContracts.getValue("metadata-to-playback-item-method").parameters)
        assertEquals("com.apple.android.music.player.fragment.m",newer.fieldContracts.getValue("lyrics-current-item-field").owner)
    }
}
