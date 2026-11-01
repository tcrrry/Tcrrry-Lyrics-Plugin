package com.tcrrry.playbackintent;

/** One short-lived, single-use authorization for an explicit collection play button. */
final class PlaybackIntentClickGate {
    interface Clock { long now(); }
    private final Clock clock;
    private final long lifetimeMs;
    private long sequence, pendingSequence, expiresAt;
    PlaybackIntentClickGate(Clock clock, long lifetimeMs) { this.clock = clock; this.lifetimeMs = lifetimeMs; }
    synchronized long click(boolean target) {
        long id = ++sequence;
        pendingSequence = target ? id : 0;
        expiresAt = target ? clock.now() + lifetimeMs : 0;
        return id;
    }
    synchronized void cancel(long id) { if (pendingSequence == id) pendingSequence = 0; }
    synchronized boolean consume() {
        boolean valid = pendingSequence != 0 && clock.now() <= expiresAt;
        pendingSequence = 0;
        return valid;
    }
    synchronized void clear() { pendingSequence = 0; }
    static boolean isTarget(String name, boolean inCollectionButtons) {
        if ("header_play_button".equals(name)) return true;
        if (!inCollectionButtons) return false;
        switch (name) {
            case "button_play": case "button_play_knockout": case "button_play_uber":
            case "button_shuffle": case "button_shuffle_knockout": case "button_shuffle_uber": return true;
            default: return false;
        }
    }
}
