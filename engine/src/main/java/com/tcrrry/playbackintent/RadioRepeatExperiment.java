package com.tcrrry.playbackintent;

import java.lang.ref.WeakReference;
import java.util.Objects;

/** Memory-only effective mode; never writes the host's stored repeat mode. */
final class RadioRepeatExperiment {
    static final class Snapshot {
        final Object manager, provider;
        final String queue, title;
        final long item;
        final boolean eligible, radio;
        Snapshot(Object manager, Object provider, String queue, long item, String title, boolean radio,
                boolean mirrored, boolean live, boolean episode, boolean video, boolean continuous,
                boolean playable, long duration) {
            this(manager,provider,queue,item,title,radio,mirrored,live,episode,video,continuous,playable,duration,false);
        }
        Snapshot(Object manager, Object provider, String queue, long item, String title, boolean radio,
                boolean mirrored, boolean live, boolean episode, boolean video, boolean continuous,
                boolean playable, long duration, boolean verifiedAutoplay) {
            this.manager=manager; this.provider=provider; this.queue=queue; this.item=item; this.title=title;
            this.radio=radio;
            eligible=radio && manager!=null && provider!=null && queue!=null && item>=0 && !mirrored
                    && !live && !episode && !video && (!continuous || verifiedAutoplay) && playable && duration>0;
        }
        static Snapshot unavailable(Object manager) {
            return new Snapshot(manager,null,null,-1,"",false,false,false,false,false,false,false,0);
        }
        boolean sameQueue(Snapshot other) {
            return other!=null && manager==other.manager && provider==other.provider && Objects.equals(queue,other.queue);
        }
    }
    private WeakReference<Object> latest=new WeakReference<>(null);
    private WeakReference<Object> owner=new WeakReference<>(null), provider=new WeakReference<>(null);
    private String queue;
    private boolean active;
    synchronized void start() { active=true; }
    synchronized void observe(Object manager) {
        if(!active) return;
        Object selected=owner.get();
        if(selected!=null && selected!=manager) clear(selected);
        latest=new WeakReference<>(manager);
    }
    synchronized Object latest() { return active ? latest.get() : null; }
    synchronized boolean wantsOverride(Object manager) { return active && owner.get()==manager && queue!=null; }
    synchronized boolean enable(Snapshot clicked, Snapshot current) {
        if (!active || !clicked.eligible || !current.eligible || !clicked.sameQueue(current) || clicked.item!=current.item) return false;
        owner=new WeakReference<>(current.manager); provider=new WeakReference<>(current.provider); queue=current.queue;
        return true;
    }
    synchronized void clear(Object manager) {
        if(owner.get()==manager) { owner.clear(); provider.clear(); queue=null; }
    }
    synchronized int effective(Object manager, int original, Snapshot current) {
        if(!wantsOverride(manager)) return original;
        if(original!=0) { clear(manager); return original; }
        if(!current.eligible || current.manager!=manager || current.provider!=provider.get() || !Objects.equals(queue,current.queue)) {
            clear(manager); return original;
        }
        return 1;
    }
    synchronized void stop() { active=false; latest.clear(); owner.clear(); provider.clear(); queue=null; }
}
