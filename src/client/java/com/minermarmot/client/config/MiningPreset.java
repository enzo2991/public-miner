package com.minermarmot.client.config;

import com.minermarmot.client.features.tnt.TntPreset;
import com.minermarmot.client.features.mining.MiningConfig;
import com.minermarmot.client.features.mining.MiningFeature;
import com.minermarmot.client.features.alerts.PlayerAlertsPreset;
import com.minermarmot.client.features.portal.PortalPreset;

import java.util.EnumMap;

public final class MiningPreset {
    public String name;
    public EnumMap<MiningFeature, Boolean> features = new EnumMap<>(MiningFeature.class);
    public MiningConfig defaults = new MiningConfig();
    public PlayerAlertsPreset playerAlerts = new PlayerAlertsPreset();
    public TntPreset tnt = new TntPreset();
    public PortalPreset portals = new PortalPreset();

    public boolean allows(MiningFeature feature) {
        return Boolean.TRUE.equals(features.get(feature));
    }

    public void validate() {
        if (name == null || name.isBlank() || features == null || defaults == null
                || features.containsKey(null) || features.containsValue(null)) {
            throw new IllegalArgumentException("Invalid mining preset");
        }
        defaults.validate();
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
        defaults.enabled.removeIf(feature -> !allows(feature));
        if (!allows(MiningFeature.WORLD_BORDER)) {
            defaults.worldBorderDistance = 0;
        }
    }
}
