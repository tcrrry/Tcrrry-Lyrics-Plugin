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
            if(enable && !radio.enable(clicked,current)) return false;
            if(!enable) radio.clear(manager);
            coordinator.modeCommand(manager);
            host.notifyModes(manager);
            return true;
        }
    }
}
