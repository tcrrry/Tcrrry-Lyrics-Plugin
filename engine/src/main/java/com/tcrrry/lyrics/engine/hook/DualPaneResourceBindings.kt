package com.tcrrry.lyrics.engine.hook

import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.tcrrry.lyrics.engine.hook.ModernMethodHook as XC_MethodHook
import com.tcrrry.lyrics.engine.ModuleConstants
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

internal object DualPaneResourceHook {
    private val rightLyricsCallback = RightLyricsPaneLayout::apply
    private val typographyCallback = TabletLyricTypography::applyToInflatedLayout
    fun install() {
        LayoutInflationRegistry.register("bottom_navigation") { view ->
            if (!FragmentDualPaneResources.isLegacyRoot(view)) return@register
            // XmlPullParser inflation can report the outer activity layout when
            // its tree merely contains bottom_navigation. Recover the included
            // navigation root so this keeps the exact-root semantics of the old
            // resource hook.
            val root = ConstraintLayoutPane.resolveBottomNavigationRoot(view) ?: return@register
            dualPaneDebug(
                "bottom navigation layout callback root=" + root.javaClass.name +
                    " orientation=" + root.resources.configuration.orientation,
            )
            ConstraintLayoutPane.installLandscapeBottomNavigation(root, targetBuild(root.context))
        }
        LayoutInflationRegistry.register("fragment_player_main") { view ->
            if (!FragmentDualPaneResources.isLegacyRoot(view)) return@register
            val root = view as? ViewGroup ?: return@register
            dualPaneDebug("layout callback root=" + root.javaClass.name + " orientation=" + root.resources.configuration.orientation)
            DualPaneShell.installImmediately(root)
        }
        hookTabletLandscapeLyricsSheet()
        TranslationsPopupOffsetHook.install()
        // The modified package changes `lyrics_line_text_size` only in its
        // w640dp resource table. Hook the two layouts that actually reference
        // that dimension so normal and karaoke lyrics receive the same 35sp
        // value at inflation time, without globally replacing phone resources.
        hookTabletLyricTextLayout("lyrics_line")
        hookTabletLyricTextLayout("lyrics_word_karaoke")
    }

    private fun hookTabletLandscapeLyricsSheet() {
        LayoutInflationRegistry.register("fragment_player_lyrics_sheet") { view ->
            if (FragmentDualPaneResources.isLegacyRoot(view)) rightLyricsCallback(view)
        }
    }

    private fun hookTabletLyricTextLayout(
        layoutName: String,
    ) {
        LayoutInflationRegistry.register(layoutName) { view ->
            if (FragmentDualPaneResources.isLegacyRoot(view)) typographyCallback(view)
        }
    }
}

/**
 * Mirrors the modified layout-land/fragment_player_lyrics_sheet.xml exactly.
 * This callback is scoped to the dedicated lyrics layout, not the player
 * layout, so it never changes the left song/album-art pane.
 */
internal object RightLyricsPaneLayout {
    private const val CURRENT_PLAYER_ITEM = "current_player_item"
    private const val RECYCLER_VIEW_GRADIENTS = "recycler_view_gradients"
    private const val CONTROLS = "controls"
    private const val CONTROLS_TAP_TARGET = "controls_tap_target"
    private const val ALPHA_GRADIENT_FRAME_LAYOUT =
        "com.apple.android.music.common.views.AlphaGradientFrameLayout"
    private const val TOP_EDGE_FRACTION = 0.15f
    private const val TOP_CLEAR_FRACTION = 0.075f
    private const val TOP_CLEAR_WITHIN_FADE_FRACTION = 0.25f
    private const val BOTTOM_EDGE_FRACTION = 0.15f

    fun apply(root: View) {
        if (!TabletModeQualifier.isEligible(root.context)) return
        val resources = root.resources
        val gradients = root.findViewById<View>(targetId(resources, RECYCLER_VIEW_GRADIENTS))
            ?: return

        // The modified root has layout_marginTop="0dp".
        val rootParams = root.layoutParams as? ViewGroup.MarginLayoutParams
        if (rootParams != null) {
            rootParams.topMargin = 0
            root.layoutParams = rootParams
        }
        hide(root, resources, CURRENT_PLAYER_ITEM)
        hide(root, resources, CONTROLS)
        hide(root, resources, CONTROLS_TAP_TARGET)
        ConstraintLayoutPane.anchorTopToParent(gradients, RECYCLER_VIEW_GRADIENTS)
        configureVerticalGradientEdges(gradients)
        root.requestLayout()
        dualPaneDebug(
            "right lyrics pane landscape resource installed root=" + System.identityHashCode(root) +
                " gradients=" + gradients.id,
        )
    }

    private fun targetId(resources: android.content.res.Resources, name: String): Int =
        resources.getIdentifier(name, "id", ModuleConstants.TARGET_PACKAGE)
            .takeIf { it != 0 }
            ?: error("id/$name resource ID was unavailable")

    private fun hide(root: View, resources: android.content.res.Resources, name: String) {
        root.findViewById<View>(targetId(resources, name))?.visibility = View.GONE
    }

    /** Uses the target view's single DST_IN layer so scrolling rows fade continuously. */
    private fun configureVerticalGradientEdges(gradients: View) {
        if (gradients.javaClass.name != ALPHA_GRADIENT_FRAME_LAYOUT) return
        gradients.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) applyVerticalGradientEdges(gradients)
        }
        gradients.post { applyVerticalGradientEdges(gradients) }
    }

    fun reapplyVerticalGradientEdges(gradients: View) {
        applyVerticalGradientEdges(gradients)
    }

    private fun applyVerticalGradientEdges(gradients: View) {
        if (gradients.javaClass.name != ALPHA_GRADIENT_FRAME_LAYOUT || gradients.height <= 0) return
        runCatching {
            val profile = (if (FragmentDualPaneResources.isLegacyRoot(gradients))
                AlphaGradientEdgeFieldProfiles.resolve(gradients.javaClass)
            else AlphaGradientEdgeFieldProfiles.resolve(gradients.javaClass, targetBuild(gradients.context)))
                ?: error("AlphaGradientFrameLayout edge profile was unavailable")
            profile.vertical.forEach { fieldName ->
                setGradientEdge(gradients, fieldName, enabled = true)
            }
            profile.horizontal.forEach { fieldName ->
                setGradientEdge(gradients, fieldName, enabled = false)
            }
            val topFadeColors = intArrayOf(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                Color.BLACK,
            )
            val topFadePositions = floatArrayOf(
                0f,
                TOP_CLEAR_WITHIN_FADE_FRACTION,
                1f,
            )
            val topFadeColorsField = dualPaneField(gradients.javaClass, "a")
                ?: error("AlphaGradientFrameLayout.a was unavailable")
            val topFadePositionsField = dualPaneField(gradients.javaClass, "e")
                ?: error("AlphaGradientFrameLayout.e was unavailable")
            topFadeColorsField.set(gradients, topFadeColors)
            topFadePositionsField.set(gradients, topFadePositions)
            val bottomFadeColors = intArrayOf(
                Color.BLACK,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
            )
            val bottomFadePositions = floatArrayOf(
                0f,
                1f - TOP_CLEAR_WITHIN_FADE_FRACTION,
                1f,
            )
            val bottomFadeColorsField = dualPaneField(gradients.javaClass, "b")
                ?: error("AlphaGradientFrameLayout.b was unavailable")
            val bottomFadePositionsField = dualPaneField(gradients.javaClass, "f")
                ?: error("AlphaGradientFrameLayout.f was unavailable")
            bottomFadeColorsField.set(gradients, bottomFadeColors)
            bottomFadePositionsField.set(gradients, bottomFadePositions)
            val topEdgeSize = (gradients.height * TOP_EDGE_FRACTION)
                .roundToInt()
                .coerceAtLeast(1)
            val bottomEdgeSize = (gradients.height * BOTTOM_EDGE_FRACTION)
                .roundToInt()
                .coerceAtLeast(1)
            val setVerticalFadeSizes = gradients.javaClass.getDeclaredMethod(
                "d",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            setVerticalFadeSizes.invoke(gradients, topEdgeSize, bottomEdgeSize)
            gradients.invalidate()
        }.onFailure {
            dualPaneDebug("right lyrics pane vertical gradient setup failed: $it")
        }
    }

    private fun setGradientEdge(gradients: View, fieldName: String, enabled: Boolean) {
        val field = dualPaneField(gradients.javaClass, fieldName)
            ?: error("AlphaGradientFrameLayout.$fieldName was unavailable")
        field.setBoolean(gradients, enabled)
    }
}

/**
 * Apple shows the translations popup synchronously from the button onClick as
 * PopupWindow.showAsDropDown(anchor, x, y). With the dual-pane landscape
 * controls strip hidden the lyrics sheet reaches the bottom edge, so the
 * stock popup would open below the visible sheet and clip black. This
 * framework hook shifts only the popup's own y offset by the popup's own
 * measured height plus the anchor button's height (unless overlapAnchor is
 * set, in which case the framework already counts the anchor), so the popup's
 * bottom edge lands on the button's top edge; the anchor view,
 * ConstraintLayout, lyrics metrics and bottom-bar boundary are never touched.
 * Matching is strict (popup content id + lyrics-sheet ancestor + tablet
 * predicate), so every other PopupWindow in the target process passes through
 * unchanged. The four-argument showAsDropDown is hooked because the one- and
 * three-argument overloads both delegate to it, so one hook covers every
 * entry point without double-shifting.
 */
internal object TranslationsPopupOffsetHook {
    private const val TRANSLATIONS_POPUP_MENU = "translations_popup_menu"
    private const val CONTROLS = "controls"
    private const val RECYCLER_VIEW_GRADIENTS = "recycler_view_gradients"
    private const val SPARSE_DEBUG_INTERVAL_MS = 60_000L

    private val installed = AtomicBoolean(false)
    @Volatile
    private var lastDebugUptime = 0L

    /**
     * Idempotent: DualPaneResourceHook.install may run more than once, and a
     * framework hook failure must never take dual-pane down with it.
     */
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            val showAsDropDown = android.widget.PopupWindow::class.java.getDeclaredMethod(
                "showAsDropDown",
                View::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            ModernXposedRuntime.hookMethod(showAsDropDown, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    shiftTranslationsPopupOffset(param)
                }
            })
            dualPaneDebug("translations popup offset hook installed")
        }.onFailure {
            dualPaneDebug("translations popup offset hook registration failed: $it")
        }
    }

    private fun shiftTranslationsPopupOffset(param: XC_MethodHook.MethodHookParam) {
        val popup = param.thisObject as? android.widget.PopupWindow ?: return
        val anchor = param.args.firstOrNull() as? View ?: return
        if (!TabletModeQualifier.isEligible(anchor.context)) return
        val resources = anchor.resources
        val popupMenuId = resources.getIdentifier(
            TRANSLATIONS_POPUP_MENU,
            "id",
            ModuleConstants.TARGET_PACKAGE,
        ).takeIf { it != 0 } ?: run {
            sparseDebug("translations popup offset skipped: id/translations_popup_menu missing")
            return
        }
        val contentView = runCatching { popup.contentView }.getOrNull() ?: return
        if (contentView.id != popupMenuId) return
        if (findLyricsSheetRoot(anchor, resources) == null) {
            sparseDebug("translations popup offset skipped: lyrics sheet ancestor missing")
            return
        }
        val measureResult = runCatching {
            contentView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
        }
        if (measureResult.isFailure) {
            sparseDebug("translations popup offset skipped: contentView measure failed")
            return
        }
        val popupHeight = contentView.measuredHeight
        if (popupHeight <= 0 || anchor.height <= 0) {
            sparseDebug(
                "translations popup offset skipped: popupHeight=" + popupHeight +
                    " anchorHeight=" + anchor.height,
            )
            return
        }
        val originalYOffset = param.args[2] as? Int ?: return
        val overlapAnchor = runCatching { popup.overlapAnchor }.getOrDefault(false)
        val shiftAmount = popupHeight + if (overlapAnchor) 0 else anchor.height
        val shiftedYOffset = originalYOffset - shiftAmount
        param.args[2] = shiftedYOffset
        sparseDebug(
            "translations popup yOffset shifted from " + originalYOffset + " to " + shiftedYOffset +
                " popupHeight=" + popupHeight + " anchorHeight=" + anchor.height,
        )
    }

    /**
     * The first ancestor containing both the hidden controls container and
     * the recycler gradients is the landscape lyrics sheet root; the gate
     * keeps the shift on popups anchored inside the lyrics sheet only.
     */
    private fun findLyricsSheetRoot(anchor: View, resources: android.content.res.Resources): View? {
        val controlsId = resources.getIdentifier(CONTROLS, "id", ModuleConstants.TARGET_PACKAGE)
            .takeIf { it != 0 } ?: return null
        val gradientsId = resources.getIdentifier(RECYCLER_VIEW_GRADIENTS, "id", ModuleConstants.TARGET_PACKAGE)
            .takeIf { it != 0 } ?: return null
        var candidate = anchor.parent as? View
        while (candidate != null) {
            if (
                candidate.findViewById<View>(controlsId) != null &&
                candidate.findViewById<View>(gradientsId) != null
            ) {
                return candidate
            }
            candidate = candidate.parent as? View
        }
        return null
    }

    /** The framework hook runs for every popup; keep diagnostics sparse. */
    private fun sparseDebug(message: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastDebugUptime < SPARSE_DEBUG_INTERVAL_MS) return
        lastDebugUptime = now
        dualPaneDebug(message)
    }
}

