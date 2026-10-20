package com.minermarmot.client.config;

import com.minermarmot.client.features.tnt.TntMode;
import com.minermarmot.client.features.mining.MiningConfig;
import com.minermarmot.client.features.mining.MiningFeature;

import com.minermarmot.client.features.alerts.BlockAlertsConfig;
import com.minermarmot.client.features.alerts.PlayerAlertsConfig;
import com.minermarmot.client.features.portal.PortalConfig;
import com.minermarmot.client.features.autocraft.AutocraftConfig;
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
    public BlockAlertsConfig alerts;
    public Map<String, BlockAlertsConfig> alertPresets = new LinkedHashMap<>();
    public PlayerAlertsConfig playerAlerts;
    public Map<String, PlayerAlertsConfig> playerAlertPresets = new LinkedHashMap<>();
    public PortalConfig portals;
    public Map<String, PortalConfig> portalPresets = new LinkedHashMap<>();
    public AutocraftConfig autocrafter;
    public Map<String, AutocraftConfig> autocrafterPresets = new LinkedHashMap<>();
    public Map<String, String> skinSelections = new LinkedHashMap<>();

    public ClientConfig copy() {
        ClientConfig copy = new ClientConfig();
        copy.preset = preset;
        copy.mining = mining.copy();
        copy.tntMode = tntMode;
        copy.displayFps = displayFps;
        copy.displayPosition = displayPosition;
        copy.displayObjectives = displayObjectives;
        copy.objectivesOnRight = objectivesOnRight;
        copy.alerts = alerts == null ? null : alerts.copy();
        alertPresets.forEach((id, settings) -> copy.alertPresets.put(id, settings.copy()));
        copy.playerAlerts = playerAlerts == null ? null : playerAlerts.copy();
        playerAlertPresets.forEach((id, settings) -> copy.playerAlertPresets.put(id, settings.copy()));
        copy.portals = portals == null ? null : portals.copy();
        portalPresets.forEach((id, settings) -> copy.portalPresets.put(id, settings.copy()));
        copy.autocrafter = autocrafter == null ? null : autocrafter.copy();
        autocrafterPresets.forEach((id, settings) -> copy.autocrafterPresets.put(id, settings.copy()));
        copy.skinSelections.putAll(skinSelections);
        return copy;
    }

    public void applyPreset(String id, MiningPreset selected) {
        if (alerts != null) {
            alertPresets.put(preset, alerts.copy());
        }
        if (playerAlerts != null) {
            playerAlertPresets.put(preset, playerAlerts.copy());
        }
        if (portals != null) portalPresets.put(preset, portals.copy());
        if (autocrafter != null) autocrafterPresets.put(preset, autocrafter.copy());
        preset = id;
        mining = selected.defaults.copy();
        selectTntMode(selected);
        alerts = alertPresets.containsKey(id) ? alertPresets.get(id).copy() : selected.blockAlerts.newConfig();
        selected.blockAlerts.initialize(alerts);
        playerAlerts = playerAlertPresets.containsKey(id) ? playerAlertPresets.get(id).copy() : selected.playerAlerts.newConfig();
        selected.playerAlerts.initialize(playerAlerts);
        portals = portalPresets.containsKey(id) ? portalPresets.get(id).copy() : selected.portals.newConfig();
        selected.portals.initialize(portals);
        autocrafter = autocrafterPresets.containsKey(id) ? autocrafterPresets.get(id).copy() : selected.autocrafter.newConfig();
        selected.autocrafter.initialize(autocrafter);
    }

    public void resetPreset(MiningPreset selected) {
        skinSelections.remove(preset);
        displayFps = false;
        displayPosition = false;
        displayObjectives = false;
        objectivesOnRight = false;
        mining = selected.defaults.copy();
        tntMode = selected.tnt.carpet && !selected.tnt.builder
                ? TntMode.CARPET : TntMode.BUILDER;
        alerts = selected.blockAlerts.newConfig();
        alertPresets.remove(preset);
        playerAlerts = selected.playerAlerts.newConfig();
        playerAlertPresets.remove(preset);
        portals = selected.portals.newConfig();
        portalPresets.remove(preset);
        autocrafter = selected.autocrafter.newConfig();
        autocrafterPresets.remove(preset);
    }

    public void validate(MiningPreset selected) {
        if (preset == null || mining == null || alertPresets == null || alertPresets.size() > 128
                || alertPresets.containsKey(null) || alertPresets.containsValue(null)
                || playerAlertPresets == null || playerAlertPresets.size() > 128
                || playerAlertPresets.containsKey(null) || playerAlertPresets.containsValue(null)
                || portalPresets == null || portalPresets.size() > 128
                || portalPresets.containsKey(null) || portalPresets.containsValue(null)
                || autocrafterPresets == null || autocrafterPresets.size() > 128
                || autocrafterPresets.containsKey(null) || autocrafterPresets.containsValue(null)
                || skinSelections == null || skinSelections.size() > 128 || skinSelections.containsKey(null)
                || skinSelections.entrySet().stream().anyMatch(entry -> entry.getKey().isBlank()
                || entry.getKey().length() > 128 || entry.getValue() == null
                || net.minecraft.resources.Identifier.tryParse(entry.getValue()) == null)) {
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
        if (alerts == null) {
            alerts = selected.blockAlerts.newConfig();
        }
        alerts.validate();
        alertPresets.values().forEach(BlockAlertsConfig::validate);
        selected.blockAlerts.initialize(alerts);
        if (playerAlerts == null) {
            playerAlerts = selected.playerAlerts.newConfig();
        }
        playerAlerts.validate();
        playerAlertPresets.values().forEach(PlayerAlertsConfig::validate);
        selected.playerAlerts.initialize(playerAlerts);
        if (portals == null) portals = selected.portals.newConfig();
        portals.validate();
        portalPresets.values().forEach(PortalConfig::validate);
        selected.portals.initialize(portals);
        if (autocrafter == null) autocrafter = selected.autocrafter.newConfig();
        autocrafter.validate();
        autocrafterPresets.values().forEach(AutocraftConfig::validate);
        selected.autocrafter.initialize(autocrafter);
    }

    private void selectTntMode(MiningPreset selected) {
        if (!selected.tnt.allows(tntMode) && !selected.tnt.modes().isEmpty()) {
            tntMode = selected.tnt.modes().getFirst();
        }
    }
}
