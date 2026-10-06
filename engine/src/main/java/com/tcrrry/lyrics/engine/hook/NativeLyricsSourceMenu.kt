package com.tcrrry.lyrics.engine.hook
import com.tcrrry.lyrics.plugin.PluginStorage.pluginPreferences

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Toast
import android.widget.TextView
import com.tcrrry.lyrics.engine.ModuleConstants
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/** Extends only Apple's lyrics translations popup; native auxiliary rows remain owned by Apple. */
internal object NativeLyricsSourceMenu {
    private const val ROW_TAG = "tcrrry_native_lyrics_source_row"
    private const val EMPHASIS_TAG = "tcrrry_lyrics_emphasis_row"
    private const val DIVIDER_TAG = "tcrrry_native_lyrics_source_divider"
    private val anchors = Collections.synchronizedMap(WeakHashMap<View, Int>())

    internal fun registerAnchor(button: View) {
        if (!anchors.containsKey(button)) anchors[button] = button.visibility
        // GONE means no provider subtitles; INVISIBLE belongs to the native immersive controls.
        if (button.visibility != View.INVISIBLE) button.visibility = View.VISIBLE
    }

    internal fun unregisterAnchor(button: View) { anchors.remove(button); anchorPages.remove(button) }

    internal fun requestedVisibility(view: Any?, requested: Int): Int =
        if (anchors.containsKey(view) && requested == View.GONE) View.VISIBLE else requested

    private val anchorPages = Collections.synchronizedMap(WeakHashMap<View, WeakReference<Any>>())

    internal fun registerPage(button: View, fragment: Any) {
        anchorPages[button] = WeakReference(fragment)
    }

    internal fun publishAnchorPage(button: View) {
        anchorPages[button]?.get()?.let(CurrentLyricsSourceStatus::rememberVisiblePage)
    }

    private val menus = Collections.synchronizedMap(WeakHashMap<LinearLayout, Boolean>())

    fun cleanupPluginRows() {
        menus.keys.toList().forEach { menu ->
            (0 until menu.childCount).map(menu::getChildAt).filter { it.tag in setOf(ROW_TAG,EMPHASIS_TAG,DIVIDER_TAG) }.forEach(menu::removeView)
        }
        menus.clear()
        anchors.toMap().forEach { (button, visibility) -> if (button.visibility == View.VISIBLE && visibility == View.GONE) button.visibility=visibility }
        anchors.keys.toList().forEach { button ->
            fun walk(view:View) { if(view is ViewGroup) for(child in (0 until view.childCount).map(view::getChildAt)) {
                if(child.tag in setOf(ROW_TAG,EMPHASIS_TAG,DIVIDER_TAG)) view.removeView(child) else walk(child)
            } }
            walk(button.rootView)
        }
        anchors.clear();anchorPages.clear()
    }

    fun install(loader: ClassLoader, currentId: () -> Long?, openSettings: (Activity) -> Unit, build: TargetBuild = TargetBuild.UNKNOWN) {
        NativeLyricsPronunciation.install(loader)
        NativeLyricsPronunciationSubtitle.install(loader, build)
        val fragment = loader.loadClass("com.apple.android.music.player.fragment.PlayerLyricsViewFragment")
        var application: WeakReference<Context>? = null
        val visibilityObserver = loader.loadClass("com.apple.android.music.player.fragment.PlayerLyricsViewFragment\$37")
        ModernXposedRuntime.hookMethod(visibilityObserver.getDeclaredMethod("onChanged", java.lang.Boolean::class.java),
            object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val context = application?.get() ?: return
                    if (NativeLyricsEmphasisVisibility.synchronize(context, param.args[0] == true))
                        param.extras["refresh-emphasis"] = true
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.extras.remove("refresh-emphasis") == true && param.throwable == null)
                        currentId()?.let { CurrentLyricsSourceStatus.refreshSilently(it) }
                }
            })
        fun bind(root: View?, fragment: Any?) {
            root ?: return
            val context = root.context.applicationContext
            application = WeakReference(context)
            val visible = runCatching {
                val field = fragment?.javaClass?.declaredFields?.singleOrNull {
                    it.type.name == "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel"
                }?.apply { isAccessible = true }
                val model = field?.get(fragment) ?: return@runCatching null
                val live = model.javaClass.getMethod("getPronunciationSelectedLiveResult").invoke(model)
                live.javaClass.getMethod("getValue").invoke(live) as? Boolean
            }.getOrNull()
            if (visible != null && NativeLyricsEmphasisVisibility.synchronize(context, visible))
                currentId()?.let { CurrentLyricsSourceStatus.refreshSilently(it) }
            val id = root.resources.getIdentifier("translations_button", "id", ModuleConstants.RESOURCE_PACKAGE)
            val button = root.findViewById<View>(id) ?: return
            registerAnchor(button)
            if (fragment != null) {
                registerPage(button, fragment)
                publishAnchorPage(button)
            }
        }
        // Apple Music calls ImageView's override directly. Hook that entry too:
        // an optimized super-call need not pass through the View method hook.
        for (type in listOf(View::class.java, ImageView::class.java)) {
            ModernXposedRuntime.hookMethod(type.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType),
                object : ModernMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[0] = requestedVisibility(param.thisObject, param.args[0] as Int)
                    }
                })
        }
        ModernXposedRuntime.hookAllMethods(fragment, "onCreateView", object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) { bind(param.result as? View, param.thisObject) }
        })
        ModernXposedRuntime.hookAllMethods(fragment, "onResume", object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                bind(runCatching { param.thisObject?.let { ModernXposedRuntime.callMethod(it, "getView") as? View } }.getOrNull(), param.thisObject)
            }
        })
        ModernXposedRuntime.hookAllMethods(fragment, "onDestroyView", object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val root = runCatching { param.thisObject?.let { ModernXposedRuntime.callMethod(it, "getView") as? View } }.getOrNull()
                    ?: return
                val id = root.resources.getIdentifier("translations_button", "id", ModuleConstants.RESOURCE_PACKAGE)
                root.findViewById<View>(id)?.let(::unregisterAnchor)
            }
        })
        ModernXposedRuntime.hookMethod(PopupWindow::class.java.getDeclaredMethod("showAsDropDown",
            View::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val anchor = param.args.firstOrNull() as? View ?: return
                    if (!anchors.containsKey(anchor)) return
                    val popup = param.thisObject as? PopupWindow ?: return
                    val menu = popup.contentView as? LinearLayout ?: return
                    val id = menu.resources.getIdentifier("translations_popup_menu", "id", ModuleConstants.RESOURCE_PACKAGE)
                    if (id == 0 || menu.id != id) return
                    runCatching {
                        publishAnchorPage(anchor)
                        appendRow(menu, popup, currentId, openSettings)
                    }
                        .onFailure { ModernXposedRuntime.log("lyrics source menu extension failed open", it) }
                }
            })
        ModernXposedRuntime.log("native lyrics source menu installed")
    }

    private fun appendRow(menu: LinearLayout, popup: PopupWindow, currentId: () -> Long?, openSettings: (Activity) -> Unit) {
        menus[menu] = true
        menu.findViewWithTag<View>(EMPHASIS_TAG)?.let { menu.removeView(it) }
        menu.findViewWithTag<View>(ROW_TAG)?.let { menu.removeView(it) }
        menu.findViewWithTag<View>(DIVIDER_TAG)?.let { menu.removeView(it) }
        // Read Apple's actual main-label size, including its accessibility scaling.
        fun labels(view: View): List<TextView> = when (view) {
            is TextView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
            else -> emptyList()
        }
        val labelSizePx = labels(menu).filter { it.visibility != View.GONE }.maxOfOrNull { it.textSize }
        val context = menu.context
        val app = context.applicationContext
        val songId = currentId() ?: 0L
        val popupRef = WeakReference(popup)
        fun applied() = CurrentLyricsSourceStatus.appliedSource(app, songId)
        fun toast(message: String) { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
        fun valid(): Boolean {
            if (!com.tcrrry.lyrics.plugin.PluginLifetime.alive) return false
            if (LyricsSourceMenuPolicy.canAct(songId, currentId())) return true
            toast("歌曲已切换或尚未加载，请重新打开菜单")
            popupRef.get()?.dismiss()
            return false
        }
        fun refresh(source: String) {
            val started = CurrentLyricsSourceStatus.refresh(songId)
            if (!started) CurrentLyricsSourceStatus.cancelSourceCycle(songId)
            toast(if (started) "正在匹配 · $source" else "请重新打开歌词页后生效")
            popupRef.get()?.dismiss()
        }
        val caption = if (songId > 0) LyricsSourceMenuPolicy.caption(applied()).substringBefore('／') else "暂无歌曲"
        val detail = CurrentLyricsSourceStatus.description(app, songId).substringAfter("$caption · ", "")
        val row = NativeLyricsMenuRowLayout(context, caption, detail, labelSizePx = labelSizePx).apply {
            tag = ROW_TAG
            isClickable = true
            isFocusable = true
            contentDescription = "歌词源：${LyricsSourceMenuPolicy.caption(applied())}。$detail。单击切换来源，长按打开详细歌词设置"
        }
        row.setOnClickListener {
            if (!valid()) return@setOnClickListener
            val next = CurrentLyricsSourceStatus.beginSourceCycle(app, songId) ?: return@setOnClickListener
            refresh(next)
        }
        row.setOnLongClickListener {
            if (!valid()) return@setOnLongClickListener true
            popupRef.get()?.dismiss()
            findActivity(context)?.let(openSettings) ?: toast("暂时无法打开歌词设置")
            true
        }
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Confirmation arrives only when Android has ruled out a double tap.
                if (row.isAttachedToWindow && popupRef.get()?.isShowing == true) row.performClick()
                return true
            }
            // A double tap must not exclude a usable recording or open settings.
            override fun onDoubleTap(e: MotionEvent): Boolean = true
            override fun onLongPress(e: MotionEvent) { row.performLongClick() }
        })
        row.setOnTouchListener { _, event -> detector.onTouchEvent(event); true }
        menu.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val displayPrefs = app.pluginPreferences("japanese_pronunciation", Context.MODE_PRIVATE)
        val canEmphasize = CurrentLyricsSourceStatus.canEmphasizePronunciation(app, songId) && NativeLyricsEmphasisVisibility.visible(app)
        val pronunciationPrimary = canEmphasize && displayPrefs.getBoolean("primary", false)
        menu.addView(NativeLyricsMenuRowLayout(context, if (pronunciationPrimary) "突出：发音" else "突出：歌词", autoSizeLabel = false, labelSizePx = labelSizePx).apply {
            tag = EMPHASIS_TAG
            isEnabled = canEmphasize
            alpha = if (canEmphasize) 1f else 0.35f
            contentDescription = "${if (pronunciationPrimary) "突出发音" else "突出歌词"}，点击切换"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (!canEmphasize || !valid()) return@setOnClickListener
                displayPrefs.edit().putBoolean("primary", !pronunciationPrimary).apply()
                CurrentLyricsSourceStatus.refreshSilently(songId)
                toast(if (pronunciationPrimary) "突出歌词" else "突出发音")
                popupRef.get()?.dismiss()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun findActivity(context: Context): Activity? {
        var current = context
        repeat(16) {
            if (current is Activity) return current
            val next = (current as? ContextWrapper)?.baseContext ?: return null
            if (next === current) return null
            current = next
        }
        return null
    }
}
