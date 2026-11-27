package dev.kifranei.ampp.media

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop

import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/** Floating material below the native-backed iOS menu controls. */
@Composable
fun GlassPlayerMoreMenu(backdrop: Backdrop, dark: Boolean) {
    val shape = RoundedCornerShape(28.dp)
    val surface = if (dark) Color(0xFF3A3A3C) else Color(0xFFF2F2F7)
    Box(Modifier.fillMaxSize().compatDrawBackdrop(backdrop = backdrop, shape = { shape }, effects = {
        vibrancy()
        blur(20.dp.toPx())
        lens(10.dp.toPx(), 16.dp.toPx())
    }, onDrawSurface = { drawRect(surface.copy(alpha = if (dark) .36f else .55f)) })
        .border(.6.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .28f),
            Color.White.copy(alpha = .07f), Color.White.copy(alpha = .18f))), shape))
}
