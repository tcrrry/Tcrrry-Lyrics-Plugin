package com.tcrrry.lyrics.engine.ui

import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SettingsFrostedPaletteAndroidTest {
    private fun contrast(foreground: Int, background: Int): Float {
        val a = Color.luminance(foreground); val b = Color.luminance(background)
        return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
    }

    @Test fun purpleArtworkStaysColoredWithoutAnAlmostOpaqueBlackWash() {
        val purple = Color.rgb(110, 52, 140)
        val wash = SettingsFrostedPalette.wash(IntArray(144) { purple }, dark = true)
        val result = SettingsFrostedPalette.composite(purple, wash)
        assertTrue(Color.alpha(wash) < 150)
        assertTrue(Color.blue(result) > Color.red(result))
        assertTrue(Color.red(result) > Color.green(result) + 10)
        assertTrue(contrast(SettingsColors.Dark.text, result) >= 4.5f)
    }

    @Test fun BrightAndDarkArtworkGetDifferentWashStrengthsForReadableText() {
        val darkWash = SettingsFrostedPalette.wash(IntArray(144) { Color.rgb(30, 45, 75) }, true)
        val brightWash = SettingsFrostedPalette.wash(IntArray(144) { Color.WHITE }, true)
        assertTrue(Color.alpha(brightWash) > Color.alpha(darkWash))
        assertTrue(contrast(SettingsColors.Dark.text, SettingsFrostedPalette.composite(Color.WHITE, brightWash)) >= 4.5f)
        val lightWash = SettingsFrostedPalette.wash(IntArray(144) { Color.BLACK }, false)
        assertTrue(contrast(SettingsColors.Light.text, SettingsFrostedPalette.composite(Color.BLACK, lightWash)) >= 4.5f)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun nativePlateDrawsTheProcessedColorTextureInsteadOfAnOpaqueBlackSurface() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().visible().get()
        val view = SettingsFrostedView(activity)
        val material = SettingsGlassBackdrop(activity, view)
        val purple = Color.rgb(110, 52, 140)
        val texture = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        texture.eraseColor(purple)
        val wash = SettingsFrostedPalette.wash(IntArray(144) { purple }, true)
        SettingsGlassBackdrop::class.java.getDeclaredField("snapshot").apply { isAccessible = true }.set(material, texture)
        SettingsGlassBackdrop::class.java.getDeclaredField("washColor").apply { isAccessible = true }.setInt(material, wash)
        view.backdrop = material
        view.layout(0, 0, 200, 300)
        val target = android.graphics.Bitmap.createBitmap(200, 300, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(target))
        val actual = target.getPixel(100, 150)
        val expected = SettingsFrostedPalette.composite(purple, wash)
        assertTrue(kotlin.math.abs(Color.red(actual) - Color.red(expected)) <= 2)
        assertTrue(kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) <= 2)
        assertTrue(Color.blue(actual) > Color.red(actual))
        material.close()
        target.recycle()
        activity.finish()
    }
}
