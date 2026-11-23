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
    private fun host(code: Long, versionName: String = "7.0.0-beta") {
        val original = TestPluginContext.current
        val context = object: PluginContext by original {
            override fun getHostVersionCode() = code
            override fun getHostVersionName() = versionName
        }
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
    @Test fun host1613UsesItsOwnWordAdapterReaderAndInheritedPageBindings() {
        host(1613)
        assertEquals("com.apple.android.music.player.x",NativeLyricsHostContract.className("com.apple.android.music.player.A"))
        assertEquals("com.apple.android.music.player.x\$a",NativeLyricsHostContract.className("com.apple.android.music.player.A\$a"))
        assertEquals("com.apple.android.music.player.g1",NativeLyricsHostContract.className("com.apple.android.music.player.n1"))
        assertEquals("com.apple.android.music.ttml.i",NativeLyricsHostContract.className("com.apple.android.music.ttml.j"))
        assertEquals("com.apple.android.music.player.M",NativeLyricsHostContract.className("com.apple.android.music.player.P"))
        assertEquals("V",NativeLyricsHostContract.fieldName("com.apple.android.music.player.fragment.l","X"))
        val profile=AppleMusicProfiles.match(TargetBuild("com.apple.android.music","7.0.0-beta",1613))!!
        assertEquals("com.apple.android.music.player.x",profile.methodContracts.getValue("cjk-karaoke-animation-method").owner)
        assertEquals(listOf("z3.y"),profile.methodContracts.getValue("metadata-to-playback-item-method").parameters)
        host(1614)
        assertEquals("com.apple.android.music.player.A",NativeLyricsHostContract.className("com.apple.android.music.player.A"))
    }
    @Test fun host1614AliasesAreScopedByVersionAndDeclaringAdapter() {
        val map = NativeLyricsHostContract::methodNameForBuild
        val word = "com.apple.android.music.player.x"
        val base = "com.apple.android.music.player.g1"
        val line = "com.apple.android.music.player.S0"
        assertEquals("p0", map(1614, "7.0.0", word, "o0"))
        assertEquals("z", map(1614, "7.0.0", word, "y"))
        assertEquals("l", map(1614, "7.0.0", word, "k"))
        assertEquals("m", map(1614, "7.0.0", line, "l"))
        assertEquals("Q", map(1614, "7.0.0", base, "P"))
        assertEquals("C", map(1614, "7.0.0", base, "B"))
        assertEquals("W", map(1614, "7.0.0", line, "W"))
        assertEquals("o0", map(1613, "7.0.0-beta", word, "o0"))
        assertEquals("o0", map(1614, "7.0.0-beta", word, "o0"))
        assertEquals("o0", map(1614, "7.0.0", "other.Owner", "o0"))
        val profile=AppleMusicProfiles.match(TargetBuild("com.apple.android.music", "7.0.0", 1614))!!
        assertEquals("com.apple.android.music.player.x", profile.methodContracts.getValue("cjk-karaoke-animation-method").owner)
    }
    @Test fun host1622AliasesAndProfileContractsResolveExactly() {
        host(1622, "7.0.1")
        assertEquals("com.apple.android.music.player.A", NativeLyricsHostContract.className("com.apple.android.music.player.A"))
        assertEquals("com.apple.android.music.player.i1", NativeLyricsHostContract.className("com.apple.android.music.player.n1"))
        assertEquals("com.apple.android.music.player.V0", NativeLyricsHostContract.className("com.apple.android.music.player.Y0"))
        assertEquals("com.apple.android.music.player.fragment.Z", NativeLyricsHostContract.className("com.apple.android.music.player.fragment.b0"))
        assertEquals("mc.z", NativeLyricsHostContract.className("lc.A"))
        assertEquals("q8.o9", NativeLyricsHostContract.className("q8.p9"))
        assertEquals("q8.k9", NativeLyricsHostContract.className("q8.l9"))
        assertEquals("m0", NativeLyricsHostContract.fieldName("com.apple.android.music.player.fragment.PlayerLyricsViewFragment", "n0"))
        assertEquals("p0", NativeLyricsHostContract.fieldName("q8.X4", "o0"))
        assertEquals("W", NativeLyricsHostContract.fieldName("com.apple.android.music.player.fragment.m", "X"))
        val map = NativeLyricsHostContract::methodNameForBuild
        assertEquals("p0", map(1622, "7.0.1", "com.apple.android.music.player.A", "o0"))
        assertEquals("z", map(1622, "7.0.1", "com.apple.android.music.player.A", "y"))
        assertEquals("l", map(1622, "7.0.1", "com.apple.android.music.player.A", "k"))
        assertEquals("m", map(1622, "7.0.1", "com.apple.android.music.player.V0", "l"))
        assertEquals("V", map(1622, "7.0.1", "com.apple.android.music.player.V0", "U"))
        assertEquals("Q", map(1622, "7.0.1", "com.apple.android.music.player.i1", "P"))
        assertEquals("p0", map(1622, "7.0.1", "q8.X4", "q0"))
        assertEquals("o0", map(1622, "7.0.1", "q8.X4", "p0"))
        assertEquals("q0", map(1614, "7.0.0", "q8.X4", "q0"))
        val profile = AppleMusicProfiles.match(TargetBuild("com.apple.android.music", "7.0.1", 1622))!!
        assertEquals("com.apple.android.music.player.A", profile.methodContracts.getValue("cjk-karaoke-animation-method").owner)
        assertEquals(listOf("z3.y"), profile.methodContracts.getValue("metadata-to-playback-item-method").parameters)
    }
    @Test fun host1626AliasesAndFollowFieldsResolveExactly() {
        val fragment = "com.apple.android.music.player.fragment.PlayerLyricsViewFragment"
        host(1626, "7.0.3")
        assertEquals("T0", NativeLyricsHostContract.fieldName(fragment, "V0"))
        assertEquals("U0", NativeLyricsHostContract.fieldName(fragment, "W0"))
        assertEquals("U0", NativeLyricsHostContract.shareSuppressFieldName())
        assertEquals("C0", NativeLyricsHostContract.fieldName(fragment, "E0"))
        assertEquals("D0", NativeLyricsHostContract.fieldName(fragment, "F0"))
        assertEquals("E0", NativeLyricsHostContract.fieldName(fragment, "G0"))
        val map = NativeLyricsHostContract::methodNameForBuild
        val base = "com.apple.android.music.player.i1"
        assertEquals("R", map(1626, "7.0.3", base, "Q"))
        assertEquals("D", map(1626, "7.0.3", base, "C"))
        assertEquals("F", map(1626, "7.0.3", base, "E"))
        assertEquals("x", map(1626, "7.0.3", base, "w"))
        assertEquals("z", map(1626, "7.0.3", base, "y"))

        host(1622, "7.0.1")
        assertEquals("U0", NativeLyricsHostContract.fieldName(fragment, "V0"))
        assertEquals("V0", NativeLyricsHostContract.shareSuppressFieldName())
        assertEquals("D0", NativeLyricsHostContract.fieldName(fragment, "E0"))
        assertEquals("E0", NativeLyricsHostContract.fieldName(fragment, "F0"))
        assertEquals("F0", NativeLyricsHostContract.fieldName(fragment, "G0"))
    }
    @Test fun bothProfilesResolveTheirOwnCompleteMetadataContracts() {
        val original=AppleMusicProfiles.match(TargetBuild("com.apple.android.music","7.0.0-beta",1606))!!
        val newer=AppleMusicProfiles.match(TargetBuild("com.apple.android.music","7.0.0-beta",1609))!!
        assertEquals(listOf("z3.w"),original.methodContracts.getValue("metadata-to-playback-item-method").parameters)
        assertEquals(listOf("z3.y"),newer.methodContracts.getValue("metadata-to-playback-item-method").parameters)
        assertEquals("com.apple.android.music.player.fragment.m",newer.fieldContracts.getValue("lyrics-current-item-field").owner)
    }
}
