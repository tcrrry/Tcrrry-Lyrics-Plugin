package com.tcrrry.playbackintent;

/** Shared command path for native controls and the optional settings switch. */
final class RepeatCommand {
    private final HostContract host;
    private final RadioRepeatExperiment radio;
    private final PlaybackIntentCoordinator coordinator;
    RepeatCommand(HostContract host, RadioRepeatExperiment radio, PlaybackIntentCoordinator coordinator) {
        this.host=host; this.radio=radio; this.coordinator=coordinator;
    }
    boolean change(RadioRepeatExperiment.Snapshot clicked, Boolean enabled) throws Exception {
        Object manager=clicked.manager;
        if(manager==null || manager!=radio.latest()) return false;
        synchronized(manager) {
            RadioRepeatExperiment.Snapshot current=host.radioSnapshot(manager);
            if(!clicked.eligible || !current.eligible || !clicked.sameQueue(current) || clicked.item!=current.item
                    || host.repeat(manager)!=0) return false;
            boolean selected=radio.effective(manager,0,current)==1;
            boolean enable=enabled==null ? !selected : enabled;
            if (enable && host.shuffle(manager) != 0) {
                if (!host.canShuffle(manager)) return false;
                // Reorder the native queue on its own worker, then recheck the item before enabling repeat.
                host.postQueueWork(manager, () -> enableAfterShuffle(clicked));
                return true;
            }
            return apply(clicked, current, enable);
        }
    }

    private void enableAfterShuffle(RadioRepeatExperiment.Snapshot clicked) {
        Object manager = clicked.manager;
        synchronized (manager) {
            if (manager != radio.latest()) return;
            try {
                RadioRepeatExperiment.Snapshot current = host.radioSnapshot(manager);
                if (!current.eligible || !clicked.sameQueue(current) || clicked.item != current.item
                        || host.repeat(manager) != 0) return;
                if (host.shuffle(manager) != 0 && host.canShuffle(manager)) {
                    host.applyShuffle.invoke(manager, 0, true);
                }
                if (host.shuffle(manager) == 0) apply(clicked, host.radioSnapshot(manager), true);
            } catch (Exception error) {
                android.util.Log.w("TcrrryPlayback", "Experimental repeat could not disable shuffle", error);
            }
        }
    }

    private boolean apply(RadioRepeatExperiment.Snapshot clicked, RadioRepeatExperiment.Snapshot current,
            boolean enable) throws Exception {
        if (enable && !radio.enable(clicked, current)) return false;
        if (!enable) radio.clear(clicked.manager);
        coordinator.modeCommand(clicked.manager);
        host.notifyModes(clicked.manager);
        return true;
    }
}
