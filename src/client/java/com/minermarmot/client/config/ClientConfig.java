package com.minermarmot.client.config;

import com.minermarmot.client.features.tnt.TntMode;
import com.minermarmot.client.features.mining.MiningConfig;
import com.minermarmot.client.features.mining.MiningFeature;

import com.minermarmot.client.features.alerts.PlayerAlertsConfig;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ClientConfig {
    public String preset = "default";
    public MiningConfig mining = new MiningConfig();
    public TntMode tntMode = TntMode.BUILDER;
    public boolean displayFps;
    public boolean displayPosition;
    public boolean displayObjectives;
    public boolean objectivesOnRight;
    public PlayerAlertsConfig playerAlerts;
    public Map<String, PlayerAlertsConfig> playerAlertPresets = new LinkedHashMap<>();

    public ClientConfig copy() {
        ClientConfig copy = new ClientConfig();
        copy.preset = preset;
        copy.mining = mining.copy();
        copy.tntMode = tntMode;
        copy.displayFps = displayFps;
        copy.displayPosition = displayPosition;
        copy.displayObjectives = displayObjectives;
        copy.objectivesOnRight = objectivesOnRight;
        copy.playerAlerts = playerAlerts == null ? null : playerAlerts.copy();
        playerAlertPresets.forEach((id, settings) -> copy.playerAlertPresets.put(id, settings.copy()));
        return copy;
    }

    public void applyPreset(String id, MiningPreset selected) {

        if (playerAlerts != null) {
            playerAlertPresets.put(preset, playerAlerts.copy());
        }
        preset = id;
        mining = selected.defaults.copy();
        selectTntMode(selected);
        playerAlerts = playerAlertPresets.containsKey(id) ? playerAlertPresets.get(id).copy() : selected.playerAlerts.newConfig();
        selected.playerAlerts.initialize(playerAlerts);
    }

    public void resetPreset(MiningPreset selected) {
        displayFps = false;
        displayPosition = false;
        displayObjectives = false;
        objectivesOnRight = false;
        mining = selected.defaults.copy();
        tntMode = selected.tnt.carpet && !selected.tnt.builder
                ? TntMode.CARPET : TntMode.BUILDER;
        playerAlerts = selected.playerAlerts.newConfig();
        playerAlertPresets.remove(preset);
    }

    public void validate(MiningPreset selected) {
        if (preset == null || mining == null || playerAlertPresets == null || playerAlertPresets.size() > 128
                || playerAlertPresets.containsKey(null) || playerAlertPresets.containsValue(null)) {
            throw new IllegalArgumentException("Invalid client settings");
        }
        mining.validate();
        if (tntMode == null) {
            throw new IllegalArgumentException("Unknown TNT mode");
        }
        selectTntMode(selected);
        mining.enabled.removeIf(feature -> !selected.allows(feature));
        if (!selected.allows(MiningFeature.WORLD_BORDER)) {
            mining.worldBorderDistance = 0;
        }

        if (playerAlerts == null) {
            playerAlerts = selected.playerAlerts.newConfig();
        }
        playerAlerts.validate();
        playerAlertPresets.values().forEach(PlayerAlertsConfig::validate);
        selected.playerAlerts.initialize(playerAlerts);
    }

    private void selectTntMode(MiningPreset selected) {
        if (!selected.tnt.allows(tntMode) && !selected.tnt.modes().isEmpty()) {
            tntMode = selected.tnt.modes().getFirst();
        }
    }
}
