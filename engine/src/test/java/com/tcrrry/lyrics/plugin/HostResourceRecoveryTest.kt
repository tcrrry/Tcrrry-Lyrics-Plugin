package com.tcrrry.lyrics.plugin

import android.content.res.Resources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostResourceRecoveryTest {
    @Test fun supportsExpectedAppleMusicVersions() {
        assertTrue(HostResourceRecoveryPolicy.supports(1609L, "7.0.0-beta"))
        assertTrue(HostResourceRecoveryPolicy.supports(1613L, "7.0.0-beta"))
        assertTrue(HostResourceRecoveryPolicy.supports(1614L, "7.0.0"))
        assertTrue(HostResourceRecoveryPolicy.supports(1622L, "7.0.1"))
        assertTrue(HostResourceRecoveryPolicy.supports(1626L, "7.0.3"))

        assertFalse(HostResourceRecoveryPolicy.supports(1626L, "7.0.1"))
        assertFalse(HostResourceRecoveryPolicy.supports(1606L, "7.0.0-beta"))
        assertFalse(HostResourceRecoveryPolicy.supports(1500L, "6.9.0"))
    }

    @Test fun acceptsNestedComplexColorTypeMismatchAndRejectsUnrelatedErrors() {
        val nestedCause = UnsupportedOperationException("Can't convert to ComplexColor: type=0x5")
        val wrapped = Resources.NotFoundException("Drawable com.apple.android.music:drawable/layer_apple_material_dark").apply {
            initCause(nestedCause)
        }
        assertTrue(HostResourceRecoveryPolicy.accepts("com.apple.android.music", "drawable", "drawable", wrapped))
        assertTrue(HostResourceRecoveryPolicy.accepts("com.tcrrry.ampplyrics.coexist", "color", "color", nestedCause))

        val hashType = IllegalArgumentException("Color value is not valid (type #0x5)")
        assertTrue(HostResourceRecoveryPolicy.accepts("com.apple.android.music", "color", "color", hashType))

        assertFalse(HostResourceRecoveryPolicy.accepts("android", "drawable", "drawable", wrapped))
        assertFalse(HostResourceRecoveryPolicy.accepts("com.apple.android.music", "string", "drawable", wrapped))
        assertFalse(
            HostResourceRecoveryPolicy.accepts(
                "com.apple.android.music",
                "drawable",
                "drawable",
                Resources.NotFoundException("Plain missing drawable"),
            ),
        )
    }
}
