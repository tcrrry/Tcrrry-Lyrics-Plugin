package com.tcrrry.playbackintent;

import android.content.Context;
import dev.amenhancer.plugin.api.PluginContext;
import dev.amenhancer.plugin.api.PluginObservation;
import dev.amenhancer.plugin.api.PluginObserver;
import dev.amenhancer.plugin.api.PluginRegistration;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** 1622 album Compose buttons reach the native whole-album commands without a View click. */
final class AlbumComposePlaybackBridge {
    private static final String ALBUM = "com.apple.android.music.collection2.fragment.AlbumPageFragment";

    private static final class Action {
        final String command;
        long token;
        Action(String command) { this.command = command; }
    }

    private final ThreadLocal<ArrayDeque<Action>> actions = new ThreadLocal<>();
    private final PlaybackIntentClickObserver clicks;
    private final PlaybackIntentClickGate gate;

    AlbumComposePlaybackBridge(PlaybackIntentClickObserver clicks, PlaybackIntentClickGate gate) {
        this.clicks = clicks;
        this.gate = gate;
    }

    void register(PluginContext context, List<PluginRegistration> registrations) throws Exception {
        ClassLoader loader = context.getHostClassLoader();
        Class<?> album = loader.loadClass(ALBUM);
        Class<?> commands = loader.loadClass("com.apple.android.music.player.L0");
        Class<?> item = loader.loadClass("com.apple.android.music.model.CollectionItemView");
        Class<?> filter = loader.loadClass("F9.a");
        List<PluginRegistration> pending = new ArrayList<>();
        try {
            bind(context, pending, album, loader.loadClass("com.apple.android.music.collection2.fragment.b"),
                    0, "c", commands.getDeclaredMethod("N", item, filter, Context.class, boolean.class, String.class),
                    "play");
            bind(context, pending, album, loader.loadClass("Ba.u"),
                    2, "b", commands.getDeclaredMethod("K", item, filter, Context.class, boolean.class, String.class),
                    "shuffle");
            registrations.addAll(pending);
        } catch (Exception error) {
            for (PluginRegistration registration : pending) registration.close();
            throw error;
        }
    }

    private void bind(PluginContext context, List<PluginRegistration> registrations, Class<?> album,
            Class<?> callback, int branch, String captureName, Method command, String label) throws Exception {
        Field discriminator = callback.getDeclaredField("a");
        Field capturedFragment = callback.getDeclaredField(captureName);
        Method invoke = callback.getDeclaredMethod("invoke");
        if (discriminator.getType() != int.class || capturedFragment.getType() != Object.class
                || invoke.getReturnType() != Object.class || command.getReturnType() != void.class
                || !Modifier.isStatic(command.getModifiers())) {
            throw new IllegalStateException("1622 album playback contract mismatch");
        }
        discriminator.setAccessible(true);
        capturedFragment.setAccessible(true);
        registrations.add(context.getHooks().observe(invoke, new PluginObserver() {
            private boolean matches(PluginObservation call) throws IllegalAccessException {
                Object receiver = call.getReceiver();
                return receiver != null && discriminator.getInt(receiver) == branch
                        && album.isInstance(capturedFragment.get(receiver));
            }

            @Override public void before(PluginObservation call) throws Exception {
                if (!matches(call)) return;
                ArrayDeque<Action> stack = actions.get();
                if (stack == null) { stack = new ArrayDeque<>(); actions.set(stack); }
                stack.push(new Action(label));
            }

            @Override public void after(PluginObservation call) throws Exception {
                if (!matches(call)) return;
                ArrayDeque<Action> stack = actions.get();
                if (stack == null || stack.isEmpty()) return;
                Action action = stack.pop();
                if (call.getThrowable() != null && action.token != 0) gate.cancel(action.token);
                if (stack.isEmpty()) actions.remove();
            }
        }));
        registrations.add(context.getHooks().observe(command, new PluginObserver() {
            private Action current() {
                ArrayDeque<Action> stack = actions.get();
                Action action = stack == null ? null : stack.peek();
                return action != null && label.equals(action.command) ? action : null;
            }

            @Override public void before(PluginObservation call) {
                Action action = current();
                if (action == null) return;
                action.token = clicks.authorizeAlbumCommand();
            }

            @Override public void after(PluginObservation call) {
                Action action = current();
                if (action != null && call.getThrowable() != null && action.token != 0) gate.cancel(action.token);
            }
        }));
    }
}
