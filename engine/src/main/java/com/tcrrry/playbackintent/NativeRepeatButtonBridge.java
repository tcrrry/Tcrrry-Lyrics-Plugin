package com.tcrrry.playbackintent;

import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import dev.amenhancer.plugin.api.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Exact 1609 queue binding; only its repeat control is adapted. */
final class NativeRepeatButtonBridge {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<Object,RadioRepeatExperiment.Snapshot> bindings=new WeakHashMap<>();
    private final Field repeatBinding, root;
    private final Method render, click, invalidate, setMask;
    private final HostContract host;
    private final RadioRepeatExperiment radio;
    private final RepeatCommand command;
    private final PluginContext runtime;
    private volatile boolean active;
    private final Runnable refreshTask=this::refreshBindings;
    private void refreshBindings() {
        for(Object binding:new ArrayList<>(bindings.keySet())) {
            try { invalidate.invoke(binding); render.invoke(binding); }
            catch(Exception error) { runtimeLog(error); }
        }
        if(!active) bindings.clear();
    }
    NativeRepeatButtonBridge(PluginContext runtime,HostContract host,RadioRepeatExperiment radio,
            RepeatCommand command) throws Exception {
        this.runtime=runtime; this.host=host; this.radio=radio; this.command=command;
        ClassLoader loader=runtime.getHostClassLoader();
        Class<?> owner=loader.loadClass("q8.j8"), base=loader.loadClass("q8.i8");
        repeatBinding=base.getDeclaredField("Y");
        if(repeatBinding.getType()!=loader.loadClass("q8.g8") || owner.getSuperclass()!=base)
            throw new IllegalStateException("Unexpected queue binding");
        root=loader.loadClass("androidx.databinding.ViewDataBinding").getDeclaredField("d");
        if(root.getType()!=View.class) throw new IllegalStateException("Unexpected binding root");
        repeatBinding.setAccessible(true); root.setAccessible(true);
        render=owner.getDeclaredMethod("l"); click=owner.getDeclaredMethod("a",View.class,int.class);
        invalidate=owner.getDeclaredMethod("z");
        setMask=loader.loadClass("com.apple.android.music.common.views.MaskingView").getDeclaredMethod("setMask",int.class);
        for(Method method:new Method[]{render,click,invalidate,setMask}) {
            if(method.getReturnType()!=void.class) throw new IllegalStateException("Unexpected native method");
            method.setAccessible(true);
        }
    }
    void register(List<PluginRegistration> registrations) {
        registrations.add(runtime.getHooks().observe(render,new PluginObserver() {
            @Override public void after(PluginObservation call) {
                if(!active || call.getThrowable()!=null || Looper.myLooper()!=Looper.getMainLooper()) return;
                try { decorate(call.getReceiver()); } catch(Exception error) { runtimeLog(error); }
            }
        }));
        registrations.add(runtime.getHooks().hook(click,false,new PluginHook() {
            @Override public void before(PluginCall call) throws Exception {
                if(!active || Looper.myLooper()!=Looper.getMainLooper()) return;
                Object binding=call.getReceiver();
                RadioRepeatExperiment.Snapshot displayed=bindings.get(binding);
                if(displayed==null || !displayed.eligible) return;
                View button=button(binding);
                if(call.getArguments()[0]!=button || !button.isAttachedToWindow()) return;
                try {
                    if(command.change(displayed,null)) {
                        call.returnResult(null);
                        refresh();
                    }
                } catch(Exception error) { runtimeLog(error); }
            }
        }));
    }
    private View button(Object binding) throws Exception { return (View)root.get(repeatBinding.get(binding)); }
    // These are the verified 1609 host IDs, not resources in the compiler APK.
    @android.annotation.SuppressLint("ResourceType")
    private void decorate(Object binding) throws Exception {
        RadioRepeatExperiment.Snapshot snapshot=host.radioSnapshot(radio.latest());
        RadioRepeatExperiment.Snapshot previous=bindings.put(binding,snapshot);
        if(!snapshot.eligible || host.repeat(snapshot.manager)!=0) {
            bindings.put(binding,RadioRepeatExperiment.Snapshot.unavailable(snapshot.manager));
            if(previous!=null && previous.eligible) invalidate.invoke(binding);
            return;
        }
        View button=button(binding);
        ImageView off=button.findViewById(0x7f0a01a6);
        View on=button.findViewById(0x7f0a01a7);
        if(off==null || !setMask.getDeclaringClass().isInstance(on)) return;
        boolean selected=radio.effective(snapshot.manager,0,snapshot)==1;
        int icon=selected ? 0x7f080784 : 0x7f080783;
        off.setImageResource(icon); setMask.invoke(on,icon);
        off.setColorFilter(button.getContext().getColor(0x7f06044c),PorterDuff.Mode.SRC_IN);
        off.setVisibility(selected ? View.INVISIBLE : View.VISIBLE);
        on.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        button.setSelected(selected);
        button.setContentDescription(button.getResources().getString(0x7f140883)+" "
                +button.getResources().getString(selected ? 0x7f140888 : 0x7f1407a0));
    }
    void start() { active=true; }
    void refresh() { main.removeCallbacks(refreshTask); main.post(refreshTask); }
    void stop() { active=false; refresh(); }
    private void runtimeLog(Exception error) { runtime.log("Native repeat control unavailable",error); }
}
