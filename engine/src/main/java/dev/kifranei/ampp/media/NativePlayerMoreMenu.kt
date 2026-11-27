package dev.kifranei.ampp.media

import android.app.Activity
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

internal data class NativePlayerMenuEntry(
    val action: PlayerMenuAction,
    val title: String,
    val subtitle: String?,
    val icon: Drawable?,
    val enabled: Boolean,
    val checked: Boolean,
    val perform: (View) -> Unit,
) {
    val signature get() = listOf(action.key, title, subtitle, enabled, checked)
}

/** Read the native data source and dispatch with its original adapter index and model. */
internal class NativePlayerMoreMenu(
    private val activity: Activity,
    private val owner: Any,
    private val list: ViewGroup,
    private val isPlaylist: Boolean = false,
    private val downloadsProvider: () -> ContentDownloadsIntegration? = { null },
    private val dismiss: () -> Unit = {},
) {
    private val adapter = checkNotNull(PluginProfiles.field("more-fragment-adapter").get(owner))
    private val source = checkNotNull(PluginProfiles.field("more-adapter-source").get(adapter))
    private val dispatcher = checkNotNull(PluginProfiles.field("more-fragment-actions").get(owner))
    private val commands = PluginProfiles.field("more-source-commands")
    private val footer = PluginProfiles.field("more-source-footer")
    private val sourceItem = PluginProfiles.field("more-source-item")
    private val getCount = PluginProfiles.method("more-source-count")
    private val getItem = PluginProfiles.method("more-source-at")
    private val makeItem = PluginProfiles.method("more-source-make-item")
    private val click = PluginProfiles.method("more-action-click")
    private val title = PluginProfiles.method("more-item-title")
    private val subtitle = PluginProfiles.method("more-item-subtitle")
    private val icon = PluginProfiles.method("more-item-icon")
    private val enabled = PluginProfiles.method("more-item-enabled")
    private val checked = PluginProfiles.method("more-item-checked")
    private val headerType = title.declaringClass
    private val baseContentGetId = runCatching {
        headerType.superclass?.getDeclaredMethod("getId")?.apply { isAccessible = true }
    }.getOrNull()
    private var navigation: List<NativePlayerMenuEntry> = emptyList()
    private val navigationText = mutableMapOf<String, TextView>()

    fun playlistHeader(): Pair<String, String?>? {
        val raw = runCatching { sourceItem.get(source) }.getOrNull() ?: return null
        val target = unwrapTarget(raw)
        val name = callNoArgString(target, "getTitle")?.takeIf { it.isNotBlank() } ?: return null
        val sub = callNoArgString(target, "getCuratorName")?.takeIf { it.isNotBlank() }
            ?: callNoArgString(target, "getSubTitle")?.takeIf { it.isNotBlank() }
        return name to sub
    }

    fun entries(): List<NativePlayerMenuEntry> {
        val main = commands.get(source) as List<*>
        val pinned = footer.get(source) as? List<*> ?: emptyList<Any>()
        val totalCount = getCount.invoke(source) as Int
        val firstModel = if (totalCount > 0) runCatching { getItem.invoke(source, 0) }.getOrNull() else null
        val offset = if (firstModel != null && !headerType.isInstance(firstModel)) 1 else 0

        val result = mutableListOf<NativePlayerMenuEntry>()
        main.forEachIndexed { index, command ->
            command ?: return@forEachIndexed
            val pos = index + offset
            val model = getItem.invoke(source, pos)
            if (model == null || !headerType.isInstance(model)) return@forEachIndexed
            val key = (command as? Enum<*>)?.name ?: "${command.javaClass.name}:$index"
            result += buildEntry(key, pos, model) { view ->
                val current = entries().firstOrNull { it.action.key == key } ?: return@buildEntry
                if (current.enabled) {
                    val position = current.action.index
                    val item = getItem.invoke(source, position)
                    click.invoke(dispatcher, *bindMoreMenuClickArguments(click.parameterTypes, position, view, item))
                }
            }
        }

        // Plugin-injected extra rows appended to source adapter count (e.g., "导出播放列表")
        for (pos in (offset + main.size) until totalCount) {
            val model = runCatching { getItem.invoke(source, pos) }.getOrNull() ?: continue
            if (!headerType.isInstance(model)) continue
            val itemId = runCatching { baseContentGetId?.invoke(model) as? String }.getOrNull()?.takeIf { it.isNotBlank() }
            val key = itemId ?: "EXTRA:$pos"
            result += buildEntry(key, pos, model) { view ->
                val item = runCatching { getItem.invoke(source, pos) }.getOrNull() ?: model
                click.invoke(dispatcher, *bindMoreMenuClickArguments(click.parameterTypes, pos, view, item))
            }
        }

        pinned.forEachIndexed { fIdx, command ->
            command ?: return@forEachIndexed
            val model = makeItem.invoke(source, activity, command, sourceItem.get(source))
            if (model == null || !headerType.isInstance(model)) return@forEachIndexed
            val key = (command as? Enum<*>)?.name ?: "${command.javaClass.name}:footer:$fIdx"
            val pos = totalCount + fIdx
            result += buildEntry(key, pos, model) { view ->
                val current = entries().firstOrNull { it.action.key == key } ?: return@buildEntry
                if (current.enabled) {
                    val item = makeItem.invoke(source, activity, command, sourceItem.get(source))
                    click.invoke(dispatcher, *bindMoreMenuClickArguments(click.parameterTypes, current.action.index, view, item))
                }
            }
        }

        if (isPlaylist) return result
        val download = downloadsProvider()?.let { integration ->
            val item = sourceItem.get(source)
            val id = item?.let { runCatching { PluginProfiles.method("content-download-item-id").invoke(it) as? String }.getOrNull() }?.toLongOrNull()
            val name = item?.let { runCatching { PluginProfiles.method("content-download-item-title").invoke(it) as? String }.getOrNull() }
            if (id == null || name.isNullOrBlank()) emptyList() else listOf(
                NativePlayerMenuEntry(
                    PlayerMenuAction("DOWNLOAD_TTML", -1),
                    ContentExportKind.TTML_LYRICS.label(activity.resources.configuration.locales[0].language),
                    null,
                    activity.getDrawable(android.R.drawable.stat_sys_download),
                    true,
                    false,
                ) {
                    dismiss()
                    integration.downloadTtml(activity, id, name)
                },
            )
        } ?: emptyList()
        return result + navigation.map { entry ->
            val text = checkNotNull(navigationText[entry.action.key])
            entry.copy(subtitle = text.text.toString(), enabled = text.isEnabled)
        } + download
    }

    private fun buildEntry(key: String, position: Int, model: Any, onClick: (View) -> Unit): NativePlayerMenuEntry {
        val identity = PlayerMenuAction(key, position)
        return NativePlayerMenuEntry(
            identity,
            title.invoke(model) as? String ?: "",
            subtitle.invoke(model) as? String,
            icon.invoke(model) as? Drawable,
            enabled.invoke(model) as Boolean,
            checked.invoke(model) as Boolean,
            onClick,
        )
    }

    private fun unwrapTarget(raw: Any): Any {
        var current = raw
        repeat(4) {
            if (current.javaClass.name != "com.apple.android.music.model.extensions.DelegatingCollectionItemView") return current
            val inner = runCatching { current.javaClass.getDeclaredField("item").apply { isAccessible = true }.get(current) }.getOrNull()
            if (inner == null || inner === current) return current
            current = inner
        }
        return current
    }

    private fun callNoArgString(target: Any, methodName: String): String? =
        runCatching { target.javaClass.getMethod(methodName).invoke(target) as? String }.getOrNull()

    fun prepareNavigation(hiddenParent: ViewGroup) {
        val main = commands.get(source) as List<*>
        if ((getCount.invoke(source) as Int) == main.size) return
        val type = PluginProfiles.method("more-adapter-item-type").invoke(adapter, 0)
        val holder = PluginProfiles.method("more-adapter-create-holder").invoke(adapter, list, type)
        val view = PluginProfiles.field("more-holder-view").get(holder) as View
        hiddenParent.addView(view, ViewGroup.LayoutParams(0, 0))
        PluginProfiles.method("more-adapter-bind-holder").invoke(adapter, holder, 0, emptyList<Any>())
        // DataBinding clears XML binding tags. Read its verified view fields instead.
        val binding = PluginProfiles.field("more-holder-binding").get(holder)
        navigation = listOf("OPEN_ALBUM" to "more-header-album", "OPEN_ARTIST" to "more-header-artist").mapNotNull { (key, field) ->
            val text = (PluginProfiles.field(field).get(binding) as TextView)
                .takeIf { it.text.isNotBlank() && it.hasOnClickListeners() } ?: return@mapNotNull null
            val label = activity.getString(activity.resources.getIdentifier(
                if (key == "OPEN_ALBUM") "player_go_to_album" else "player_go_to_artist", "string", activity.packageName).takeIf { it != 0 }
                ?: android.R.string.untitled)
            navigationText[key] = text
            NativePlayerMenuEntry(PlayerMenuAction(key, 0), label, text.text.toString(), null, text.isEnabled, false) { text.performClick() }
        }
    }
}

/** Native click parameters are (int, View, item) in 1606-1609 and 1613, (item, View, int) in 1611; bind by type. */
internal fun bindMoreMenuClickArguments(types: Array<Class<*>>, position: Int, view: Any?, item: Any?): Array<Any?> =
    types.map { type ->
        when {
            type == Integer.TYPE -> position
            View::class.java.isAssignableFrom(type) -> view
            else -> item
        }
    }.toTypedArray()
