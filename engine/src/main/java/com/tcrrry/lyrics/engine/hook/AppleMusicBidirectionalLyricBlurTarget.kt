package com.tcrrry.lyrics.engine.hook

import android.util.Log
import android.view.View
import com.tcrrry.lyrics.engine.config.TargetConfigClient
import com.tcrrry.lyrics.engine.hook.ModernMethodHook as XC_MethodHook
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import android.os.Handler
import android.os.Looper

/** Classifies canonical callback owner names without relying on the trailing lambda ordinal. */
internal fun lyricWordCallbackSource(ownerName: String): String? = when {
    ownerName.contains("\$prBgWordEventCallback\$") -> LyricHighlightProbe.Source.PR_BG_WORD
    ownerName.contains("\$bgWordEventCallback\$") -> LyricHighlightProbe.Source.BG_WORD
    ownerName.contains("\$prWordEventCallback\$") -> LyricHighlightProbe.Source.PR_WORD
    ownerName.contains("\$wordEventCallback\$") -> LyricHighlightProbe.Source.WORD
    else -> null
}

/** Apple Music symbol and hook adapter for the target-independent lyric blur runtime. */
internal class AppleMusicBidirectionalLyricBlurTarget(
    private val symbols: TargetSymbolResolver,
) : BidirectionalLyricBlurTarget {
    override fun install(): TargetCapabilityInstall {
        val recyclerResolution = symbols.resolve(AppleMusicSymbols.RecyclerView)
        val fragmentResolution = symbols.resolve(AppleMusicSymbols.LyricsFragment)
        val recyclerClass = recyclerResolution.valueOrNull()
        val fragmentClass = fragmentResolution.valueOrNull()
        if (recyclerClass == null || fragmentClass == null) {
            return TargetCapabilityInstall.Degraded(
                listOf(recyclerResolution, fragmentResolution)
                    .filterNot { it is TargetResolution.Found<*> }
                    .joinToString { it.summary },
            )
        }

        val vectorResolution = symbols.resolve(AppleMusicSymbols.LyricsLineVector)
        val sessionResolution = symbols.resolve(AppleMusicSymbols.LyricsSessionProcessor)
        val callbackResolution = symbols.resolve(AppleMusicSymbols.LyricsHighlightCallback)
        val wordCallbackResolution = symbols.resolve(AppleMusicSymbols.LyricsWordHighlightCallbacks)
        val notifyWordHighlightResolution = symbols.resolve(
            AppleMusicSymbols.LyricsViewModelNotifyWordHighlight,
        )
        val setCurrentHighlightedLineResolution = symbols.resolve(
            AppleMusicSymbols.LyricsViewModelSetCurrentHighlightedLine,
        )
        val callback = callbackResolution.valueOrNull()
        val notifyWordHighlight = notifyWordHighlightResolution.valueOrNull()
        val setCurrentHighlightedLine = setCurrentHighlightedLineResolution.valueOrNull()
        if (callback == null && notifyWordHighlight == null && setCurrentHighlightedLine == null) {
            return TargetCapabilityInstall.Degraded(
                listOf(
                    callbackResolution,
                    notifyWordHighlightResolution,
                    setCurrentHighlightedLineResolution,
                ).joinToString { it.summary },
            )
        }

        val followRecovery = if ((fragmentResolution as? TargetResolution.Found)?.profileId ==
            "apple-music-7.0.0-beta-1609") NativeLyricsPresentationRecovery.resolve(fragmentClass) else null
        val targetAccess = AppleMusicLyricBlurTargetAccess(recyclerClass, followRecovery)
        val probe = LyricHighlightProbe()
        val runtime = OpenSourceLyricBlurPort(
            targetAccess = targetAccess,
            blurRadiusOffsetPx = TargetConfigClient.currentSettings().lyricBlurRadiusOffsetPx,
            probe = probe,
        )
        // Observe only RecyclerView entry points, without replacing touch listeners or consuming events.
        for (name in listOf("onInterceptTouchEvent", "onTouchEvent")) ModernXposedRuntime.hookMethod(
            recyclerClass.getDeclaredMethod(name, android.view.MotionEvent::class.java),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val event = param.args.firstOrNull() as? android.view.MotionEvent ?: return
                    runCatching { runtime.onLyricsTouch(view, event) }
                        .onFailure { Log.w(TAG, "Lyrics touch observation failed open", it) }
                }
            })
        val highlights = LyricHighlightEventRouter(runtime, probe)
        val wordTails = LyricWordTailState()
        val releaseTails = installWordTailProtection(fragmentClass, wordTails)

        // Preserve the upstream installation order: recycler, session, callback, lifecycle, VM.
        targetAccess.initializeAdapterPositionAccessor()
        hookSessionProcessor(
            method = sessionResolution.valueOrNull(),
            runtime = runtime,
            probe = probe,
            wordTails = wordTails,
            releaseTails = releaseTails,
        )
        hookWordHighlightCallbacks(
            callbacks = wordCallbackResolution.valueOrNull().orEmpty(),
            probe = probe,
            runtime = runtime,
            wordTails = wordTails,
        )
        hookHighlightCallback(
            method = callback,
            vectorClass = vectorResolution.valueOrNull(),
            highlights = highlights,
            probe = probe,
            runtime = runtime,
        )
        hookLyricsFragment(fragmentClass, runtime)
        hookViewModel(notifyWordHighlight, setCurrentHighlightedLine, highlights)

        val optionalFailures = listOf(
            vectorResolution,
            sessionResolution,
            callbackResolution,
            wordCallbackResolution,
            notifyWordHighlightResolution,
            setCurrentHighlightedLineResolution,
        ).filterNot { it is TargetResolution.Found<*> }
        return if (optionalFailures.isEmpty()) {
            TargetCapabilityInstall.Active(
                "a23bc/amlyricblur core installed; ${fragmentResolution.summary}; ${callbackResolution.summary}",
            )
        } else {
            TargetCapabilityInstall.Degraded(
                "Lyric blur installed with fallback hooks; " +
                    optionalFailures.joinToString { it.summary },
            )
        }
    }

    private fun installWordTailProtection(fragmentClass: Class<*>?, tails: LyricWordTailState): () -> Unit {
        // The verified 1606 adapter cancels foreground/background word animators in o0's exit path.
        if (fragmentClass == null || fragmentClass.declaredMethods.none { it.name == "w2" }) return {}
        return runCatching {
            val adapter = lyricsClassForName("com.apple.android.music.player.A", false, fragmentClass.classLoader)
            RubyGradientBounds.installNative(adapter)
            NativeTerminalGradientFix.install(adapter)
            NativeLyricsLineTiming.install(fragmentClass.classLoader)
            val leaving = adapter.getDeclaredMethod("o0", Int::class.javaPrimitiveType)
            require(leaving.returnType == Boolean::class.javaPrimitiveType)
            val pointer = adapter.getDeclaredMethod("y")
            val holder = lyricsClassForName("com.apple.android.music.player.A\$a", false, fragmentClass.classLoader)
            val position = holder.getMethod("d")
            val lineForPosition = adapter.getDeclaredMethod("E", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            val highlighted = adapter.getMethod("w")
            val wordBindings = holder.lyricsDeclaredField("G").apply { isAccessible = true }
            val wordLine = lyricsClassForName("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel\$e",
                false, fragmentClass.classLoader).lyricsDeclaredField("a").apply { isAccessible = true }
            val pending = WeakHashMap<Any, MutableMap<Int, WeakReference<Any>>>()
            val main = com.tcrrry.lyrics.plugin.PluginHandler(Looper.getMainLooper())
            // Only an already bound departing karaoke row may retain its masks.
            // Initial/recycled holders must always take the normal binding path.
            fun retain(target: Any, item: Any, row: Int): Boolean {
                val token = pointer.invoke(target) ?: return false
                val active = highlighted.invoke(target) as? Set<*> ?: return false
                if (row in active || !tails.protects(token, row)) return false
                val boundRow = (lineForPosition.invoke(target, position.invoke(item), true) as? Number)?.toInt()
                val words = wordBindings.get(item) as? Map<*, *> ?: return false
                if (boundRow != row || words.values.none { word -> word != null && wordLine.getInt(word) == row }) return false
                synchronized(pending) { pending.getOrPut(target) { mutableMapOf() }[row] = WeakReference(item) }
                return true
            }
            // O's notifyItemChanged ultimately enters k, not i0. k owns word-view
            // rebuilding and mask initialization even when i0 is never called.
            val fullBind = adapter.getDeclaredMethod("k",
                lyricsClassForName("androidx.recyclerview.widget.RecyclerView\$D", false, fragmentClass.classLoader),
                Int::class.javaPrimitiveType)
            val nativeBindInstalled = ModernXposedRuntime.hookMethod(fullBind, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val target = param.thisObject ?: return
                    val item = param.args.firstOrNull() ?: return
                    val bindPosition = (param.args.getOrNull(1) as? Number)?.toInt() ?: return
                    if (NativeLyricsTailBindGuard.beforeBind(target, item, bindPosition)) {
                        LyricsPlaybackDiagnostics.record("tail-bind", "path=native-full pos=$bindPosition")
                        param.result = null
                    }
                }
            })
            require(nativeBindInstalled) { "Native full word binding hook was rejected" }
            NativeLyricsTailBindGuard.retain = { target, item, bindPosition ->
                val row = (lineForPosition.invoke(target, bindPosition, true) as Number).toInt()
                retain(target, item, row).also { held ->
                    if (held) LyricsPlaybackDiagnostics.record("tail-retain", "row=$row path=k")
                }
            }
            for (name in listOf("F", "i0", "t0")) runCatching {
                val method = if (name == "F") adapter.getDeclaredMethod(name, lyricsClassForName("androidx.recyclerview.widget.RecyclerView\$D", false, fragmentClass.classLoader))
                    else adapter.getDeclaredMethod(name, holder, Int::class.javaPrimitiveType)
                val registered = ModernXposedRuntime.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val target = param.thisObject ?: return@runCatching
                            val token = pointer.invoke(target) ?: return@runCatching
                            val row = if (name == "F") lineForPosition.invoke(param.thisObject,
                                position.invoke(param.args[0]), true) else param.args.getOrNull(1)
                            val id = (row as? Number)?.toInt() ?: return@runCatching
                            LyricsPlaybackDiagnostics.record("row-$name", "row=$id protect=${tails.protects(token, id)} current=${highlighted.invoke(target)}")
                            val item = param.args.firstOrNull() ?: return@runCatching
                            if (retain(target, item, id)) {
                                LyricsPlaybackDiagnostics.record("tail-retain", "row=$id path=$name")
                                param.result = null
                            }
                        }
                    }
                })
                LyricsPlaybackDiagnostics.record("row-hook", "name=$name installed=$registered")
            }.onFailure { LyricsPlaybackDiagnostics.record("row-hook-error", "name=$name error=${it.javaClass.simpleName}") }
            ModernXposedRuntime.hookMethod(leaving, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result != true) return
                    runCatching {
                        val token = pointer.invoke(param.thisObject) ?: return@runCatching
                        val row = (param.args.firstOrNull() as? Number)?.toInt() ?: return@runCatching
                        val protect = tails.protects(token, row)
                        LyricsPlaybackDiagnostics.record("tail-exit", "doc=${LyricsPlaybackDiagnostics.identity(token)} row=$row protect=$protect")
                        if (protect) param.result = false
                    }.onFailure { Log.w(TAG, "Word tail protection failed open", it) }
                }
            })
            val release: () -> Unit = {
                // The real playback clock, including pause/seek/repeat, owns expiry.
                // Post once outside native processing so a rebind cannot interrupt its callbacks.
                val ready = mutableListOf<Triple<WeakReference<Any>, Int, WeakReference<Any>>>()
                synchronized(pending) {
                val adapters = pending.entries.iterator()
                while (adapters.hasNext()) {
                    val (target, rows) = adapters.next()
                    val token = pointer.invoke(target)
                    if (token == null || !tails.owns(token)) { adapters.remove(); continue }
                    val active = highlighted.invoke(target) as? Set<*> ?: continue
                    val items = rows.entries.iterator()
                    while (items.hasNext()) {
                        val (row, item) = items.next()
                        if (item.get() == null || row in active) { items.remove(); continue }
                        if (!tails.protects(token, row) && active.isNotEmpty()) {
                            ready.add(Triple(WeakReference(target), row, item))
                            items.remove()
                        }
                    }
                    if (rows.isEmpty()) adapters.remove()
                }
                }
                if (ready.isNotEmpty()) main.post {
                    ready.forEach { (targetRef, row, itemRef) -> runCatching {
                        val target = targetRef.get() ?: return@runCatching
                        val item = itemRef.get() ?: return@runCatching
                        val token = pointer.invoke(target) ?: return@runCatching
                        val bound = (lineForPosition.invoke(target, position.invoke(item), true) as? Number)?.toInt()
                        if (tails.owns(token) && !tails.protects(token, row) && bound == row) {
                            fullBind.invoke(target, item, position.invoke(item))
                            LyricsPlaybackDiagnostics.record("tail-release", "row=$row")
                        }
                    } }
                }
            }
            release
        }.onFailure { Log.w(TAG, "Native word tail cancellation seam unavailable", it) }.getOrElse { {} }
    }

    private fun readWordEnds(vector: Any): Map<Int, Long>? = runCatching {
        val type = vector.javaClass
        val count = (type.getMethod("size").invoke(vector) as Number).toInt().coerceIn(0, 256)
        val getWord = type.getMethod("get", Long::class.javaPrimitiveType)
        buildMap<Int, Long> {
            for (index in 0 until count) {
                val ptr = getWord.invoke(vector, index.toLong()) ?: continue
                val word = ptr.javaClass.getMethod("get").invoke(ptr) ?: continue
                val begin = (word.javaClass.getMethod("getBegin").invoke(word) as Number).toLong()
                val end = (word.javaClass.getMethod("getEnd").invoke(word) as Number).toLong()
                if (begin < 0 || end <= begin) continue
                val linePtr = word.javaClass.getMethod("getLyricsLine").invoke(word) ?: continue
                val line = linePtr.javaClass.getMethod("get").invoke(linePtr) ?: continue
                val row = (line.javaClass.getMethod("getLineId").invoke(line) as Number).toInt()
                val rowWords = line.javaClass.getMethod("getWords").invoke(line)
                val completeEnd = rowWords?.let(::nativeLastWordEnd) ?: end
                put(row, maxOf(get(row) ?: end, end, completeEnd))
            }
        }
    }.getOrNull()

    private fun hookSessionProcessor(
        method: Method?,
        runtime: LyricBlurRuntime,
        probe: LyricHighlightProbe,
        wordTails: LyricWordTailState,
        releaseTails: () -> Unit,
    ) {
        if (method == null) {
            Log.w(TAG, "Lyric session processor symbol was unavailable")
            return
        }
        try {
            NativeLyricsImmediateAnchor.processObserved = { token, position ->
                wordTails.process(token, position)
                runCatching(releaseTails).onFailure { LyricsPlaybackDiagnostics.record("tail-error", it.javaClass.simpleName) }
                runtime.onProcessPosition(token, position)
                runtime.onWordHighlightsChanged("word-tail", wordTails.protectedRows())
            }
            NativeLyricsImmediateAnchor.presentationRecovered = runtime::onPresentationRecovered
            NativeLyricsImmediateAnchor.currentPresentationHighlights = runtime::currentLineHighlights
            ModernXposedRuntime.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val processor = param.thisObject
                    val pointer = param.args.firstOrNull()
                    if (processor != null && pointer != null) NativeLyricsLineTiming.prepare(processor, pointer)
                    probe.recordSession(
                        token = param.args.firstOrNull(),
                        processPosition = (param.args.getOrNull(1) as? Number)?.toLong(),
                    )
                    param.args.firstOrNull()?.let { token ->
                        val position = (param.args.getOrNull(1) as? Number)?.toLong()
                        LyricsPlaybackDiagnostics.record("jni-process", "doc=${LyricsPlaybackDiagnostics.identity(token)} pos=$position", sample = true)
                        wordTails.process(token, position)
                        runCatching(releaseTails).onFailure { LyricsPlaybackDiagnostics.record("tail-error", it.javaClass.simpleName) }
                        runtime.onProcessPosition(token, position)
                        runtime.onWordHighlightsChanged("word-tail", wordTails.protectedRows())
                        NativeLyricsImmediateAnchor.beforeProcess(token, position)
                    }
                }
            })
            Log.i(TAG, "Lyric session hook installed on ${method.name}")
        } catch (t: Throwable) {
            Log.e(TAG, "Lyric session hook failed", t)
        }
    }

    private fun hookHighlightCallback(
        method: Method?,
        vectorClass: Class<*>?,
        highlights: LyricHighlightEventRouter,
        probe: LyricHighlightProbe,
        runtime: LyricBlurRuntime,
    ) {
        if (method == null || vectorClass == null) {
            Log.w(TAG, "Highlight callback symbols were unavailable")
            return
        }
        Log.i(TAG, "FOUND: ${method.declaringClass.name}.${method.name}")
        try {
            ModernXposedRuntime.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val vector = param.args.firstOrNull { argument ->
                            argument != null && vectorClass.isInstance(argument)
                        } ?: return
                        val rawLineIds = readLineIds(vectorClass, vector)
                        LyricsPlaybackDiagnostics.record("line-callback", "pos=${param.args.getOrNull(0)} ids=$rawLineIds deadline=${param.args.getOrNull(2)}")
                        val nativeFirst = (param.args.getOrNull(0) as? Number)?.toLong()
                        highlights.onCallback(
                            nativeFirst = nativeFirst,
                            lineIds = rawLineIds,
                            nativeLast = (param.args.getOrNull(2) as? Number)?.toLong(),
                            rawLineIds = rawLineIds,
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "Highlight hook error", t)
                    }
                }
            })
            highlights.onCallbackInstalled()
            Log.i(TAG, "Highlight hook installed on ${method.name}")
        } catch (t: Throwable) {
            Log.e(TAG, "installHighlightHook failed", t)
        }
    }

    /**
     * Apple keeps line and word transition callbacks as four sibling
     * Kotlin/JNI owner classes.  The line callback alone cannot tell whether
     * the previous row is still receiving word progress, so the four word
     * vectors are kept in a separate blur-state stream instead of replacing
     * the existing line session.
     */
    private fun hookWordHighlightCallbacks(
        callbacks: List<Method>,
        probe: LyricHighlightProbe,
        runtime: LyricBlurRuntime,
        wordTails: LyricWordTailState,
    ) {
        if (callbacks.isEmpty()) {
            Log.w(TAG, "Word callback methods were unavailable")
            return
        }
        assignWordCallbackSources(callbacks).forEach { (wordMethod, source) ->
            runCatching {
                ModernXposedRuntime.hookMethod(wordMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args.getOrNull(1)?.let { vector ->
                            readWordEnds(vector)?.let { wordTails.update(source, it) }
                        }
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val vector = param.args.getOrNull(1) ?: return@runCatching
                            val wordKeys = readWordKeys(wordMethod.parameterTypes[1], vector)
                                ?: return@runCatching
                            readWordEnds(vector)?.let {
                                wordTails.update(source, it)
                                LyricsPlaybackDiagnostics.record("word-$source", "keys=$wordKeys ends=$it")
                            }
                            probe.recordWord(
                                source = source,
                                firstNative = (param.args.getOrNull(0) as? Number)?.toLong(),
                                wordKeys = wordKeys,
                                lastNative = (param.args.getOrNull(2) as? Number)?.toLong(),
                            )
                            runtime.onWordHighlightsChanged("word-tail", wordTails.protectedRows())
                            runtime.onWordHighlightsChanged(
                                source = source,
                                lineIds = wordKeys.mapNotNull { key ->
                                    key.substringBefore(':').toIntOrNull()
                                }.toSet(),
                            )
                        }
                    }
                })
            }.onFailure { error ->
                Log.w(TAG, "Word callback hook failed on ${wordMethod.declaringClass.name}", error)
            }
        }
    }

    private fun assignWordCallbackSources(callbacks: List<Method>): List<Pair<Method, String>> {
        val selected = selectWordCallbackMethods(callbacks)
        val knownSources = selected.mapNotNull { method ->
            lyricWordCallbackSource(method.declaringClass.name)
        }.toSet()
        val remainingSources = listOf(
            LyricHighlightProbe.Source.WORD,
            LyricHighlightProbe.Source.BG_WORD,
            LyricHighlightProbe.Source.PR_WORD,
            LyricHighlightProbe.Source.PR_BG_WORD,
        ).filterNot(knownSources::contains).toMutableList()
        return selected.map { method ->
            val source = lyricWordCallbackSource(method.declaringClass.name) ?:
                remainingSources.removeFirstOrNull() ?: LyricHighlightProbe.Source.WORD
            method to source
        }
    }

    private fun selectWordCallbackMethods(callbacks: List<Method>): List<Method> = buildList {
        val seenSources = mutableSetOf<String>()
        callbacks.forEach { method ->
            val source = lyricWordCallbackSource(method.declaringClass.name) ?: return@forEach
            if (size < MAX_WORD_CALLBACKS && seenSources.add(source)) add(method)
        }
        callbacks.forEach { method ->
            if (size >= MAX_WORD_CALLBACKS) return@forEach
            if (lyricWordCallbackSource(method.declaringClass.name) == null) add(method)
        }
    }

    private fun readWordKeys(vectorClass: Class<*>, vector: Any): Set<String>? = runCatching {
        val size = (vectorClass.getMethod("size").invoke(vector) as Number).toInt()
        val get = vectorClass.getMethod("get", Long::class.javaPrimitiveType)
        buildSet {
            for (index in 0 until size) {
                runCatching {
                    val pointer = get.invoke(vector, index.toLong()) ?: return@runCatching
                    val word = pointer.javaClass.getMethod("get").invoke(pointer) ?: return@runCatching
                    val wordId = (word.javaClass.getMethod("getWordId").invoke(word) as Number).toInt()
                    val linePointer = word.javaClass.getMethod("getLyricsLine").invoke(word)
                        ?: return@runCatching
                    val line = linePointer.javaClass.getMethod("get").invoke(linePointer)
                        ?: return@runCatching
                    val lineId = (line.javaClass.getMethod("getLineId").invoke(line) as Number).toInt()
                    add("$lineId:$wordId")
                }
            }
        }
    }.getOrNull()

    private fun readLineIds(vectorClass: Class<*>, vector: Any): Set<Int> {
        val size = (vectorClass.getMethod("size").invoke(vector) as Long).toInt()
        val get = vectorClass.getMethod("get", Long::class.javaPrimitiveType)
        return buildSet {
            for (index in 0 until size) {
                try {
                    val pointer = get.invoke(vector, index.toLong()) ?: continue
                    val nativeObject = pointer.javaClass.getMethod("get").invoke(pointer) ?: continue
                    val lineId = (
                        nativeObject.javaClass.getMethod("getLineId").invoke(nativeObject) as Number
                    ).toInt()
                    add(lineId)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun hookLyricsFragment(fragmentClass: Class<*>, runtime: LyricBlurRuntime) {
        try {
            ModernXposedRuntime.hookAllMethods(fragmentClass, "onCreateView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val root = param.result as? View ?: return
                    val owner = param.thisObject ?: return
                    runtime.onLyricsViewCreated(owner, root)
                    Log.i(TAG, "onCreateView hooked")
                }
            })
            val destroyDeclaringClass = findLifecycleDeclaringClass(fragmentClass, "onDestroyView")
                ?: error("onDestroyView declaration was unavailable")
            ModernXposedRuntime.hookAllMethods(
                destroyDeclaringClass,
                "onDestroyView",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val owner = param.thisObject?.takeIf(fragmentClass::isInstance) ?: return
                        runtime.onLyricsViewDestroyed(owner)
                    }
                },
            )
            Log.i(TAG, "Fragment lifecycle hooks installed")
        } catch (t: Throwable) {
            Log.w(TAG, "Fragment hook failed: ${t.message}")
        }
    }

    private fun findLifecycleDeclaringClass(start: Class<*>, methodName: String): Class<*>? =
        generateSequence(start) { type -> type.superclass }
            .firstOrNull { type ->
                type.declaredMethods.any { method ->
                    method.name == methodName && method.parameterCount == 0
                }
            }

    private fun hookViewModel(
        notifyWordHighlight: Method?,
        setCurrentHighlightedLine: Method?,
        highlights: LyricHighlightEventRouter,
    ) {
        if (notifyWordHighlight == null && setCurrentHighlightedLine == null) {
            Log.w(TAG, "VM method symbols were unavailable")
            return
        }
        try {
            if (notifyWordHighlight != null) {
                ModernXposedRuntime.hookMethod(notifyWordHighlight, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            highlights.onFourArgumentViewModelEvent(
                                lineId = param.args[0] as Int,
                                isBackground = param.args[3] as Boolean,
                            )
                        }
                    })
            }
            if (setCurrentHighlightedLine != null) {
                ModernXposedRuntime.hookMethod(setCurrentHighlightedLine, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            highlights.onSingleArgumentViewModelEvent(param.args[0] as Int)
                        }
                    })
            }
        } catch (t: Throwable) {
            Log.w(TAG, "VM hook failed: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "AMLyricBlur"
        const val MAX_WORD_CALLBACKS = 4
    }
}

internal class AppleMusicLyricBlurTargetAccess(
    private val recyclerViewClass: Class<*>,
    private val recovery: NativeLyricsPresentationRecovery? = null,
) : LyricBlurTargetAccess {
    private var adapterPositionAccessor: Method? = null

    fun initializeAdapterPositionAccessor() {
        try {
            adapterPositionAccessor = recyclerViewClass.declaredMethods.firstOrNull { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.contentEquals(arrayOf(View::class.java)) &&
                    method.returnType == Int::class.javaPrimitiveType
            }?.apply { isAccessible = true }
            Log.i("AMLyricBlur", "Reflection OK")
        } catch (t: Throwable) {
            Log.e("AMLyricBlur", "Reflection failed", t)
        }
    }

    override fun nativeFollowSuspended(owner: Any?, recycler: android.view.ViewGroup, document: Any?, active: Set<Int>): Boolean =
        recovery?.suspended(owner, recycler, document, active) == true

    override fun recoverFollow(owner: Any?, recycler: android.view.ViewGroup, document: Any?, active: Set<Int>): Boolean =
        if (recovery != null) recovery.recover(owner, recycler, document, active)
        else NativeLyricsImmediateAnchor.recoverFollow(owner)

    override fun isRecyclerView(view: View): Boolean = view.javaClass == recyclerViewClass

    override fun isInstrumentalRow(view: View): Boolean = InstrumentalRowIdentity.matches(view)

    override fun isCreditsRow(view: View): Boolean = CreditsRowIdentity.matches(view)

    override fun adapterPosition(view: View): Int = try {
        (adapterPositionAccessor?.invoke(null, view) as? Int) ?: -1
    } catch (_: Throwable) {
        -1
    }
}

/** Callback wins once installed; otherwise ViewModel events replace the active lyric line. */
internal class LyricHighlightEventRouter(
    private val runtime: LyricBlurRuntime,
    private val probe: LyricHighlightProbe = LyricHighlightProbe(),
) {
    private var callbackInstalled = false

    fun onCallbackInstalled() {
        callbackInstalled = true
        probe.recordSyntheticInstall()
        runtime.onHighlightsChanged(emptySet())
    }

    fun onCallback(lineIds: Set<Int>) {
        onCallback(nativeFirst = null, lineIds = lineIds, nativeLast = null)
    }

    /**
     * Native callback bridge.  The two Long values are intentionally kept as
     * nullable wrappers: reflection may expose a null argument on a degraded
     * callback shape, and a diagnostic failure must never affect the set path.
     */
    fun onCallback(
        nativeFirst: Long?,
        lineIds: Set<Int>,
        nativeLast: Long?,
        rawLineIds: Set<Int>? = null,
    ) {
        probe.recordNative(nativeFirst, lineIds, nativeLast, rawLineIds ?: lineIds)
        runtime.onNativeHighlightsChanged(lineIds, nativeFirst)
    }

    fun onFourArgumentViewModelEvent(lineId: Int, isBackground: Boolean) {
        if (!callbackInstalled && !isBackground && lineId > 0) {
            probe.recordVm4(lineId)
            runtime.onFallbackHighlightChanged(lineId)
        }
    }

    fun onSingleArgumentViewModelEvent(lineId: Int) {
        if (!callbackInstalled && lineId >= 0) {
            probe.recordVm1(lineId)
            runtime.onFallbackHighlightChanged(lineId)
        }
    }
}
