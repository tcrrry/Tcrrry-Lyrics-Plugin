package dev.kifranei.ampp.media

import android.content.res.Resources
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class ContentDownloadArtistControl { INFO, PLAY, BACK, TOOLBAR_ACTION }

internal data class ContentDownloadArtistLabels(val values: Map<ContentDownloadArtistControl, Set<String>>) {
    fun control(descriptions: List<*>?): ContentDownloadArtistControl? {
        val matches = descriptions.orEmpty().filterIsInstance<String>().map(::normalizeContentDownloadTitle).toSet()
        return values.filterValues { labels -> labels.any(matches::contains) }.keys.singleOrNull()
    }

    companion object {
        fun fromResources(resources: Resources, packageName: String): ContentDownloadArtistLabels {
            fun strings(vararg names: String) = names.mapNotNull { name ->
                runCatching {
                    resources.getIdentifier(name, "string", packageName).takeIf { it != 0 }
                        ?.let(resources::getString)?.let(::normalizeContentDownloadTitle)?.takeIf(String::isNotEmpty)
                }.getOrNull()
            }.toSet()
            // Resource names verified in Apple Music 7.0.0-beta/1606 and 1609.
            return ContentDownloadArtistLabels(mapOf(
                ContentDownloadArtistControl.INFO to strings("ax_artist_information_button"),
                ContentDownloadArtistControl.PLAY to strings("play_button"),
                ContentDownloadArtistControl.BACK to strings("navigate_up", "nav_app_bar_navigate_up_description", "back"),
                ContentDownloadArtistControl.TOOLBAR_ACTION to strings("share", "more_options"),
            ))
        }
    }
}

internal data class ContentDownloadArtistNode(
    val bounds: ContentDownloadBounds,
    val control: ContentDownloadArtistControl? = null,
    val actionable: Boolean = false,
    val hasText: Boolean = false,
)

/** Ordinary glyphs do not require artist controls. A used fallback stays header-bound until the page changes. */
internal class ContentDownloadArtistEntryState {
    private var usedFallback = false
    fun clear() { usedFallback = false }
    fun placement(title: ContentDownloadBounds?, titleFound: Boolean, width: Int, height: Int, density: Float, rtl: Boolean,
        fallback: () -> ContentDownloadButtonPlacement?): ContentDownloadButtonPlacement? {
        if (!usedFallback && (titleFound || title != null)) {
            return title?.let { contentDownloadButtonPlacement(it, width, height, density, rtl) }
        }
        return fallback().also { if (it != null) usedFallback = true }
    }
}

internal class ContentDownloadArtistSnapshotCache<T> {
    private var value: T? = null
    private var refreshedAt: Long? = null
    private var dirty = true
    fun invalidate() { dirty = true }
    fun clear() { value = null; refreshedAt = null; dirty = true }
    fun get(now: Long, refresh: () -> T?): T? {
        if (dirty || refreshedAt == null || now - checkNotNull(refreshedAt) >= 500) {
            dirty = false; refreshedAt = now; value = refresh()
        }
        return value
    }
}

internal data class ContentDownloadArtistHandle(val path: List<Int>, val node: ContentDownloadArtistNode)
internal data class ContentDownloadArtistTree(val handles: List<ContentDownloadArtistHandle>, val children: Map<List<Int>, List<Int>>)
internal data class ContentDownloadArtistTrackedTree(
    val tree: ContentDownloadArtistTree,
    val neededChildren: Map<List<Int>, Set<Int>>,
    val watchedParents: Set<List<Int>>,
)

/** The verified root is unmerged: an icon description labels its nearest enclosing click target. */
private fun artistButtonHandles(handles: List<ContentDownloadArtistHandle>): List<ContentDownloadArtistHandle> {
    val inherited = LinkedHashMap<List<Int>, MutableSet<ContentDownloadArtistControl>>()
    for (label in handles.filter { !it.node.actionable && it.node.control != null }) {
        val parent = handles.filter { candidate -> candidate.node.actionable && candidate.path.size < label.path.size &&
            label.path.take(candidate.path.size) == candidate.path && label.node.bounds.left >= candidate.node.bounds.left &&
            label.node.bounds.right <= candidate.node.bounds.right && label.node.bounds.top >= candidate.node.bounds.top &&
            label.node.bounds.bottom <= candidate.node.bounds.bottom }.maxByOrNull { it.path.size } ?: continue
        inherited.getOrPut(parent.path) { LinkedHashSet() } += checkNotNull(label.node.control)
    }
    return handles.map { handle ->
        val roles = inherited[handle.path] ?: return@map handle
        handle.node.control?.let(roles::add)
        handle.copy(node = handle.node.copy(control = roles.singleOrNull()))
    }
}

/** Uses the same native semantics contracts as title positioning; never reads image URLs or changes profiles. */
internal class ContentDownloadArtistAnchor(private val contracts: ContentDownloadContracts, private val packageName: String) {
    private var locale = ""
    private var density = 0f
    private var labels: ContentDownloadArtistLabels? = null
    private data class Owner(val view: WeakReference<View>, val tree: ContentDownloadArtistTrackedTree)
    private val cache = ContentDownloadArtistSnapshotCache<List<Owner>>()

    fun invalidate() = cache.invalidate()
    fun clear() = cache.clear()

    fun placement(root: View, rootPosition: IntArray, now: Long): ContentDownloadButtonPlacement? {
        if (!root.isAttachedToWindow || !root.isShown) return null
        val visible = Rect()
        if (!root.getGlobalVisibleRect(visible)) return null
        val viewport = ContentDownloadBounds((visible.left - rootPosition[0]).toFloat(), (visible.top - rootPosition[1]).toFloat(),
            (visible.right - rootPosition[0]).toFloat(), (visible.bottom - rootPosition[1]).toFloat())
        val currentLocale = root.resources.configuration.locales.toLanguageTags()
        val currentDensity = root.resources.displayMetrics.density
        if (labels == null || locale != currentLocale || density != currentDensity) {
            locale = currentLocale
            density = currentDensity
            labels = ContentDownloadArtistLabels.fromResources(root.resources, packageName)
            cache.clear()
        }
        val nativeLabels = labels ?: return null
        fun refresh() = discover(root, nativeLabels, currentDensity)
        fun current(owners: List<Owner>): Pair<Boolean, ContentDownloadButtonPlacement?> {
            var placement: ContentDownloadButtonPlacement? = null
            for (owner in owners) {
                val view = owner.view.get()?.takeIf { it.isShown && it.isAttachedToWindow } ?: return false to null
                val semantics = contracts.call("content-download-compose-semantics", view) ?: return false to null
                val semanticRoot = contracts.call("content-download-semantics-root", semantics) ?: return false to null
                val snapshot = currentSnapshot(owner.tree, semanticRoot, nativeLabels) ?: return false to null
                val position = IntArray(2); view.getLocationOnScreen(position)
                val dx = (position[0] - rootPosition[0]).toFloat()
                val dy = (position[1] - rootPosition[1]).toFloat()
                val candidate = contentDownloadArtistButtonPlacement(snapshot.map { it.copy(bounds = it.bounds.translated(dx, dy)) },
                    viewport, root.width, root.height, currentDensity)
                if (candidate != null) {
                    if (placement != null) return true to null
                    placement = candidate
                }
            }
            return true to placement
        }
        val owners = cache.get(now, ::refresh) ?: return null
        val first = current(owners)
        if (first.first) return first.second
        // A changed toolbar shape or detached/replaced node invalidates the entry before another frame is drawn.
        cache.invalidate()
        return cache.get(now, ::refresh)?.let(::current)?.second
    }

    private fun discover(root: View, labels: ContentDownloadArtistLabels, density: Float): List<Owner>? {
        val views = ArrayDeque<View>(); views.add(root)
        var visited = 0
        val owners = ArrayList<Owner>()
        while (views.isNotEmpty() && visited++ < 256) {
            val view = views.removeFirst()
            if (!view.isShown || !view.isAttachedToWindow) continue
            if (view is ViewGroup) {
                if (view.childCount > 256) return null
                for (index in 0 until view.childCount) views.add(view.getChildAt(index))
            }
            val semanticOwner = contracts.call("content-download-compose-semantics", view) ?: continue
            val semanticRoot = contracts.call("content-download-semantics-root", semanticOwner) ?: continue
            val tree = scan(semanticRoot, labels) ?: return null
            track(tree, density)?.let { owners += Owner(WeakReference(view), it) }
        }
        return owners.takeIf { views.isEmpty() }
    }

    internal fun snapshot(semanticRoot: Any, labels: ContentDownloadArtistLabels): List<ContentDownloadArtistNode>? =
        scan(semanticRoot, labels)?.handles?.let(::artistButtonHandles)?.map { it.node }

    internal fun scan(semanticRoot: Any, labels: ContentDownloadArtistLabels): ContentDownloadArtistTree? {
        val rootId = nodeId(semanticRoot) ?: return null
        val nodes = ArrayDeque<Pair<Any, List<Int>>>(); nodes.add(semanticRoot to listOf(rootId))
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val result = ArrayList<ContentDownloadArtistHandle>()
        val shapes = LinkedHashMap<List<Int>, List<Int>>()
        var count = 0
        while (nodes.isNotEmpty() && count++ < 512) {
            val (current, path) = nodes.removeFirst()
            if (!seen.add(current)) continue
            val properties = semanticsProperties(current)
            if (properties.containsKey("InvisibleToUser") || properties.containsKey("HideFromAccessibility")) continue
            val actionable = properties.containsKey("OnClick")
            val hasText = (properties["Text"] as? List<*>)?.isNotEmpty() == true
            val control = labels.control(properties["ContentDescription"] as? List<*>)
            if (actionable || hasText || control != null) {
                val bounds = readBounds(contracts.call("content-download-semantics-bounds", current)) ?: return null
                if (bounds.valid()) result += ContentDownloadArtistHandle(path, ContentDownloadArtistNode(bounds,
                    control, actionable, hasText))
            }
            val children = contracts.call("content-download-semantics-children", current, true, false) as? List<*> ?: return null
            if (children.size > 512) return null
            val ids = ArrayList<Int>()
            for (child in children.filterNotNull()) {
                val id = nodeId(child) ?: return null
                ids += id; nodes.add(child to (path + id))
            }
            shapes[path] = ids
        }
        return ContentDownloadArtistTree(result, shapes).takeIf { nodes.isEmpty() }
    }

    internal fun track(tree: ContentDownloadArtistTree, density: Float): ContentDownloadArtistTrackedTree? {
        val buttons = artistButtonHandles(tree.handles)
        val back = buttons.filter { it.node.actionable && it.node.control == ContentDownloadArtistControl.BACK }.singleOrNull() ?: return null
        val rowTop = (back.node.bounds.top + back.node.bounds.bottom) / 2 - 32 * density
        val rowBottom = rowTop + 64 * density
        val toolbar = buttons.filter { (it.node.actionable || it.node.hasText) && it.node.bounds.top < rowBottom && it.node.bounds.bottom > rowTop }
        val header = buttons.filter { it.node.actionable && it.node.control in setOf(ContentDownloadArtistControl.INFO, ContentDownloadArtistControl.PLAY) }
        if (header.none { it.node.control == ContentDownloadArtistControl.INFO } || header.none { it.node.control == ContentDownloadArtistControl.PLAY }) return null
        val selected = (toolbar + header).map { it.path }.toSet()
        val handles = tree.handles.filter { it.path in selected || it.node.control != null && selected.any { parent -> it.path.take(parent.size) == parent } }
        if (handles.size > 24) return null
        val needed = LinkedHashMap<List<Int>, MutableSet<Int>>()
        val watched = LinkedHashSet<List<Int>>()
        for (handle in handles) for (length in 1 until handle.path.size) {
            val parent = handle.path.take(length)
            needed.getOrPut(parent) { LinkedHashSet() } += handle.path[length]
            if (toolbar.any { handle.path.take(it.path.size) == it.path }) watched += parent
        }
        if (needed.size > 32 || needed.keys.any { (tree.children[it]?.size ?: Int.MAX_VALUE) > 64 }) return null
        return ContentDownloadArtistTrackedTree(tree.copy(handles = handles), needed, watched)
    }

    /** Resolve cached IDs through just their ancestor paths. Read fresh bounds/config; never walk content descendants. */
    internal fun currentSnapshot(tracked: ContentDownloadArtistTrackedTree, semanticRoot: Any, labels: ContentDownloadArtistLabels): List<ContentDownloadArtistNode>? {
        val rootId = nodeId(semanticRoot) ?: return null
        val handles = tracked.tree.handles.associateBy { it.path }
        if (handles.keys.any { it.firstOrNull() != rootId }) return null
        val nodes = ArrayDeque<Pair<Any, List<Int>>>(); nodes.add(semanticRoot to listOf(rootId))
        val result = ArrayList<ContentDownloadArtistHandle>()
        while (nodes.isNotEmpty()) {
            val (current, path) = nodes.removeFirst()
            val properties = semanticsProperties(current)
            if (properties.containsKey("InvisibleToUser") || properties.containsKey("HideFromAccessibility")) return null
            if (path in handles) {
                val bounds = readBounds(contracts.call("content-download-semantics-bounds", current)) ?: return null
                result += ContentDownloadArtistHandle(path, ContentDownloadArtistNode(bounds, labels.control(properties["ContentDescription"] as? List<*>),
                    properties.containsKey("OnClick"), (properties["Text"] as? List<*>)?.isNotEmpty() == true))
            }
            val needed = tracked.neededChildren[path] ?: continue
            val children = contracts.call("content-download-semantics-children", current, true, false) as? List<*> ?: return null
            if (children.size > 64) return null
            val childrenById = LinkedHashMap<Int, Any>()
            for (child in children.filterNotNull()) childrenById[nodeId(child) ?: return null] = child
            if (path in tracked.watchedParents && childrenById.keys.toList() != tracked.tree.children[path]) return null
            if (!childrenById.keys.containsAll(needed)) return null
            for (id in needed) nodes.add(checkNotNull(childrenById[id]) to (path + id))
        }
        return artistButtonHandles(result).map { it.node }
    }

    private fun nodeId(node: Any): Int? = contracts.field("content-download-semantics-id", node) as? Int

    internal fun semanticsProperties(node: Any): Map<String, Any?> {
        val config = contracts.call("content-download-semantics-config", node) as? Iterable<*> ?: return emptyMap()
        val result = LinkedHashMap<String, Any?>()
        config.take(64).forEach { item ->
            val entry = item as? Map.Entry<*, *> ?: return@forEach
            val name = contracts.field("content-download-semantics-key-name", entry.key) as? String ?: return@forEach
            if (name in ARTIST_POSITION_PROPERTIES) result[name] = entry.value
        }
        return result
    }

    private fun readBounds(value: Any?): ContentDownloadBounds? {
        fun coordinate(name: String) = (contracts.field("content-download-rect-$name", value) as? Number)?.toFloat()
        return ContentDownloadBounds(coordinate("left") ?: return null, coordinate("top") ?: return null,
            coordinate("right") ?: return null, coordinate("bottom") ?: return null)
    }
}

private val ARTIST_POSITION_PROPERTIES = setOf("ContentDescription", "OnClick", "Text", "InvisibleToUser", "HideFromAccessibility")

/** A real, visible artist header and native topbar are required; no screen-coordinate guess survives scrolling. */
internal fun contentDownloadArtistButtonPlacement(
    nodes: List<ContentDownloadArtistNode>, viewport: ContentDownloadBounds, width: Int, height: Int, density: Float,
): ContentDownloadButtonPlacement? {
    if (!viewport.valid() || !density.isFinite() || density <= 0) return null
    val size = max(1, (40 * density).roundToInt())
    val gap = 8 * density
    fun ContentDownloadBounds.inside() = valid() && left >= max(0f, viewport.left) && top >= max(0f, viewport.top) &&
        right <= min(width.toFloat(), viewport.right) && bottom <= min(height.toFloat(), viewport.bottom)
    fun control(type: ContentDownloadArtistControl) = nodes.filter { it.actionable && it.control == type }.singleOrNull()?.bounds
        ?.takeIf { it.inside() && it.width >= 24 * density && it.height >= 24 * density }
    val info = control(ContentDownloadArtistControl.INFO) ?: return null
    val play = control(ContentDownloadArtistControl.PLAY) ?: return null
    val back = control(ContentDownloadArtistControl.BACK) ?: return null
    val centerY = (back.top + back.bottom) / 2
    val actions = nodes.filter { it.actionable && it.control == ContentDownloadArtistControl.TOOLBAR_ACTION && it.bounds.inside() &&
        abs((it.bounds.top + it.bounds.bottom) / 2 - centerY) <= 8 * density }.map { it.bounds }
    if (actions.isEmpty()) return null
    val toolbarBottom = max(back.bottom, actions.maxOf { it.bottom })
    if (info.width < 32 * density || info.height < 32 * density || play.width < 32 * density || play.height < 32 * density ||
        info.top < toolbarBottom + gap || play.top < toolbarBottom + gap ||
        abs((info.top + info.bottom - play.top - play.bottom) / 2) > 16 * density ||
        info.left < play.right && info.right > play.left) return null
    fun ContentDownloadArtistNode.obstacle(): ContentDownloadBounds {
        val touchWidth = if (actionable) max(bounds.width, 48 * density) else bounds.width
        val touchHeight = if (actionable) max(bounds.height, 48 * density) else bounds.height
        val x = (bounds.left + bounds.right) / 2
        val y = (bounds.top + bounds.bottom) / 2
        return ContentDownloadBounds(x - touchWidth / 2 - gap, y - touchHeight / 2 - gap,
            x + touchWidth / 2 + gap, y + touchHeight / 2 + gap)
    }
    val towardRight = actions.all { it.left >= back.right }
    val towardLeft = actions.all { it.right <= back.left }
    if (!towardRight && !towardLeft) return null
    val start = max(viewport.left, if (towardRight) back.right else actions.maxOf { it.right })
    val end = min(viewport.right, if (towardRight) actions.minOf { it.left } else back.left)
    val top = (centerY - size / 2f).roundToInt()
    val obstacles = nodes.filter { it.actionable || it.hasText }.map { it.obstacle() }
        .filter { it.valid() && top + size > it.top && top < it.bottom }.sortedBy { it.left }
    val spaces = ArrayList<Pair<Float, Float>>()
    var cursor = start
    for (obstacle in obstacles) {
        if (obstacle.right <= cursor || obstacle.left >= end) continue
        if (obstacle.left > cursor) spaces += cursor to min(end, obstacle.left)
        cursor = max(cursor, obstacle.right)
    }
    if (cursor < end) spaces += cursor to end
    for ((left, right) in if (towardRight) spaces.asReversed() else spaces) {
        val x = if (towardRight) floor(right - size).toInt() else ceil(left).toInt()
        val target = ContentDownloadBounds(x.toFloat(), top.toFloat(), (x + size).toFloat(), (top + size).toFloat())
        if (x < left || x + size > right || !target.inside()) continue
        val icon = max(1, (18 * density).roundToInt()).coerceAtMost(size)
        return ContentDownloadButtonPlacement(x, top, size, (size - icon) / 2, (size - icon) / 2, icon)
    }
    return null
}
