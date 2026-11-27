package com.tcrrry.lyrics.plugin

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbMixerSelectorTest {
    @Test
    fun prefersBitPerfectExactMatchOverDefaultAndHigherBitDepth() {
        val candidates = listOf(
            UsbMixerSelector.MixerCandidate(96000, AudioFormat.ENCODING_PCM_32BIT, 12, bitPerfect = false, index = 0),
            UsbMixerSelector.MixerCandidate(96000, AudioFormat.ENCODING_PCM_24BIT_PACKED, 12, bitPerfect = true, index = 1),
            UsbMixerSelector.MixerCandidate(96000, AudioFormat.ENCODING_PCM_32BIT, 12, bitPerfect = true, index = 2),
        )
        val picked = UsbMixerSelector.selectBest(96000, AudioFormat.ENCODING_PCM_24BIT_PACKED, 12, candidates)
        assertNotNull(picked)
        assertTrue(picked!!.bitPerfect)
        assertEquals(AudioFormat.ENCODING_PCM_24BIT_PACKED, picked.encoding)
    }

    @Test
    fun fallsBackToHighestBitDepthBitPerfectAtExactSampleRate() {
        val candidates = listOf(
            UsbMixerSelector.MixerCandidate(96000, AudioFormat.ENCODING_PCM_16BIT, 12, bitPerfect = true, index = 0),
            UsbMixerSelector.MixerCandidate(96000, AudioFormat.ENCODING_PCM_32BIT, 12, bitPerfect = true, index = 1),
            UsbMixerSelector.MixerCandidate(96000, AudioFormat.ENCODING_PCM_FLOAT, 12, bitPerfect = false, index = 2),
        )
        val picked = UsbMixerSelector.selectBest(96000, AudioFormat.ENCODING_PCM_FLOAT, 12, candidates)
        assertNotNull(picked)
        assertTrue(picked!!.bitPerfect)
        assertEquals(AudioFormat.ENCODING_PCM_32BIT, picked.encoding)
    }

    @Test
    fun picksDefaultMixerWhenHalExposesNoBitPerfectBehavior() {
        val candidates = listOf(
            UsbMixerSelector.MixerCandidate(48000, AudioFormat.ENCODING_PCM_32BIT, 12, bitPerfect = false, index = 0),
            UsbMixerSelector.MixerCandidate(96000, AudioFormat.ENCODING_PCM_16BIT, 12, bitPerfect = false, index = 1),
            UsbMixerSelector.MixerCandidate(96000, AudioFormat.ENCODING_PCM_32BIT, 12, bitPerfect = false, index = 2),
        )
        val picked = UsbMixerSelector.selectBest(96000, AudioFormat.ENCODING_PCM_FLOAT, 12, candidates)
        assertNotNull(picked)
        assertEquals(96000, picked!!.sampleRate)
        assertEquals(AudioFormat.ENCODING_PCM_32BIT, picked.encoding)
    }

    @Test
    fun prefers32BitHeadroomUnderDefaultMixerEvenWhenTrackRequests16Bit() {
        val candidates = listOf(
            UsbMixerSelector.MixerCandidate(44100, AudioFormat.ENCODING_PCM_16BIT, 12, bitPerfect = false, index = 0),
            UsbMixerSelector.MixerCandidate(44100, AudioFormat.ENCODING_PCM_24BIT_PACKED, 12, bitPerfect = false, index = 1),
            UsbMixerSelector.MixerCandidate(44100, AudioFormat.ENCODING_PCM_32BIT, 12, bitPerfect = false, index = 2),
        )
        val picked = UsbMixerSelector.selectBest(44100, AudioFormat.ENCODING_PCM_16BIT, 12, candidates)
        assertNotNull(picked)
        assertEquals(AudioFormat.ENCODING_PCM_32BIT, picked!!.encoding)
    }

    @Test
    fun refusesToDownsampleWhenOnlyLowerRateExists() {
        val candidates = listOf(
            UsbMixerSelector.MixerCandidate(48000, AudioFormat.ENCODING_PCM_32BIT, 12, bitPerfect = true, index = 0),
        )
        assertNull(UsbMixerSelector.selectBest(96000, AudioFormat.ENCODING_PCM_24BIT_PACKED, 12, candidates))
        assertNull(UsbMixerSelector.selectBest(96000, AudioFormat.ENCODING_PCM_24BIT_PACKED, 12, emptyList()))
    }

    @Test
    fun formatCleanCardShowsLiveRateAndDongleSpecsWithoutDebugClutter() {
        val snap = UsbDiagnosticSnapshot(
            apiSupported = true,
            enabled = true,
            devicePresent = true,
            usbDacPresent = true,
            wiredHeadsetPresent = false,
            deviceName = "USB-Audio - TP55",
            deviceTypeLabel = "USB_HEADSET",
            deviceAddress = "card=1;device=0",
            supportedRates = listOf(44100, 96000, 192000),
            supportedEncodings = listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_32BIT),
            maxDeviceRateHz = 192000,
            maxDeviceBitDepthLabel = "32bit / Float32",
            totalMixers = 60,
            bitPerfectCount = 0,
            mixerSummary = "60 项（Bit-Perfect 0 项）",
            lastTrackRateHz = 96000,
            lastTrackEncoding = AudioFormat.ENCODING_PCM_24BIT_PACKED,
            lastTrackRequest = "96000Hz/24bit",
            lastAppliedResult = "MIXER_DEFAULT 96000Hz/32bit",
            routedDescription = "",
            activeMixerDescription = "",
            channelStateLabel = "独立 Hi-Fi 通道已激活（已绕过系统音效，32bit 余裕）",
            logFilePath = "/storage/emulated/0/usb_bitperfect_log.txt",
        )
        val card = snap.formatCleanCard()
        assertTrue(card.contains("USB-Audio - TP55"))
        assertTrue(card.contains("192 kHz"))
        assertTrue(card.contains("96 kHz / 24bit"))
        assertTrue(card.contains("独立 Hi-Fi 通道"))
        assertFalse(card.contains("card=1"))
        assertFalse(card.contains("/storage/"))
        assertFalse(card.contains("API "))
    }
}
