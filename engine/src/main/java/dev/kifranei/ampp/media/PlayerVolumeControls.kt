package dev.kifranei.ampp.media

/** Each pane owns its own IDs. Cache complete control groups, then select the visible one. */
internal object PlayerVolumeControls {
    fun <T> discover(root: T, isControls: (T) -> Boolean, children: (T) -> List<T>): List<T> {
        val groups = ArrayList<T>()
        fun visit(node: T) {
            if (isControls(node)) groups += node else children(node).forEach(::visit)
        }
        visit(root)
        return groups
    }

    fun <T> select(groups: List<T>, opacity: (T) -> Float?): T? {
        var selected: T? = null
        var best = .01f
        groups.forEach { group ->
            val alpha = opacity(group)
            // Later groups draw above earlier groups while native panes overlap.
            if (alpha != null && alpha.isFinite() && alpha > .01f && alpha >= best) {
                selected = group; best = alpha
            }
        }
        return selected
    }
}
