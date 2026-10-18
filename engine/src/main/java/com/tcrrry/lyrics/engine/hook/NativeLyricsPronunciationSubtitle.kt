package com.tcrrry.lyrics.engine.hook

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.tcrrry.lyrics.engine.lyrics.DesktopLyricsPresentation
import java.util.Collections
import java.util.WeakHashMap

/** Auxiliary tracks belong to the native row, outside its word-gradient children. */
internal object NativeLyricsPronunciationSubtitle {
    private data class Managed(val word: Boolean, val pronunciation: Boolean, val primaryPronunciation: Boolean, val subtitles: NativeSubtitleLookup)
    private val managed = Collections.synchronizedMap(WeakHashMap<Any, Managed>())
    internal data class Selection(val owner: Any, val lineId: Int, val pronunciation: Boolean, val translation: Boolean) {
        companion object {
            fun resolve(owner: Any, lineId: Int, pronunciation: Boolean, translation: Boolean, parent: Selection?): Selection {
                val inherited = parent?.takeIf { it.owner === owner }
                return Selection(owner, lineId, inherited?.pronunciation ?: pronunciation, inherited?.translation ?: translation)
            }
        }
    }
    // This ID belongs to the verified host APK, not the module's resource table.
    @SuppressLint("ResourceType")
    private fun auxiliaryTextColor(view: TextView): Int =
        runCatching { view.context.getColor(view.resources.getIdentifier("white_alpha_35", "color", com.tcrrry.lyrics.engine.ModuleConstants.RESOURCE_PACKAGE)) }.getOrDefault(0x59ffffff)

    private val auxiliaryStyles = Collections.synchronizedMap(WeakHashMap<TextView, Int>())
    internal fun releaseAuxiliary(view: View) { auxiliaryStyles.remove(view) }
    internal fun protectedAlpha(view: Any?, requested: Float): Float =
        if (auxiliaryStyles.containsKey(view)) 1f else requested
    internal fun protectedColor(view: Any?, requested: Int): Int = auxiliaryStyles[view] ?: requested

    private fun installPronunciationStyleGuard() {
        ModernXposedRuntime.hookMethod(View::class.java.getDeclaredMethod("setAlpha", Float::class.javaPrimitiveType), object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.args[0] = protectedAlpha(param.thisObject, param.args[0] as Float)
            }
        })
        ModernXposedRuntime.hookMethod(TextView::class.java.getDeclaredMethod("setTextColor", Int::class.javaPrimitiveType), object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.args[0] = protectedColor(param.thisObject, param.args[0] as Int)
            }
        })
        ModernXposedRuntime.hookMethod(TextView::class.java.getDeclaredMethod("setTextColor", android.content.res.ColorStateList::class.java), object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                auxiliaryStyles[param.thisObject]?.let { param.args[0] = android.content.res.ColorStateList.valueOf(it) }
            }
        })
    }

    // Both auxiliary views must remain dim; only the primary word track sweeps.
    internal fun stylePronunciation(view: TextView) {
        val color = auxiliaryTextColor(view)
        auxiliaryStyles[view] = color
        view.setTextColor(color)
        view.alpha = 1f
    }

    internal fun renderTranslation(container: ViewGroup, view: TextView) {
        if (!PronunciationHeaderLayout.supports(container)) return
        stylePronunciation(view)
        PronunciationHeaderLayout.moveBelow(container, view)
    }

    /** U/Z only accepts translation subtitle bindings, not word-pronunciation bindings. */
    internal fun renderHeader(
        container: ViewGroup,
        text: String,
        background: Boolean,
        resolveLayout: (String) -> Int = { name -> container.resources.getIdentifier(name, "layout", com.tcrrry.lyrics.engine.ModuleConstants.RESOURCE_PACKAGE) },
        inflate: (String, Int) -> Unit,
    ) {
        val layout = resolveLayout(if (background) "lyrics_bg_translation_line_karaoke" else "lyrics_translation_line_karaoke")
        check(layout != 0) { "Missing native auxiliary subtitle layout" }
        val count = container.childCount
        try {
            inflate(text, layout)
            check(container.childCount == count + 1) { "Native subtitle did not append one view" }
            val view = container.getChildAt(count) as? TextView ?: error("Native subtitle is not a TextView")
            stylePronunciation(view)
            PronunciationHeaderLayout.moveAbove(container, view)
        } catch (error: Exception) {
            // Native DataBinding may attach before rejecting a mismatched binding.
            while (container.childCount > count) container.removeViewAt(count)
            throw error
        }
    }

    internal fun reconcileAuxiliaries(
        container: ViewGroup, pronunciation: String?, translation: String?, background: Boolean,
        resolveLayout: (String) -> Int = { name -> container.resources.getIdentifier(name, "layout", com.tcrrry.lyrics.engine.ModuleConstants.RESOURCE_PACKAGE) },
        inflate: (String, Int) -> Unit,
    ) {
        fun update(text: String?, above: Boolean) {
            val current = PronunciationHeaderLayout.auxiliary(container, above) as? TextView
            if (text.isNullOrBlank()) {
                PronunciationHeaderLayout.clearAuxiliary(container, above)
                return
            }
            if (current != null && current.text.toString() == text) {
                stylePronunciation(current)
                current.visibility = View.VISIBLE
                return
            }
            PronunciationHeaderLayout.clearAuxiliary(container, above)
            if (above) renderHeader(container, text, background, resolveLayout, inflate)
            else {
                val layout = resolveLayout(if (background) "lyrics_bg_translation_line_karaoke" else "lyrics_translation_line_karaoke")
                check(layout != 0) { "Missing native translation subtitle layout" }
                val count = container.childCount
                try {
                    inflate(text, layout)
                    check(container.childCount == count + 1)
                    val view = container.getChildAt(count) as TextView
                    renderTranslation(container, view)
                } catch (error: Exception) {
                    while (container.childCount > count) container.removeViewAt(count)
                    throw error
                }
            }
        }
        update(pronunciation, true)
        update(translation, false)
    }

    private val nativeLine = ThreadLocal<Any?>()
    private val selection = ThreadLocal<Selection?>()

    fun remember(pointer: Any, ttml: String) {
        val presentation = DesktopLyricsPresentation.fromTtml(ttml)
        val subtitles = NativeSubtitleLookup(ttml)
        // Imported/cached platform TTML can bypass the cross-source enrichment pass.
        // Register that exact provider text too; mixed Latin words remain literal units.
        val platformPronunciation=TtmlAuxiliaryOrigins.read(ttml)?.pronunciation.orEmpty()
        val japaneseRoot=TtmlOriginalLanguage.read(ttml)?.startsWith("ja",true)==true ||
            presentation?.offlinePronunciationLanguages?.contains("ja")==true
        if(!japaneseRoot && (presentation?.source in setOf("QQ音乐","网易云音乐") ||
            platformPronunciation.any { it in setOf("QQ音乐","网易云音乐") })) {
            val body=Regex("(?is)<body\\b[^>]*>(.*?)</body\\s*>").find(ttml)?.groupValues?.get(1).orEmpty()
            val lines=Regex("(?is)<p\\b[^>]*>(.*?)</p\\s*>").findAll(body)
                .map { LyricsAuxiliaryText.plain(it.groupValues[1]) }.toList()
            if(lines.none { Regex("[ぁ-ゖァ-ヺ가-힣]").containsMatchIn(it) }) lines.forEach { original ->
                subtitles.forOriginal(original)?.pronunciation?.let { reading ->
                    com.tcrrry.lyrics.engine.lyrics.LanguagePronunciationSupplement.rememberChineseProviderRow(original,reading)
                }
            }
        }
        if (presentation != null || subtitles.hasPronunciation || subtitles.hasTranslation) {
            managed[pointer] = Managed(TtmlTimingPolicy.isWord(ttml), subtitles.hasPronunciation,
                presentation?.primaryPronunciation == true, subtitles)
        } else managed.remove(pointer)
    }

    internal fun subtitleLookup(pointer: Any?): NativeSubtitleLookup? = managed[pointer]?.subtitles

    internal fun lineSubtitles(pointer: Any?, original: String): NativeSubtitleLookup.Tracks? =
        managed[pointer]?.subtitles?.forOriginal(original)

    internal fun isManaged(pointer: Any?): Boolean = pointer != null && managed.containsKey(pointer)

    internal fun isManagedWord(pointer: Any?): Boolean = pointer != null && managed[pointer]?.word == true
    internal fun isManagedLine(pointer: Any?): Boolean = pointer != null && managed[pointer]?.word == false

    internal fun isPronunciationPrimary(pointer: Any?): Boolean = pointer != null && managed[pointer]?.primaryPronunciation == true

    internal fun hasManagedPronunciation(pointer: Any?): Boolean = pointer != null && managed[pointer]?.pronunciation == true

    fun install(loader: ClassLoader, build: TargetBuild = TargetBuild.UNKNOWN) {
        installPronunciationStyleGuard()
        val modern = build.versionName == "7.0.0-beta" && build.versionCode in setOf(1606L, 1609L)
        if (modern) NativeLineLyricsPresentation.install(loader)
        runCatching {
            val adapter = loader.lyricsClass("com.apple.android.music.player.A")
            val base = loader.lyricsClass(if (modern) "com.apple.android.music.player.n1" else "com.apple.android.music.player.i1")
            val holder = loader.lyricsClass("androidx.recyclerview.widget.RecyclerView\$D")
            val line = loader.lyricsClass("com.apple.android.music.ttml.javanative.model.LyricsLine\$LyricsLineNative")
            val flexbox = loader.lyricsClass("com.apple.android.music.common.views.FullWidthAlphaGradientFlexboxLayout")
            val subtitle = adapter.getDeclaredMethod(if (modern) "U" else "Z", String::class.java, flexbox,
                Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            // Native AM documents can bypass managed-pointer enrichment. Sanitize
            // only serialized timing markup at the final subtitle inflation entry.
            ModernXposedRuntime.hookMethod(subtitle, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    (param.args[0] as? String)?.let { param.args[0] = LyricsAuxiliaryText.forNative(it) }
                }
            })
            val rebuild = adapter.declaredMethods.single { it.name == (if (modern) "b0" else "g0") && it.parameterTypes.size == 7 }
            val rowView = holder.lyricsDeclaredField("a").apply { isAccessible = true }
            val pointer = adapter.getMethod(if (modern) "y" else "D")
            val type = adapter.getMethod(if (modern) "f" else "k", Int::class.javaPrimitiveType)
            val index = adapter.getMethod(if (modern) "E" else "J", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            val processor = adapter.lyricsDeclaredField("p").apply { isAccessible = true }
            val lineAt = processor.type.getMethod("a", Int::class.javaPrimitiveType)
            val dereference = lineAt.returnType.getMethod("get")
            val id = line.getMethod("getLineId")
            val pronunciation = line.getMethod("getHtmlPronunciationLineText")
            val backgroundPronunciation = line.getMethod("getHtmlPronunciationBackgroundVocalsLineText")
            val translation = line.getMethod("getHtmlTranslationLineText")
            val backgroundTranslation = line.getMethod("getHtmlTranslatedBackgroundVocalsLineText")
            val original = line.getMethod("getHtmlLineText")
            val bindingHolder = loader.lyricsClass(if (modern) "com.apple.android.music.player.n1\$b" else "com.apple.android.music.player.i1\$b")
            val rowBinding = bindingHolder.lyricsDeclaredField("u").apply { isAccessible = true }
            // Verified 1606 karaoke binding; old profiles fail open instead of hiding text.
            val bindingType = loader.lyricsClass("q8.p9")
            val mainWords = bindingType.lyricsDeclaredField("a0").apply { isAccessible = true }
            val backgroundWords = bindingType.lyricsDeclaredField("Z").apply { isAccessible = true }
            // 1606's word model caches the original Flex-line mask key per
            // native timed binding. Ruby reflow must refresh those references.
            val karaokeMaps=if(modern) runCatching {
                val type=loader.lyricsClass("com.apple.android.music.player.A\$a")
                type to listOf("G","H").map { type.lyricsDeclaredField(it).apply { isAccessible=true } }
            }.onFailure { ModernXposedRuntime.log("Ruby word-mask references unavailable; kept auxiliary presentation",it) }.getOrNull() else null
            fun containers(row: Any): List<Pair<ViewGroup, Boolean>> {
                val nativeBinding = rowBinding.get(row)
                if (!bindingType.isInstance(nativeBinding)) return emptyList()
                return listOf((mainWords.get(nativeBinding) as ViewGroup) to false,
                    (backgroundWords.get(nativeBinding) as ViewGroup) to true)
            }
            val translationFlag = base.lyricsDeclaredField("d").apply { isAccessible = true }
            val pronunciationFlag = base.lyricsDeclaredField("e").apply { isAccessible = true }
            val fullBind = adapter.getDeclaredMethod(if (modern) "k" else "p", holder, Int::class.javaPrimitiveType).apply { isAccessible = true }
            val partialBind = adapter.getDeclaredMethod(if (modern) "l" else "q", holder, Int::class.javaPrimitiveType, List::class.java).apply { isAccessible = true }
            val pending = loader.lyricsClass(if (modern) "com.apple.android.music.player.n1\$b" else "com.apple.android.music.player.i1\$b").lyricsDeclaredField("y").apply { isAccessible = true }
            val cachedText = loader.lyricsClass(if (modern) "com.apple.android.music.player.n1\$b" else "com.apple.android.music.player.i1\$b").lyricsDeclaredField("v").apply { isAccessible = true }
            val payloads = listOf("h", "i").map { base.lyricsDeclaredField(it).apply { isAccessible = true }.get(null) }
            var enabled = false
            val bindings = NativeAuxiliaryBindingState()
            fun owns(value: Any?): Boolean = enabled && value != null && isManagedWord(pointer.invoke(value))

            // Clean up only for an actual glyph rebuild. Rendering is repaired after
            // the outer bind even when Apple's cached tuple skips this method entirely.
            ModernXposedRuntime.hookMethod(rebuild, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (selection.get()?.owner !== param.thisObject) return
                    runCatching { PronunciationHeaderLayout.clear(param.args[2] as ViewGroup) }
                        .onFailure { ModernXposedRuntime.log("auxiliary cleanup failed open", it) }
                }
            })
            fun repair(target: Any, row: Any, value: Any, selected: Selection) {
                val data = managed[pointer.invoke(target)] ?: return
                val primary = original.invoke(value) as? String ?: ""
                for ((container, background) in containers(row)) {
                    if (!PronunciationHeaderLayout.supports(container)) continue
                    if (container.visibility == View.GONE) {
                        PronunciationHeaderLayout.clear(container)
                        continue
                    }
                    if(karaokeMaps?.first?.isInstance(row)==true) RubyGradientBounds.trackWordMap(container,
                        karaokeMaps.second[if(background)1 else 0].get(row) as? Map<*,*>)
                    val pron = ((if (background) backgroundPronunciation else pronunciation).invoke(value) as? String)?.let(LyricsAuxiliaryText::forNative)
                    val trans = ((if (background) backgroundTranslation else translation).invoke(value) as? String)?.let(LyricsAuxiliaryText::forNative)
                    val fallback = if (background) null else data.subtitles.forOriginal(primary)
                    reconcileAuxiliaries(container,
                        if (selected.pronunciation) pron?.takeIf(String::isNotBlank) ?: fallback?.pronunciation else null,
                        if (selected.translation) trans?.takeIf(String::isNotBlank) ?: fallback?.translation else null,
                        background) { text, layout -> subtitle.invoke(target, text, container, layout, false, 1) }
                    RubyPronunciationRows.bindWords(container, primary,
                        if (selected.pronunciation && !background) pron?.takeIf(String::isNotBlank) ?: fallback?.pronunciation else null,
                        data.primaryPronunciation)
                }
            }
            // Main karaoke words always remain the original lyrics for these pointers.
            ModernXposedRuntime.hookMethod(base.getDeclaredMethod(if (modern) "B" else "G"), object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable == null && runCatching { adapter.isInstance(param.thisObject) && owns(param.thisObject) }.getOrDefault(false)) {
                        param.result = true
                    }
                }
            })
            val binding = object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val target = param.thisObject ?: return@runCatching
                        // This hook is registered before the blur/tail hooks. Check the shared
                        // gate BEFORE clearing headers or forcing a word-map rebuild.
                        if (modern && param.method == fullBind && NativeLyricsTailBindGuard.beforeBind(
                                target, param.args[0]!!, param.args[1] as Int)) {
                            LyricsPlaybackDiagnostics.record("tail-bind", "path=subtitle-full pos=${param.args[1]}")
                            param.result = null
                            return@runCatching
                        }
                        if (!owns(target)) {
                            bindings.clear(param.args[0]!!)
                            (rowView.get(param.args[0]) as? ViewGroup)?.let { PronunciationHeaderLayout.clearRow(it) }
                            return@runCatching
                        }
                        val position = param.args[1] as Int
                        if (type.invoke(target, position) != 3) {
                            bindings.clear(param.args[0]!!)
                            (rowView.get(param.args[0]) as? ViewGroup)?.let { PronunciationHeaderLayout.clearRow(it) }
                            return@runCatching
                        }
                        val changes = if (param.method == partialBind) param.args[2] as List<*> else null
                        if (changes != null && (changes.isEmpty() || changes.any { it in payloads })) {
                            // A pronunciation toggle normally updates only ruby views.
                            // Rebind this row for either auxiliary toggle, never for word events.
                            @Suppress("UNCHECKED_CAST")
                            val tracked = pending.get(param.args[0]) as MutableSet<Any?>
                            if (changes.isEmpty()) tracked.clear() else tracked.addAll(changes.filter { it in payloads })
                            NativeLyricsTailBindGuard.updatingAuxiliary(explicitChange = changes.any { it in payloads }) {
                                fullBind.invoke(target, param.args[0], position)
                            }
                            param.result = null
                            return@runCatching
                        }
                        val data = processor.get(target) ?: return@runCatching
                        val currentLine = dereference.invoke(lineAt.invoke(data, index.invoke(target, position, true))) ?: return@runCatching
                        val rowContainers = containers(param.args[0]!!)
                        if (rowContainers.isEmpty() || rowContainers.any { !PronunciationHeaderLayout.supports(it.first) }) return@runCatching
                        val previous = selection.get()
                        val translated = translationFlag.getBoolean(target)
                        val pronounced = pronunciationFlag.getBoolean(target)
                        // Some partial updates invoke full binding internally;
                        // inherit real choices rather than their temporary masked flags.
                        val selected = Selection.resolve(target, id.invoke(currentLine) as Int, pronounced, translated, previous)
                        if (param.method == fullBind && bindings.changed(param.args[0]!!,
                                NativeAuxiliaryBindingState.Identity(requireNotNull(pointer.invoke(target)), selected.lineId,
                                    selected.pronunciation, selected.translation))) {
                            (rowView.get(param.args[0]) as? ViewGroup)?.let { PronunciationHeaderLayout.clearRow(it) }
                            cachedText.set(param.args[0], null)
                        }
                        param.extras["subtitle-state"] = Triple(previous, translated, pronounced)
                        param.extras["header-previous-line"] = nativeLine.get()
                        nativeLine.set(currentLine)
                        selection.set(selected)
                        // No auxiliary participates in native word maps or gradient rectangles.
                        translationFlag.setBoolean(target, false)
                        pronunciationFlag.setBoolean(target, false)
                    }.onFailure { error ->
                        restore(param)
                        ModernXposedRuntime.log("pronunciation subtitle binding failed open", error)
                    }
                }
                private fun restore(param: MethodHookParam) {
                    @Suppress("UNCHECKED_CAST")
                    val state = param.extras.remove("subtitle-state") as? Triple<Selection?, Boolean, Boolean> ?: return
                    try {
                        translationFlag.setBoolean(param.thisObject, state.second)
                        pronunciationFlag.setBoolean(param.thisObject, state.third)
                    } finally {
                        val previousLine = param.extras.remove("header-previous-line")
                        if (previousLine == null) nativeLine.remove() else nativeLine.set(previousLine)
                        if (state.first == null) selection.remove() else selection.set(state.first)
                    }
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        // Native English and emphasized pronunciation need the
                        // same post-layout word-key refresh as managed ruby.
                        // Register their maps even when no subtitle is moved.
                        if(param.throwable==null && param.method==fullBind && karaokeMaps?.first?.isInstance(param.args[0])==true) {
                            for((container,background) in containers(param.args[0]!!)) {
                                RubyGradientBounds.trackWordMap(container,
                                    karaokeMaps.second[if(background)1 else 0].get(param.args[0]) as? Map<*,*>)
                            }
                        }
                        if (param.throwable != null) bindings.clear(param.args[0]!!)
                        else if (param.extras.containsKey("subtitle-state")) {
                            val selected = selection.get()
                            val value = nativeLine.get()
                            if (selected != null && value != null) repair(param.thisObject!!, param.args[0]!!, value, selected)
                        }
                    } catch (error: Exception) {
                        ModernXposedRuntime.log("auxiliary post-bind repair failed", error)
                    } finally {
                        runCatching { restore(param) }.onFailure { ModernXposedRuntime.log("pronunciation subtitle restore failed", it) }
                    }
                }
            }
            ModernXposedRuntime.hookMethod(fullBind, binding)
            ModernXposedRuntime.hookMethod(partialBind, binding)
            enabled = true
            ModernXposedRuntime.log("managed pronunciation subtitle layout installed")
        }.onFailure { ModernXposedRuntime.log("pronunciation subtitle layout unavailable; kept native layout", it) }
    }
}
