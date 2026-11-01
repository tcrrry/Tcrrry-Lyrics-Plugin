package com.tcrrry.playbackintent;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackIntentClickGateTest {
    @Test public void onlyCollectionButtonsAndHeaderPlayAreEligible() {
        assertTrue(PlaybackIntentClickGate.isTarget("header_play_button", false));
        for (String name : new String[]{"button_play", "button_play_knockout", "button_play_uber", "button_shuffle", "button_shuffle_knockout", "button_shuffle_uber"}) {
            assertTrue(PlaybackIntentClickGate.isTarget(name, true));
            assertFalse(PlaybackIntentClickGate.isTarget(name, false));
        }
        assertFalse(PlaybackIntentClickGate.isTarget("item_play_view", true));
        assertFalse(PlaybackIntentClickGate.isTarget("mini_player_play_btn", false));
    }
    @Test public void aSongClickRevokesThePendingCollectionButton() {
        long[] now = {100}; PlaybackIntentClickGate gate = new PlaybackIntentClickGate(() -> now[0], 3000);
        gate.click(true); gate.click(false); assertFalse(gate.consume());
    }
    @Test public void validClickIsSingleUseAndExpires() {
        long[] now = {100}; PlaybackIntentClickGate gate = new PlaybackIntentClickGate(() -> now[0], 3000);
        gate.click(true); assertTrue(gate.consume()); assertFalse(gate.consume());
        gate.click(true); now[0] = 3101; assertFalse(gate.consume());
    }
    @Test public void failedOldClickCannotCancelNewerClick() {
        PlaybackIntentClickGate gate = new PlaybackIntentClickGate(() -> 1, 3000);
        long old = gate.click(true); gate.click(true); gate.cancel(old); assertTrue(gate.consume());
        long failed = gate.click(true); gate.cancel(failed); assertFalse(gate.consume());
    }
}
