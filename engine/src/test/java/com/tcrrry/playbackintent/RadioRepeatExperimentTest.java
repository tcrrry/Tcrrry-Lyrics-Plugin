package com.tcrrry.playbackintent;

import org.junit.Test;
import static org.junit.Assert.*;

public class RadioRepeatExperimentTest {
    private final RadioRepeatExperiment engine=new RadioRepeatExperiment();
    private final Object manager=new Object(), provider=new Object();
    private RadioRepeatExperiment.Snapshot snapshot(String queue,long item,boolean radio,boolean mirrored,
            boolean live,boolean episode,boolean video,boolean continuous,boolean playable,long duration) {
        return new RadioRepeatExperiment.Snapshot(manager,provider,queue,item,"song",radio,mirrored,live,episode,video,continuous,playable,duration);
    }
    private RadioRepeatExperiment.Snapshot song() { return snapshot("queue-a",42,true,false,false,false,false,false,true,180000); }
    private void enable() { engine.start(); assertTrue(engine.enable(song(),song())); }
    @Test public void defaultNeverOverridesMode() { engine.start(); assertEquals(0,engine.effective(manager,0,song())); }
    @Test public void explicitEnableOverridesOnlyEffectiveMode() {
        enable(); assertEquals(1,engine.effective(manager,0,song())); assertTrue(engine.wantsOverride(manager));
    }
    @Test public void liveRadioIsRefused() {
        engine.start(); RadioRepeatExperiment.Snapshot live=snapshot("queue-a",42,true,false,true,false,false,false,true,100);
        assertFalse(engine.enable(live,live)); assertEquals(0,engine.effective(manager,0,live));
    }
    @Test public void episodesVideosAutoplayAndRemoteAreRefused() {
        engine.start();
        RadioRepeatExperiment.Snapshot[] excluded={snapshot("a",1,true,true,false,false,false,false,true,100),
                snapshot("a",1,true,false,false,true,false,false,true,100),
                snapshot("a",1,true,false,false,false,true,false,true,100),
                snapshot("a",1,true,false,false,false,false,true,true,100)};
        for(RadioRepeatExperiment.Snapshot s:excluded) assertFalse(engine.enable(s,s));
    }
    @Test public void unknownDurationAndUnplayableContentAreRefused() {
        engine.start();
        for(long duration:new long[]{0,-1}) {
            RadioRepeatExperiment.Snapshot s=snapshot("a",1,true,false,false,false,false,false,true,duration);
            assertFalse(engine.enable(s,s));
        }
        RadioRepeatExperiment.Snapshot s=snapshot("a",1,true,false,false,false,false,false,false,100);
        assertFalse(engine.enable(s,s));
    }
    @Test public void ordinaryPlaylistIsNeverModified() {
        enable(); RadioRepeatExperiment.Snapshot s=snapshot("b",1,false,false,false,false,false,false,true,100);
        assertEquals(2,engine.effective(manager,2,s)); assertFalse(engine.wantsOverride(manager));
    }
    @Test public void changedQueueDuringClickIsRefused() {
        engine.start(); assertFalse(engine.enable(song(),snapshot("queue-b",42,true,false,false,false,false,false,true,100)));
    }
    @Test public void changedSongDuringClickIsRefused() {
        engine.start(); assertFalse(engine.enable(song(),snapshot("queue-a",43,true,false,false,false,false,false,true,100)));
    }
    @Test public void newStationAutomaticallyEndsOverride() {
        enable(); assertEquals(0,engine.effective(manager,0,snapshot("queue-b",42,true,false,false,false,false,false,true,100)));
        assertFalse(engine.wantsOverride(manager));
    }
    @Test public void sameQueueManualNextSongMayRepeatNewSongLikeNativeMode() {
        enable(); assertEquals(1,engine.effective(manager,0,snapshot("queue-a",43,true,false,false,false,false,false,true,100)));
    }
    @Test public void transitioningToLiveContentEndsOverride() {
        enable(); assertEquals(0,engine.effective(manager,0,snapshot("queue-a",43,true,false,true,false,false,false,true,100)));
        assertFalse(engine.wantsOverride(manager));
    }
    @Test public void restoringSequenceStopsOverride() {
        enable(); engine.clear(manager); assertEquals(0,engine.effective(manager,0,song()));
    }
    @Test public void stopAndRestartDoNotRememberExperiment() {
        enable(); engine.stop(); engine.start(); assertEquals(0,engine.effective(manager,0,song())); assertNull(engine.latest());
    }
    @Test public void unrelatedManagerIsUntouched() {
        enable(); assertEquals(0,engine.effective(new Object(),0,song())); assertTrue(engine.wantsOverride(manager));
    }
    @Test public void nativeOrOtherPluginModeWins() {
        enable(); assertEquals(2,engine.effective(manager,2,song())); assertFalse(engine.wantsOverride(manager));
    }
    @Test public void missingOrReplacedProviderIsRefused() {
        engine.start(); assertFalse(engine.enable(song(),RadioRepeatExperiment.Snapshot.unavailable(manager)));
        RadioRepeatExperiment.Snapshot other=new RadioRepeatExperiment.Snapshot(manager,new Object(),"queue-a",42,"song",true,false,false,false,false,false,true,100);
        assertFalse(engine.enable(song(),other));
    }
    @Test public void stoppedPluginCannotEnableAndDoesNotRetainLatestManager() {
        engine.start(); engine.observe(manager); assertSame(manager,engine.latest());
        engine.stop(); engine.observe(manager); assertNull(engine.latest()); assertFalse(engine.enable(song(),song()));
    }
    @Test public void anotherPlaybackManagerPublicationEndsOldOverride() {
        enable(); Object other=new Object(); engine.observe(other);
        assertFalse(engine.wantsOverride(manager)); assertSame(other,engine.latest());
    }
    @Test public void verifiedAutoplayAllowsFiniteSongs() {
        engine.start();
        RadioRepeatExperiment.Snapshot s=new RadioRepeatExperiment.Snapshot(manager,provider,"auto",42,"song",
                true,false,false,false,false,true,true,180000,true);
        assertTrue(engine.enable(s,s)); assertEquals(1,engine.effective(manager,0,s));
        engine.clear(manager); assertEquals(0,engine.effective(manager,0,s));
    }
    @Test public void autoplayStillRejectsLiveEpisodesVideosAndRemote() {
        engine.start();
        for(int rejected=0;rejected<4;rejected++) {
            RadioRepeatExperiment.Snapshot s=new RadioRepeatExperiment.Snapshot(manager,provider,"auto",42,"song",
                    true,rejected==0,rejected==1,rejected==2,rejected==3,true,true,180000,true);
            assertFalse(engine.enable(s,s));
        }
    }
    @Test public void autoplayProviderChangeEndsRepeatEvenWithSameQueueUuid() {
        engine.start();
        RadioRepeatExperiment.Snapshot s=new RadioRepeatExperiment.Snapshot(manager,provider,"auto",42,"song",
                true,false,false,false,false,true,true,180000,true);
        assertTrue(engine.enable(s,s));
        RadioRepeatExperiment.Snapshot next=new RadioRepeatExperiment.Snapshot(manager,new Object(),"auto",43,"song",
                true,false,false,false,false,true,true,180000,true);
        assertEquals(0,engine.effective(manager,0,next)); assertFalse(engine.wantsOverride(manager));
    }
}
