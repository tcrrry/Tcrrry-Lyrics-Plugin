package com.tcrrry.lyrics.engine.hook

import com.tcrrry.lyrics.sources.DirectLyricsRepository.Result
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [35])
class TranslationOutcomeCacheTest {
    @org.junit.Before fun attachPlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.attach() }
    @org.junit.After fun closePlugin() { com.tcrrry.lyrics.plugin.TestPluginContext.current.close() }
    @Test fun failedResultExpiresInsteadOfBlockingFutureTranslationForever() {
        var time = 100L
        val cache = TranslationOutcomeCache { time }
        val failure = DesktopLyricsSupplement.Outcome(Result(), "模型未下载", retryable = true)
        cache["1|QQ音乐|offline"] = failure
        assertSame(failure, cache["1|QQ音乐|offline"])
        time += 5000
        assertNull(cache["1|QQ音乐|offline"])
    }
    @Test fun explicitRefreshClearsOnlyTheCurrentSongsTranslationResult() {
        val cache = TranslationOutcomeCache { 100L }
        val failure = DesktopLyricsSupplement.Outcome(Result(), "模型未下载", retryable = true)
        cache["1|QQ音乐|offline"] = failure
        cache["2|QQ音乐|offline"] = failure
        cache.invalidate(1)
        assertNull(cache["1|QQ音乐|offline"])
        assertSame(failure, cache["2|QQ音乐|offline"])
    }
    @Test fun completeTranslationSurvivesRetryBackoffAndIsReused() {
        var time = 0L
        val cache = TranslationOutcomeCache { time }
        val translated = DesktopLyricsSupplement.Outcome(Result(translatedLyrics = "[00:01.000]你好"), "已补译")
        cache["1|QQ音乐|offline"] = translated
        time += 3600000
        assertSame(translated, cache["1|QQ音乐|offline"])
    }
    @Test fun incompleteDiskLyricsDoNotPreventRetryButCompletedAndUnrelatedLyricsRemainCached() {
        assertFalse(TranslationOutcomeCache.canReuse(TranslationOutcomeCache.marker(false) + "<tt/>", true))
        assertTrue(TranslationOutcomeCache.canReuse(TranslationOutcomeCache.marker(false) + "<tt/>", false))
        assertTrue(TranslationOutcomeCache.canReuse(TranslationOutcomeCache.marker(true) + "<tt/>", true))
        assertTrue(TranslationOutcomeCache.canReuse("<tt/>", true))
    }
}
