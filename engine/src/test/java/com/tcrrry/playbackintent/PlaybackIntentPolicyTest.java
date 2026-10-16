package com.tcrrry.playbackintent;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackIntentPolicyTest {
    private boolean clear(int insertion, int repeat, boolean dynamic, boolean capable) {
        return PlaybackIntentPolicy.shouldClear(insertion, repeat, repeat, 0, 0, true, false,
                dynamic, capable, true, true);
    }
    @Test public void ordinaryPlayAndShuffleRequestsClearOnlyRepeatOne() {
        for (int insertion : new int[]{1, 5}) {
            assertTrue(clear(insertion, 1, false, true));
            assertFalse(clear(insertion, 0, false, true));
            assertFalse(clear(insertion, 2, false, true));
        }
    }
    @Test public void stationClearsInheritedRepeatAllAndOne() {
        assertTrue(clear(7, 1, true, false)); assertTrue(clear(7, 2, true, false));
        assertFalse(clear(7, 0, true, false));
    }
    @Test public void newDynamicRadioCanUseNormalReplacementInsertion() {
        assertTrue(clear(1, 2, true, false)); assertTrue(clear(5, 1, true, false));
    }
    @Test public void capableDynamicQueueDoesNotLoseRepeatAll() { assertFalse(clear(1, 2, true, true)); }
    @Test public void editsAutoplayAndKeepAndReplaceAreNeverNewPlayIntents() {
        for (int insertion : new int[]{-1,0,2,3,4,6,8,9,10,11,Integer.MAX_VALUE})
            for (int repeat : new int[]{1,2}) assertFalse(clear(insertion, repeat, true, false));
    }
    @Test public void failedQueueAndRemoteMirroringDoNothing() {
        assertFalse(PlaybackIntentPolicy.shouldClear(1,1,1,0,0,false,false,false,true,true,true));
        assertFalse(PlaybackIntentPolicy.shouldClear(1,1,1,0,0,true,true,false,true,true,true));
    }
    @Test public void laterModeCommandEvenWithSameValueWins() {
        assertFalse(PlaybackIntentPolicy.shouldClear(1,1,1,0,1,true,false,false,true,true,true));
    }
    @Test public void nativeModeChangesArePreserved() {
        assertFalse(PlaybackIntentPolicy.shouldClear(1,1,2,0,0,true,false,false,true,true,true));
        assertFalse(PlaybackIntentPolicy.shouldClear(1,1,0,0,0,true,false,false,true,true,true));
    }
    @Test public void unknownRepeatValuesAreNeverRewritten() {
        assertFalse(clear(7,-1,true,false)); assertFalse(clear(7,3,true,false));
    }
    @Test public void switchesAreIndependent() {
        assertFalse(PlaybackIntentPolicy.shouldClear(1,1,1,0,0,true,false,false,true,false,true));
        assertFalse(PlaybackIntentPolicy.shouldClear(7,1,1,0,0,true,false,true,false,true,false));
    }
    @Test public void exactHostVersionsOnly() {
        assertTrue(PlaybackIntentPolicy.supportsHost("com.apple.android.music","7.0.0-beta",1609));
        assertTrue(PlaybackIntentPolicy.supportsHost("com.tcrrry.ampplyrics.coexist","7.0.0-beta",1609));
        assertFalse(PlaybackIntentPolicy.supportsHost("com.apple.android.music","7.0.0-beta",1606));
        assertFalse(PlaybackIntentPolicy.supportsHost("com.apple.android.music","7.0.0-beta",1610));
        assertFalse(PlaybackIntentPolicy.supportsHost("com.apple.android.music","7.0.0",1609));
        assertFalse(PlaybackIntentPolicy.supportsHost("other.package","7.0.0-beta",1609));
    }
}
