package com.minermarmot.client.config;

import com.minermarmot.client.features.tnt.TntPreset;
import com.minermarmot.client.features.mining.MiningConfig;
import com.minermarmot.client.features.mining.MiningFeature;
import com.minermarmot.client.features.alerts.AlertIcon;
import com.minermarmot.client.features.alerts.BlockAlertTarget;
import com.minermarmot.client.features.alerts.BlockAlertsPreset;
import com.minermarmot.client.features.alerts.PlayerAlertsPreset;
import com.minermarmot.client.features.portal.PortalPreset;
import com.minermarmot.client.features.autocraft.AutocraftPreset;
import com.minermarmot.client.features.skins.SkinsPreset;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Set;

public final class MiningPreset {
    public String name;
    public boolean autoBoostBlockUpward;
    public EnumMap<MiningFeature, Boolean> features = new EnumMap<>(MiningFeature.class);
    public MiningConfig defaults = new MiningConfig();
    public Set<String> monitoredBlocks = new LinkedHashSet<>();
    public BlockAlertsPreset blockAlerts = new BlockAlertsPreset();
    public PlayerAlertsPreset playerAlerts = new PlayerAlertsPreset();
    public TntPreset tnt = new TntPreset();
    public PortalPreset portals = new PortalPreset();
    public AutocraftPreset autocrafter = new AutocraftPreset();
    public SkinsPreset skins = new SkinsPreset();

    public boolean allows(MiningFeature feature) {
        return Boolean.TRUE.equals(features.get(feature));
    }

    public void validate() {
        if (name == null || name.isBlank() || features == null || defaults == null || monitoredBlocks == null
                || features.containsKey(null) || features.containsValue(null) || monitoredBlocks.size() > 4096) {
            throw new IllegalArgumentException("Invalid mining preset");
        }
        defaults.validate();
        for (String block : monitoredBlocks) {
            if (block == null || !block.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
                throw new IllegalArgumentException("Invalid monitored block identifier: " + block);
            }
        }
        if (blockAlerts == null || blockAlerts.categories == null
                || !monitoredBlocks.isEmpty() && !blockAlerts.categories.isEmpty()) {
            throw new IllegalArgumentException("Use either legacy monitoredBlocks or blockAlerts.categories");
        }
        if (!monitoredBlocks.isEmpty()) {
            BlockAlertsPreset.Category category = new BlockAlertsPreset.Category();
            for (String block : monitoredBlocks) {
                BlockAlertTarget target = new BlockAlertTarget();
                target.id = block;
                target.block = block;
                target.displayName = block.substring(block.indexOf(':') + 1).replace('_', ' ');
                target.item1 = new AlertIcon();
                target.item1.item = block;
                category.blocks.add(target);
            }
            blockAlerts.categories.put("BLOCKS", category);
            monitoredBlocks.clear();
        }
        blockAlerts.validate();
        if (playerAlerts == null) {
            throw new IllegalArgumentException("Missing player alert preset");
        }
        playerAlerts.validate();
        if (tnt == null) {
            throw new IllegalArgumentException("Missing TNT preset");
        }
        tnt.validate();
        if (portals == null) throw new IllegalArgumentException("Missing portal preset");
        portals.validate();
        if (autocrafter == null) throw new IllegalArgumentException("Missing autocrafter preset");
        autocrafter.validate();
        if (skins == null) throw new IllegalArgumentException("Missing skins preset");
        skins.validate();
        defaults.enabled.removeIf(feature -> !allows(feature));
        if (!allows(MiningFeature.WORLD_BORDER)) {
            defaults.worldBorderDistance = 0;
        }
    }
}
