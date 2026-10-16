package com.tcrrry.playbackintent;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackIntentCoordinatorTest {
    private static final class Manager { int repeat = 1; Object provider; int clears; }
    private static final class Provider { boolean dynamic, mirrored, failed; }
    private final Manager manager = new Manager();
    private final Provider provider = new Provider();
    private final PlaybackIntentCoordinator coordinator = new PlaybackIntentCoordinator(new PlaybackIntentCoordinator.QueueAccess() {
        public int repeat(Object m) { return ((Manager)m).repeat; }
        public boolean committed(Object m, Object p) { return ((Manager)m).provider == p && !((Provider)p).failed; }
        public boolean mirrored(Object p) { return ((Provider)p).mirrored; }
        public boolean dynamic(Object p) { return ((Provider)p).dynamic; }
        public boolean canRepeat(Object m) { return !((Provider)((Manager)m).provider).dynamic; }
        public void clearAtNativePublication(Object m) { ((Manager)m).repeat = 0; ((Manager)m).clears++; }
    });
    @Before public void start() { coordinator.start(); }
    private void request(int type) throws Exception { coordinator.requested(manager, provider, type); }
    private boolean commit(int type) throws Exception {
        coordinator.beginCommit(manager, provider, type);
        try { return coordinator.beforeNativePublication(manager); }
        finally { coordinator.endCommit(); }
    }
    @Test public void clearsAtPublicationNotAtRequest() throws Exception {
        request(1); assertEquals(1,manager.repeat);
        manager.provider=provider; assertTrue(commit(1)); assertEquals(0,manager.repeat);
    }
    @Test public void providerNotInstalledDoesNotChangeMode() throws Exception { request(1); assertFalse(commit(1)); }
    @Test public void emptyOrRejectedProviderDoesNotChangeMode() throws Exception {
        request(1); manager.provider=provider; provider.failed=true; assertFalse(commit(1));
    }
    @Test public void exceptionDuringPreparationCancelsRequest() throws Exception {
        request(1); coordinator.requestFailed(provider); manager.provider=provider; assertFalse(commit(1));
    }
    @Test public void manualChoiceWhileLoadingIsNotOverridden() throws Exception {
        request(1); coordinator.modeCommand(manager); manager.provider=provider; assertFalse(commit(1));
    }
    @Test public void manualOffThenOneWhileLoadingIsNotOverridden() throws Exception {
        request(1); coordinator.modeCommand(manager); manager.repeat=0;
        coordinator.modeCommand(manager); manager.repeat=1; manager.provider=provider; assertFalse(commit(1));
    }
    @Test public void wrongManagerCannotConsumeRequest() throws Exception {
        request(1); Manager other=new Manager(); other.provider=provider;
        coordinator.beginCommit(other,provider,1);
        try { assertFalse(coordinator.beforeNativePublication(other)); } finally { coordinator.endCommit(); }
        assertEquals(1,manager.repeat); assertEquals(1,other.repeat);
    }
    @Test public void mismatchingInsertionCannotReuseRequest() throws Exception {
        request(1); manager.provider=provider; assertFalse(commit(7));
    }
    @Test public void unrelatedProviderDoesNotConsumeOriginalRequest() throws Exception {
        request(1); Provider other=new Provider(); manager.provider=other;
        coordinator.beginCommit(manager,other,1);
        try { assertFalse(coordinator.beforeNativePublication(manager)); } finally { coordinator.endCommit(); }
        manager.provider=provider; assertTrue(commit(1));
    }
    @Test public void outsideCommitAndNormalSongChangeDoNothing() throws Exception {
        request(1); manager.provider=provider; assertFalse(coordinator.beforeNativePublication(manager));
    }
    @Test public void duplicatePublicationCorrectsOnlyOnce() throws Exception {
        request(1); manager.provider=provider; coordinator.beginCommit(manager,provider,1);
        try {
            assertTrue(coordinator.beforeNativePublication(manager)); manager.repeat=1;
            assertFalse(coordinator.beforeNativePublication(manager)); assertEquals(1,manager.clears);
        } finally { coordinator.endCommit(); }
    }
    @Test public void consumedRequestDoesNotAffectSubsequentReplay() throws Exception {
        request(1); manager.provider=provider; assertTrue(commit(1)); manager.repeat=1;
        assertFalse(commit(1)); assertEquals(1,manager.repeat);
    }
    @Test public void automaticAppendIsUntouched() throws Exception {
        request(2); manager.provider=provider; provider.dynamic=true; assertFalse(commit(2));
    }
    @Test public void queueRestorationWithoutRequestIsUntouched() throws Exception {
        manager.provider=provider; assertFalse(commit(1));
    }
    @Test public void repeatAllOnNormalPlaylistSurvives() throws Exception {
        manager.repeat=2; request(1); manager.provider=provider; assertFalse(commit(1)); assertEquals(2,manager.repeat);
    }
    @Test public void stationRepeatAllIsClearedWithoutEnablingItsControls() throws Exception {
        manager.repeat=2; provider.dynamic=true; request(7); manager.provider=provider;
        assertTrue(commit(7)); assertTrue(provider.dynamic); assertEquals(0,manager.repeat);
    }
    @Test public void remotePlaybackIsUntouched() throws Exception {
        request(1); provider.mirrored=true; manager.provider=provider; assertFalse(commit(1));
    }
    @Test public void stoppedPluginCannotMutatePendingOrFutureQueues() throws Exception {
        request(1); manager.provider=provider; coordinator.stop(); assertFalse(commit(1));
        request(1); assertFalse(commit(1));
    }
    @Test public void stoppingInsideCommitPreventsMutation() throws Exception {
        request(1); manager.provider=provider; coordinator.beginCommit(manager,provider,1); coordinator.stop();
        try { assertFalse(coordinator.beforeNativePublication(manager)); } finally { coordinator.endCommit(); }
    }
    @Test public void nestedUnrelatedCommitDoesNotBorrowOuterIntent() throws Exception {
        request(1); manager.provider=provider; coordinator.beginCommit(manager,provider,1);
        try {
            coordinator.beginCommit(manager,new Provider(),2);
            try { assertFalse(coordinator.beforeNativePublication(manager)); } finally { coordinator.endCommit(); }
            assertTrue(coordinator.beforeNativePublication(manager));
        } finally { coordinator.endCommit(); }
        assertFalse(coordinator.beforeNativePublication(manager));
    }
    @Test public void workerThreadCannotBorrowAnotherThreadsFrame() throws Exception {
        request(1); manager.provider=provider; coordinator.beginCommit(manager,provider,1);
        final Throwable[] error=new Throwable[1];
        Thread thread=new Thread(() -> { try { assertFalse(coordinator.beforeNativePublication(manager)); } catch(Throwable e) { error[0]=e; } });
        thread.start(); thread.join();
        try { assertNull(error[0]); assertTrue(coordinator.beforeNativePublication(manager)); }
        finally { coordinator.endCommit(); }
    }
    @Test public void disabledOptionInvalidatesAlreadyPendingRequest() throws Exception {
        request(1); manager.provider=provider; coordinator.options(false,false); assertFalse(commit(1));
    }
    @Test public void offModeAtRequestDoesNotArmLaterModeChange() throws Exception {
        manager.repeat=0; request(1); manager.repeat=1; manager.provider=provider; assertFalse(commit(1));
    }
    @Test public void nativeAlreadyClearedModeNeedsNoSecondWrite() throws Exception {
        request(1); manager.repeat=0; manager.provider=provider; assertFalse(commit(1)); assertEquals(0,manager.clears);
    }
    @Test public void asynchronousPreparationCanCommitOnAnotherThread() throws Exception {
        request(1); manager.provider=provider;
        final Throwable[] error=new Throwable[1];
        Thread thread=new Thread(() -> { try { assertTrue(commit(1)); } catch(Throwable e) { error[0]=e; } });
        thread.start(); thread.join(); assertNull(error[0]); assertEquals(0,manager.repeat);
        assertFalse(coordinator.beforeNativePublication(manager));
    }
}
