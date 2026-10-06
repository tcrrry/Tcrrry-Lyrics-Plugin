package com.tcrrry.lyrics.engine.hook;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

/** Mirrors the host's default interface getter and inherited handler/browser API. */
public final class InterfacePlaybackHost extends InterfacePlaybackHostBase {
    public int restarts;
    public long y2(int state) {
        if (state != 3) throw new AssertionError("Paused playback must not restart");
        restarts++;
        return 200L;
    }
    @Override void onLyricsMessage() { y2(getMediaBrowser().getPlaybackState()); }
}

interface MediaPosition { long getCurrentPosition(); }
interface MediaPlayback extends MediaPosition {
    boolean isPlaying();
    int getPlaybackState();
}
interface PlaybackOwner {
    MediaPlayback browser();
    default MediaPlayback getMediaBrowser() { return browser(); }
}
abstract class InterfacePlaybackHostBase implements PlaybackOwner {
    public long position = 210000L;
    public boolean playing = true;
    private final MediaPlayback media = new MediaPlayback() {
        public long getCurrentPosition() { return position; }
        public boolean isPlaying() { return playing; }
        public int getPlaybackState() { return playing ? 3 : 2; }
    };
    private final Handler X = new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message message) {
            if (message.what == 42) onLyricsMessage();
        }
    };
    public MediaPlayback browser() { return media; }
    public Handler handler() { return X; }
    abstract void onLyricsMessage();
}
