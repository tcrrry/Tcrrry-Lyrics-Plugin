package com.tcrrry.playlisttools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CoverStoreLogicTest {

    @Test
    public void rewritesClearingRecipe01To00WhenLocalArtworkPresent() {
        String input = "{\"version\":\"0.1\"}";
        String fileUrl = "file:///data/user/0/com.apple.android.music/cache/temp_playlist_image_upload.png";
        assertEquals("{\"version\":\"0.0\"}", CoverStore.rewriteRecipeIfCustomCoverPresent(input, fileUrl));
    }

    @Test
    public void doesNotTouchRecipeWhenGeneratedExpressionIdIsPresent() {
        String generated = "{\"version\":\"1.0\",\"expressionId\":4,\"colorInfo\":{\"background\":\"#112233\"}}";
        assertEquals(generated, CoverStore.rewriteRecipeIfCustomCoverPresent(generated, "file:///tmp/x.jpg"));

        String edgeWithId = "{\"version\":\"0.1\",\"expressionId\":2}";
        assertEquals(edgeWithId, CoverStore.rewriteRecipeIfCustomCoverPresent(edgeWithId, "file:///tmp/x.jpg"));
    }

    @Test
    public void doesNotTouchRecipe01WithoutLocalCustomArtworkUrl() {
        String clearing = "{\"version\":\"0.1\"}";
        assertEquals(clearing, CoverStore.rewriteRecipeIfCustomCoverPresent(clearing, null));
        assertEquals(clearing, CoverStore.rewriteRecipeIfCustomCoverPresent(clearing, ""));
        assertEquals(clearing, CoverStore.rewriteRecipeIfCustomCoverPresent(clearing, "https://is1-ssl.mzstatic.com/image/thumb/foo.jpg"));
    }
}
