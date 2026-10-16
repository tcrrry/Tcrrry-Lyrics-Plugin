package com.tcrrry.playbackintent;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.WeakHashMap;

/** Transactions span asynchronous preparation; frames exist only during native queue commit. */
final class PlaybackIntentCoordinator {
    interface QueueAccess {
        int repeat(Object manager) throws Exception;
        boolean committed(Object manager, Object provider) throws Exception;
        boolean mirrored(Object provider) throws Exception;
        boolean dynamic(Object provider) throws Exception;
        boolean canRepeat(Object manager) throws Exception;
        void clearAtNativePublication(Object manager) throws Exception;
    }
    private static final class Request {
        final WeakReference<Object> manager;
        final int insertion, repeat;
        final long revision;
        Request(Object manager, int insertion, int repeat, long revision) {
            this.manager = new WeakReference<>(manager);
            this.insertion = insertion; this.repeat = repeat; this.revision = revision;
        }
    }
    private static final class Frame {
        final WeakReference<Object> manager, provider;
        final Request request;
        boolean corrected;
        Frame(Object manager, Object provider, Request request) {
            this.manager = new WeakReference<>(manager); this.provider = new WeakReference<>(provider); this.request = request;
        }
    }
    private final QueueAccess queue;
    private final Map<Object, Request> requests = new WeakHashMap<>();
    private final Map<Object, Long> revisions = new WeakHashMap<>();
    private final ThreadLocal<ArrayDeque<Frame>> frames = new ThreadLocal<>();
    private boolean active;
    private boolean normalEnabled = true, stationEnabled = true;
    private int corrections;

    PlaybackIntentCoordinator(QueueAccess queue) { this.queue = queue; }
    synchronized void start() { active = true; }
    synchronized void stop() { active = false; requests.clear(); revisions.clear(); }
    synchronized void options(boolean normal, boolean station) {
        normalEnabled = normal; stationEnabled = station; requests.clear();
    }
    synchronized int corrections() { return corrections; }
    private long revision(Object manager) { Long value = revisions.get(manager); return value == null ? 0L : value; }

    // Callers take the manager monitor first, consistently with the native commit path.
    synchronized void requested(Object manager, Object provider, int insertion) throws Exception {
        if (provider == null) return;
        requests.remove(provider);
        if (!active || !PlaybackIntentPolicy.replacesQueue(insertion)) return;
        int repeat = queue.repeat(manager);
        if (repeat != 1 && repeat != 2) return;
        requests.put(provider, new Request(manager, insertion, repeat, revision(manager)));
    }
    synchronized void requestFailed(Object provider) { requests.remove(provider); }
    synchronized void modeCommand(Object manager) {
        if (active) revisions.put(manager, revision(manager) + 1L);
    }
    synchronized void beginCommit(Object manager, Object provider, int insertion) {
        Request request = requests.remove(provider);
        if (request != null && (request.manager.get() != manager || request.insertion != insertion)) request = null;
        ArrayDeque<Frame> stack = frames.get();
        if (stack == null) { stack = new ArrayDeque<>(); frames.set(stack); }
        stack.push(new Frame(manager, provider, active ? request : null));
    }
    void endCommit() {
        ArrayDeque<Frame> stack = frames.get();
        if (stack == null) return;
        if (!stack.isEmpty()) stack.pop();
        if (stack.isEmpty()) frames.remove();
    }
    synchronized boolean beforeNativePublication(Object manager) throws Exception {
        ArrayDeque<Frame> stack = frames.get();
        if (!active || stack == null || stack.isEmpty()) return false;
        Frame frame = stack.peek();
        Request request = frame.request;
        Object provider = frame.provider.get();
        if (frame.manager.get() != manager || provider == null || request == null || frame.corrected) return false;
        if (!queue.committed(manager, provider)) return false;
        if (!PlaybackIntentPolicy.shouldClear(request.insertion, request.repeat, queue.repeat(manager),
                request.revision, revision(manager), true,
                queue.mirrored(provider), queue.dynamic(provider), queue.canRepeat(manager),
                normalEnabled, stationEnabled)) return false;
        queue.clearAtNativePublication(manager);
        frame.corrected = true;
        corrections++;
        return true;
    }
}
