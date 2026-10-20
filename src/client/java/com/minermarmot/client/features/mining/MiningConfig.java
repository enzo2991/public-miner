package com.minermarmot.client.features.mining;

import java.util.EnumSet;
import java.util.List;

public final class MiningConfig {
    public static final List<Integer> BORDER_DISTANCES = List.of(0, 3, 5, 7, 25);

    public EnumSet<MiningFeature> enabled = EnumSet.noneOf(MiningFeature.class);
    public int worldBorderDistance;

    public MiningConfig copy() {
        MiningConfig copy = new MiningConfig();
        copy.enabled = enabled.clone();
        copy.worldBorderDistance = worldBorderDistance;
        return copy;
    }

    public void validate() {
        if (enabled == null || worldBorderDistance < 0) {
            throw new IllegalArgumentException("Invalid mining settings");
        }
    }
}
