package com.minermarmot.client.features.mining;

import java.util.EnumSet;
import java.util.List;

public final class MiningConfig {
    public static final List<Integer> BORDER_DISTANCES = List.of(0, 3, 5, 7, 25);

    public EnumSet<MiningFeature> enabled = EnumSet.noneOf(MiningFeature.class);
    public int worldBorderDistance;
    public int autoBoostIntervalMs = 200;
    public int dolphinMinY = 10;
    public int dolphinMaxY = 230;
    public int dolphinAdvanceBlocks = 5;
    public int dolphinCameraRotationMs = 200;

    public MiningConfig copy() {
        MiningConfig copy = new MiningConfig();
        copy.enabled = enabled.clone();
        copy.worldBorderDistance = worldBorderDistance;
        copy.autoBoostIntervalMs = autoBoostIntervalMs;
        copy.dolphinMinY = dolphinMinY;
        copy.dolphinMaxY = dolphinMaxY;
        copy.dolphinAdvanceBlocks = dolphinAdvanceBlocks;
        copy.dolphinCameraRotationMs = dolphinCameraRotationMs;
        return copy;
    }

    public int autoBoostIntervalTicks() { return Math.max(1, (autoBoostIntervalMs + 49) / 50); }

    public void validate() {
        // Upgrade older camera timings without discarding the rest of the saved settings.
        dolphinCameraRotationMs = Math.max(200, dolphinCameraRotationMs);
        if (enabled == null || worldBorderDistance < 0 || autoBoostIntervalMs < 0 || autoBoostIntervalMs > 60000
                || dolphinMinY >= dolphinMaxY || dolphinAdvanceBlocks < 1) {
            throw new IllegalArgumentException("Invalid mining settings");
        }
    }
}
