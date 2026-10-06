package com.tcrrry.lyrics.engine.hook

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.tcrrry.lyrics.engine.hook.ModernMethodHook as XC_MethodHook
import com.tcrrry.lyrics.engine.ModuleConstants
import com.tcrrry.lyrics.engine.R
import com.tcrrry.lyrics.engine.config.TargetConfigClient
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

object TabletModeQualifier {
    fun isOfficialTablet(context: Context): Boolean {
        val tabletId = context.resources.getIdentifier(
            "is_tablet",
            "bool",
            ModuleConstants.TARGET_PACKAGE,
        )
        if (tabletId == 0) return false
        return runCatching { context.resources.getBoolean(tabletId) }.getOrDefault(false)
    }

    fun isOfficialTabletLandscape(context: Context): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            isOfficialTablet(context)

    fun isEligible(context: Context): Boolean =
        isOfficialTabletLandscape(context) && TargetConfigClient.currentSettings().dualPaneEnabled
}

internal object FlatLandscapeWindowPolicy {
    fun shouldReserveNavigationSpace(context: Context): Boolean {
        return shouldApplyCompensation(
            isTabletLandscape = TabletModeQualifier.isOfficialTabletLandscape(context),
            compensationEnabled = TargetConfigClient.currentSettings().navigationCompensationEnabled,
        )
    }

    fun shouldInstallBoundarySync(context: Context): Boolean {
        val enabled = shouldApplyCompensation(
            isTabletLandscape = TabletModeQualifier.isOfficialTabletLandscape(context),
            compensationEnabled = TargetConfigClient.currentSettings().navigationCompensationEnabled,
        )
        dualPaneDebug("flat boundary gate enabled=$enabled")
        return enabled
    }

    internal fun shouldApplyCompensation(
        isTabletLandscape: Boolean,
        compensationEnabled: Boolean,
    ): Boolean = isTabletLandscape && compensationEnabled
}

internal data class FlatPlayerBoundaryDecision(
    val reserveNavigationSpace: Boolean,
    val translationY: Int,
    val tabsVisible: Boolean,
)

/**
 * Phase 109 settled visual compensation: expanded always settles at
 * translationY 0, a settled collapsed sheet settles at exactly
 * -navigationInset. The full `tabsHeight` is used only to detect overlap.
 * The decision stays binary on `expanded` (sheetTop <=
 * rootHeight / 2) on purpose: the collapsed peek geometry is owned by
 * Apple's holder and is not measurable here, so no continuous
 * sheetTop-to-collapsed mapping could be verified. It is applied as visual
 * translationY only, never as layout margin, so the full-screen sheet keeps
 * its geometry (no black strip) and the native holder's animation targets
 * are untouched.
 */
internal object FlatPlayerBoundaryPolicy {
    fun decide(
        rootHeight: Int,
        sheetTop: Int,
        sheetBottom: Int,
        tabsTop: Int,
        tabsHeight: Int,
        navigationInset: Int = tabsHeight,
        wasNavigationSpaceReserved: Boolean,
    ): FlatPlayerBoundaryDecision {
        require(rootHeight > 0) { "rootHeight must be positive" }
        val expanded = sheetTop <= rootHeight / 2
        val sheetOverlapsTabs = sheetTop < tabsTop + tabsHeight && sheetBottom > tabsTop
        val collapsedOverlap = !expanded &&
            tabsTop > rootHeight / 2 &&
            sheetOverlapsTabs
        val reserveNavigationSpace = wasNavigationSpaceReserved || collapsedOverlap
        return FlatPlayerBoundaryDecision(
            reserveNavigationSpace = reserveNavigationSpace,
            // Expanded is the settled zero; a reserved collapsed sheet must
            // clear the navigation inset. The reservation latch makes the
            // collapsed state sticky
            // so the binary flip cannot oscillate around the midpoint.
            translationY = if (!expanded && reserveNavigationSpace) -navigationInset else 0,
            // Let the native holder own an expanded transition until the
            // collapsed geometry has established a navigation reservation.
            tabsVisible = !reserveNavigationSpace || !expanded,
        )
    }

    /**
     * Root-relative sheet top from window-space measurements, excluding the
     * visual translation the module applies to the outer player container.
     * getLocationInWindow includes ancestor translations, so without this
     * correction the compensation itself would move the measured sheet top
     * and flip the binary decision back and forth around the midpoint.
     */
    fun sheetTopRelativeToRoot(
        sheetWindowTop: Int,
        rootWindowTop: Int,
        containerTranslationY: Float,
    ): Int = sheetWindowTop - rootWindowTop - containerTranslationY.roundToInt()
}

