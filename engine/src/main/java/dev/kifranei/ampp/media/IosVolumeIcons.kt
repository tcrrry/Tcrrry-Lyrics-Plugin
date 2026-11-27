package dev.kifranei.ampp.media

import android.graphics.Path
import android.util.Xml
import androidx.core.graphics.PathParser
import dev.amenhancer.plugin.api.PluginContext
import org.xmlpull.v1.XmlPullParser

/** Exact 24dp vectors copied from Flamingo's native now-playing resources. */
internal class IosVolumeIcons(val low: Path, val high: Path) {
    companion object {
        fun read(context: PluginContext): IosVolumeIcons {
            fun icon(asset: String, count: Int): Path = context.openAsset(asset).use { input ->
                val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
                val ns = "http://schemas.android.com/apk/res/android"
                val result = Path()
                var paths = 0
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != XmlPullParser.START_TAG) continue
                    when (parser.name) {
                        "vector" -> check(parser.getAttributeValue(ns, "viewportWidth").toFloat() == 24f &&
                            parser.getAttributeValue(ns, "viewportHeight").toFloat() == 24f)
                        "path" -> {
                            result.addPath(checkNotNull(PathParser.createPathFromPathData(parser.getAttributeValue(ns, "pathData"))))
                            paths++
                        }
                    }
                }
                check(paths == count)
                result
            }
            return IosVolumeIcons(icon("flamingo-volume-low.xml", 1), icon("flamingo-volume-high.xml", 4))
        }
    }
}
