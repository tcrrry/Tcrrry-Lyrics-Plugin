package dev.kifranei.ampp.media

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.components.LiquidButton


@Composable
fun GlassAudioQualityCard(backdrop: Backdrop, badge: Drawable, title: String, encoding: String,
    source: String, settingsLabel: String, doneLabel: String, openSettings: () -> Unit, done: () -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    Column(Modifier.fillMaxSize().popupGlass(backdrop, shape)
        .padding(22.dp)) {
        Canvas(Modifier.size(width = 96.dp, height = 80.dp).align(Alignment.CenterHorizontally)) {
            val canvas = drawContext.canvas.nativeCanvas
            val save = canvas.save()
            try {
                badge.setTint(android.graphics.Color.WHITE)
                val aspect = if (badge.intrinsicWidth > 0 && badge.intrinsicHeight > 0)
                    badge.intrinsicWidth.toFloat() / badge.intrinsicHeight else 1f
                val width = minOf(size.width, size.height * aspect)
                val height = width / aspect
                canvas.translate((size.width - width) / 2f, (size.height - height) / 2f)
                badge.setBounds(0, 0, width.toInt(), height.toInt())
                badge.draw(canvas)
            } finally { canvas.restoreToCount(save) }
        }
        Spacer(Modifier.height(16.dp))
        BasicText(title, modifier = Modifier.fillMaxWidth(), style = TextStyle(color = Color.White, fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center))
        Spacer(Modifier.height(4.dp))
        BasicText(encoding, modifier = Modifier.fillMaxWidth(), style = TextStyle(color = Color.White.copy(alpha = .8f),
            fontSize = 16.sp, textAlign = TextAlign.Center))
        if (source.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            BasicText(source, modifier = Modifier.fillMaxWidth(), style = TextStyle(color = Color.White.copy(alpha = .75f),
                fontSize = 14.sp, textAlign = TextAlign.Center))
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiquidButton(openSettings, backdrop, Modifier.weight(1f).height(48.dp),
                surfaceColor = Color.White.copy(alpha = .15f)) {
                BasicText(settingsLabel, style = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
            }
            LiquidButton(done, backdrop, Modifier.weight(1f).height(48.dp), surfaceColor = Color.White.copy(alpha = .95f)) {
                BasicText(doneLabel, style = TextStyle(color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}
