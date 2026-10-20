package com.minermarmot.client.features.alerts;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

public final class BlockAlertsConfig {
    public boolean sounds = true;
    public boolean waypoints = true;
    public boolean waypointSortByRarity = false;
    public boolean glow = true;
    public boolean glowHiddenEdges = true;
    public int soundDelayTicks = 40;
    public float soundVolume = 1.0f;
    public int displayedWaypointCount = 10;
    public int waypointDisplayDistance = 256;
    public int vanillaOreScanRadius = 16;
    public int otherBlockScanRadius = 16;
    public Map<String, BlockAlertSettings> targets = new LinkedHashMap<>();

    public boolean withinDisplayDistance(BlockPos pos, Vec3 player) {
        return pos.distToCenterSqr(player) <= (double) waypointDisplayDistance * waypointDisplayDistance;
    }

    public BlockAlertsConfig copy() {
        BlockAlertsConfig copy = new BlockAlertsConfig();
        copy.sounds = sounds;
        copy.waypoints = waypoints;
        copy.waypointSortByRarity = waypointSortByRarity;
        copy.glow = glow;
        copy.glowHiddenEdges = glowHiddenEdges;
        copy.soundDelayTicks = soundDelayTicks;
        copy.soundVolume = soundVolume;
        copy.displayedWaypointCount = displayedWaypointCount;
        copy.waypointDisplayDistance = waypointDisplayDistance;
        copy.vanillaOreScanRadius = vanillaOreScanRadius;
        copy.otherBlockScanRadius = otherBlockScanRadius;
        targets.forEach((id, settings) -> copy.targets.put(id, settings.copy()));
        return copy;
    }

    public void validate() {
        if (!BlockAlertSettings.validVolume(soundVolume) || soundDelayTicks < 0 || soundDelayTicks > 12000 || displayedWaypointCount < 0
                || displayedWaypointCount > 100 || waypointDisplayDistance < 1 || waypointDisplayDistance > 4096
                || vanillaOreScanRadius < 1 || vanillaOreScanRadius > 128 || otherBlockScanRadius < 1 || otherBlockScanRadius > 128
                || targets == null || targets.size() > 4096 || targets.containsKey(null) || targets.containsValue(null)) {
            throw new IllegalArgumentException("Invalid block alert settings");
        }
        targets.values().forEach(BlockAlertSettings::validate);
    }
}
