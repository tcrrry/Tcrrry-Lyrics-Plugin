package com.tcrrry.playbackintent;

final class PlaybackIntentPolicy {
    static boolean supportsHost(String pkg, String version, long code) {
        return code == 1609L && "7.0.0-beta".equals(version)
                && ("com.apple.android.music".equals(pkg) || "com.tcrrry.ampplyrics.coexist".equals(pkg));
    }
    static boolean replacesQueue(int insertion) {
        return insertion == 1 || insertion == 5 || insertion == 7;
    }

    static boolean shouldClear(int insertion, int originalRepeat, int currentRepeat,
            long requestRevision, long currentRevision, boolean committed, boolean mirrored,
            boolean dynamic, boolean canRepeat, boolean normalEnabled, boolean stationEnabled) {
        if (!replacesQueue(insertion) || !committed || mirrored || requestRevision != currentRevision
                || originalRepeat != currentRepeat || currentRepeat < 1 || currentRepeat > 2) return false;
        boolean station = insertion == 7 || (dynamic && !canRepeat);
        return station ? stationEnabled : normalEnabled && currentRepeat == 1;
    }
}
