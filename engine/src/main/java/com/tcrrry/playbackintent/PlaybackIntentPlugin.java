package com.tcrrry.playbackintent;

import dev.amenhancer.plugin.api.*;
import java.util.ArrayList;
import java.util.List;

public final class PlaybackIntentPlugin extends AmppPlugin {
    private final List<PluginRegistration> registrations = new ArrayList<>();
    private PlaybackIntentCoordinator coordinator;
    private PluginContext runtime;
    private HostContract host;
    private NativeRepeatButtonBridge nativeRepeat;
    private RepeatCommand repeatCommand;
    private final RadioRepeatExperiment radio=new RadioRepeatExperiment();
    private final ThreadLocal<Boolean> readingRadio=new ThreadLocal<>();

    @Override public void onLoad(PluginContext context) throws Exception {
        if (!PlaybackIntentPolicy.supportsHost(context.getHostPackageName(), context.getHostVersionName(), context.getHostVersionCode()))
            throw new PluginUnsupportedException("播放模式测试插件仅适配 Apple Music 7.0.0-beta / 1609");
        runtime = context;
        host = new HostContract(context.getHostClassLoader());
        coordinator = new PlaybackIntentCoordinator(host);
        repeatCommand = new RepeatCommand(host,radio,coordinator);
        nativeRepeat = new NativeRepeatButtonBridge(context,host,radio,repeatCommand);
        registrations.add(context.claimResource("com.tcrrry.playbackintent.queue-modes", true));
        nativeRepeat.register(registrations);
        registrations.add(context.getHooks().observe(host.addItems, new PluginObserver() {
            @Override public void before(PluginObservation call) throws Exception {
                synchronized (call.getReceiver()) {
                    Object[] args = call.getArguments();
                    coordinator.requested(call.getReceiver(), args[0], (Integer) args[1]);
                }
            }
            @Override public void after(PluginObservation call) {
                if (call.getThrowable() != null) coordinator.requestFailed(call.getArguments()[0]);
            }
        }));
        registrations.add(context.getHooks().observe(host.setRepeat, new PluginObserver() {
            @Override public void before(PluginObservation call) {
                synchronized (call.getReceiver()) {
                    coordinator.modeCommand(call.getReceiver()); radio.clear(call.getReceiver());
                }
            }
        }));
        registrations.add(context.getHooks().observe(host.addItemsInternal, new PluginObserver() {
            @Override public void before(PluginObservation call) {
                Object[] args = call.getArguments();
                coordinator.beginCommit(call.getReceiver(), args[0], (Integer) args[1]);
            }
            @Override public void after(PluginObservation call) { coordinator.endCommit(); }
        }));
        registrations.add(context.getHooks().observe(host.publish, new PluginObserver() {
            @Override public void before(PluginObservation call) throws Exception {
                synchronized (call.getReceiver()) {
                    radio.observe(call.getReceiver());
                    if (coordinator.beforeNativePublication(call.getReceiver()))
                        runtime.log("Playback intent: stale repeat cleared at new queue commit", null);
                }
            }
            @Override public void after(PluginObservation call) { nativeRepeat.refresh(); }
        }));
        registrations.add(context.getHooks().observe(host.setCurrentIndex,new PluginObserver() {
            @Override public void after(PluginObservation call) {
                if(call.getThrowable()==null) { radio.observe(call.getReceiver()); nativeRepeat.refresh(); }
            }
        }));
        registrations.add(context.getHooks().hook(host.getRepeat,false,new PluginHook() {
            @Override public void after(PluginCall call) throws Exception {
                if(call.getThrowable()==null && radio.latest()==null) radio.observe(call.getReceiver());
                if(call.getThrowable()!=null || readingRadio.get()!=null || !radio.wantsOverride(call.getReceiver())) return;
                readingRadio.set(true);
                try {
                    synchronized(call.getReceiver()) {
                        int original=(Integer)call.getResult();
                        int effective=radio.effective(call.getReceiver(),original,host.radioSnapshot(call.getReceiver()));
                        if(effective!=original) call.returnResult(effective);
                    }
                } catch(Exception error) {
                    radio.clear(call.getReceiver());
                    runtime.log("Radio experiment stopped after an unavailable snapshot",error);
                } finally { readingRadio.remove(); }
            }
        }));
        context.onClose(this::onStop);
    }
    @Override public void onStart() { coordinator.start(); radio.start(); nativeRepeat.start(); }
    public Object observedQueue() { return radio.latest(); }
    public void resumeObservedQueue(Object manager) throws Exception {
        if(manager!=null && host.getRepeat.getDeclaringClass().isInstance(manager)) {
            radio.observe(manager);
            host.notifyModes(manager);
        }
    }
    @Override public PluginSettingsSession createSettings(android.content.Context context) {
        return new RadioExperimentSettings(context,radio,host,repeatCommand,runtime);
    }
    @Override public void onStop() {
        Object manager=radio.latest();
        radio.stop();
        if(nativeRepeat!=null) nativeRepeat.stop();
        if (coordinator != null) coordinator.stop();
        for (int i = registrations.size() - 1; i >= 0; i--) {
            try { registrations.get(i).close(); }
            catch (Exception error) { if (runtime != null) runtime.log("Playback intent cleanup failed", error); }
        }
        registrations.clear();
        if(host!=null && manager!=null) {
            try { host.notifyModes(manager); }
            catch(Exception error) { if(runtime!=null) runtime.log("Radio restore notification failed",error); }
        }
    }
}
