package com.tcrrry.playbackintent;

final class PlaybackIntentPolicy {
    static boolean supportsHost(String pkg, String version, long code) {
        return ((code == 1609L || code == 1613L) && "7.0.0-beta".equals(version)
                || code == 1614L && "7.0.0".equals(version)
                || code == 1622L && "7.0.1".equals(version)
                || code == 1626L && "7.0.3".equals(version))
                && ("com.apple.android.music".equals(pkg) || "com.tcrrry.ampplyrics.coexist".equals(pkg));
    }
    static boolean conflictsWithRepeatOne(int repeat, int shuffle) {
        return repeat == 1 && shuffle > 0;
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
