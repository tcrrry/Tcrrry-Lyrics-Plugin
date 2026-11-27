package dev.kifranei.ampp.media

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import java.util.SortedSet

/** Adds a "更多分享" button above "报告问题" in the native lyrics-share sheet. */
internal class LyricsMoreShareIntegration(private val build: TargetBuild, private val loader: ClassLoader) : PlayerShareCardTarget {
    override fun install(): TargetCapabilityInstall {
        val type = runCatching {
            loader.loadClass("com.apple.android.music.common.actionsheet.lyrics.ShareLyricsActionSheetFragment")
        }.getOrNull() ?: return TargetCapabilityInstall.Unsupported("No lyrics share sheet for ${build.displayName}")
        val method = runCatching {
            type.getDeclaredMethod("onCreateView", android.view.LayoutInflater::class.java,
                ViewGroup::class.java, android.os.Bundle::class.java)
        }.getOrNull() ?: return TargetCapabilityInstall.Unsupported("No lyrics share view for ${build.displayName}")
        val scope = PluginScope()
        try {
            check(MediaRuntime.observeMethod(method, object : PluginMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val root = param.result as? View ?: return
                    if (param.throwable != null) return
                    root.post { addButton(root, param.thisObject) }
                }
            }, scope))
            scope.activate()
            return TargetCapabilityInstall.Active("Lyrics share sheet has a 更多分享 system-share button")
        } catch (error: Throwable) {
            scope.close()
            throw error
        }
    }

    private fun addButton(root: View, fragment: Any) {
        val context = root.context
        val report = findText(root, "报告问题") ?: findText(root, "Report a Problem") ?: return
        val density = root.resources.displayMetrics.density
        val label = TextView(context).apply {
            text = "更多分享"
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.BLACK)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F2F2F7")); cornerRadius = 18 * density
            }
            setOnClickListener {
                val selected = selectedLyrics(fragment)
                if (selected.isEmpty()) {
                    Toast.makeText(context, "请先选择需要分享的歌词", Toast.LENGTH_SHORT).show()
                } else {
                    shareLyrics(root, fragment, selected)
                }
            }
        }
        val height = (48 * density).toInt()
        val parent = report.parent as? ViewGroup ?: root as? ViewGroup ?: return
        if (parent is android.widget.LinearLayout) {
            val lp = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, height)
            lp.setMargins((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            parent.addView(label, parent.indexOfChild(report), lp)
        } else {
            root as? ViewGroup ?: return
            root.addView(label, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
            label.post {
                val loc = IntArray(2); report.getLocationInWindow(loc)
                val rootLoc = IntArray(2); root.getLocationInWindow(rootLoc)
                label.y = (loc[1] - rootLoc[1] - height - 12 * density).coerceAtLeast(0f)
                label.x = 16 * density
                val lp = label.layoutParams as ViewGroup.LayoutParams
                lp.width = root.width - (32 * density).toInt()
                label.layoutParams = lp
            }
        }
        MediaRuntime.log("lyrics more-share button added")
    }

    private fun selectedLyrics(fragment: Any): List<String> = runCatching {
        val vm = fragment.javaClass.getMethod("h1").invoke(fragment) ?: return emptyList()
        val set = vm.javaClass.getMethod("getSelectedLyricLines").invoke(vm) as? SortedSet<*> ?: return emptyList()
        set.mapNotNull { item ->
            if (item == null) return@mapNotNull null
            val text = runCatching {
                item.javaClass.methods.firstOrNull { it.name in setOf("getText", "getLine", "getContent", "toString") && it.parameterCount == 0 }
                    ?.invoke(item)?.toString()
            }.getOrNull()
            text?.takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    private fun shareLyrics(root: View, fragment: Any, lyrics: List<String>) {
        runCatching {
            val context = root.context
            val width = root.width.coerceAtLeast(1)
            val height = root.height.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val uri = save(bitmap, context)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "分享歌词").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            MediaRuntime.log("lyrics more-share failed", it)
            Toast.makeText(root.context, "分享失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun wrap(text: String, paint: Paint, max: Float): List<String> {
        val out = ArrayList<String>(); var current = ""
        for (ch in text) {
            val next = current + ch
            if (paint.measureText(next) > max && current.isNotEmpty()) { out.add(current); current = ch.toString() } else current = next
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    private fun save(bitmap: Bitmap, context: android.content.Context): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ampp-lyrics-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AM++")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("insert failed")
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ?: error("write failed")
        return uri
    }

    private fun findText(view: View, text: String): View? {
        if (view is TextView && view.text?.toString()?.contains(text) == true) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findText(view.getChildAt(i), text)?.let { return it }
        return null
    }
}
