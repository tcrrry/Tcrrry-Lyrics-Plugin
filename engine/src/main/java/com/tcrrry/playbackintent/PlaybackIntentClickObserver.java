package com.tcrrry.playbackintent;

import android.view.View;
import android.view.ViewParent;
import dev.amenhancer.plugin.api.PluginContext;
import dev.amenhancer.plugin.api.PluginObservation;
import dev.amenhancer.plugin.api.PluginObserver;
import dev.amenhancer.plugin.api.PluginRegistration;

/** Observe actual native button clicks, without replacing their listeners. */
final class PlaybackIntentClickObserver {
    private final PlaybackIntentClickGate gate;
    private final Runnable onUserAction;
    private final ThreadLocal<java.util.ArrayDeque<Long>> calls = new ThreadLocal<>();
    private volatile boolean active;
    PlaybackIntentClickObserver(PlaybackIntentClickGate gate, Runnable onUserAction) { this.gate = gate; this.onUserAction = onUserAction; }
    void start() { active = true; }
    void stop() { active = false; gate.clear(); calls.remove(); }
    void register(PluginContext context, java.util.List<PluginRegistration> registrations) throws Exception {
        PluginObserver observer = new PluginObserver() {
            @Override public void before(PluginObservation call) {
                if (!active || !(call.getReceiver() instanceof View)) return;
                View view = (View) call.getReceiver();
                java.util.ArrayDeque<Long> stack = calls.get();
                if (stack == null) { stack = new java.util.ArrayDeque<>(); calls.set(stack); }
                String name = resourceName(view);
                boolean target = isTarget(view);
                long id = 0L;
                // An outer click can delegate to the real collection button via callOnClick.
                // If an ancestor already authorized this action, never arm it a second time.
                if (target && stack.stream().noneMatch(token -> token != 0L)) {
                    onUserAction.run();
                    id = gate.click(true);
                } else if (stack.isEmpty() && !isNonPlaybackControl(name)) {
                    onUserAction.run();
                    gate.click(false);
                }
                stack.push(id);
            }
            @Override public void after(PluginObservation call) {
                java.util.ArrayDeque<Long> stack = calls.get();
                if (stack == null || stack.isEmpty()) return;
                long id = stack.pop();
                if (id != 0 && (call.getThrowable() != null || Boolean.FALSE.equals(call.getResult()))) gate.cancel(id);
                if (stack.isEmpty()) calls.remove();
            }
        };
        registrations.add(context.getHooks().observe(View.class.getDeclaredMethod("performClick"), observer));
        registrations.add(context.getHooks().observe(View.class.getDeclaredMethod("callOnClick"), observer));
    }

    long authorizeAlbumCommand() {
        if (!active) return 0L;
        onUserAction.run();
        return gate.click(true);
    }

    static boolean isNonPlaybackControl(String name) {
        return "btn_back".equals(name) || "player_queue".equals(name) || "player_lyrics".equals(name);
    }

    private static String resourceName(View view) {
        if (view.getId() == View.NO_ID) return "";
        try {
            String pkg = view.getResources().getResourcePackageName(view.getId());
            if (!"com.apple.android.music".equals(pkg) && !"com.tcrrry.ampplyrics.coexist".equals(pkg)) return "";
            return view.getResources().getResourceEntryName(view.getId());
        } catch (android.content.res.Resources.NotFoundException ignored) { return ""; }
    }
    static boolean isTarget(View view) {
        String name = resourceName(view);
        boolean group = false;
        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            String parentName = resourceName((View)parent);
            if ("button_group_play_shuffle".equals(parentName) || "button_group_play_shuffle_action".equals(parentName)
                    || "playback_buttons".equals(parentName) || "playback_buttons_uber".equals(parentName)) {
                group = true; break;
            }
            parent = parent.getParent();
        }
        return PlaybackIntentClickGate.isTarget(name, group);
    }
}
