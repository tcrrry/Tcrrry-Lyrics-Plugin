package com.tcrrry.lyrics.offline
import android.os.Bundle
import android.net.Uri
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class TranslationProviderTest {
    private fun provider()=Robolectric.buildContentProvider(TranslationProvider::class.java).create().get()
    @Test fun statusDoesNotLoadOrDownloadModels() { assertEquals(1,provider().call("status",null,null).getInt("protocol")) }
    @Test fun unsupportedMethodsDoNotEnterTranslation() { assertTrue(provider().call("delete-model",null,null).containsKey("error")) }
    @Test fun missingLanguageAndOversizedInputFailWithoutModelAccess() {
        assertTrue(provider().call("translate",null,Bundle()).containsKey("error"))
        val input=Bundle().apply { putString("language","ja");putString("text","あ".repeat(4001)) }
        assertEquals("无效文本",provider().call("translate",null,input).getString("error"))
    }
    @Test fun malformedLanguageAndEmptyInputAreRejected() {
        val input=Bundle().apply { putString("language","../zh");putString("text","hello") }
        assertTrue(provider().call("translate",null,input).containsKey("error"))
        input.putString("language","en");input.putString("text","")
        assertTrue(provider().call("translate",null,input).containsKey("error"))
    }
    @Test fun providerDoesNotOfferDatabaseOrFileOperations() {
        val service=provider();val uri=Uri.parse("content://com.tcrrry.lyrics.offline.models/secret")
        assertNull(service.query(uri,null,null,null,null));assertNull(service.insert(uri,null));assertEquals(0,service.delete(uri,null,null));assertEquals(0,service.update(uri,null,null,null));assertNull(service.getType(uri))
    }
}
