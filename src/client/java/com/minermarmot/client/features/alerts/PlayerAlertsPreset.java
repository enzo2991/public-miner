package com.minermarmot.client.features.alerts;

import java.util.regex.Pattern;

public final class PlayerAlertsPreset {
    public boolean inventoryFull;
    public boolean privateMessage;
    public String privateMessagePattern = "";
    public PlayerAlertsConfig defaults = new PlayerAlertsConfig();
    private transient Pattern messagePattern;

    public void validate() {
        if (defaults == null || privateMessagePattern == null || privateMessagePattern.length() > 512
                || privateMessage && privateMessagePattern.isBlank()) {
            throw new IllegalArgumentException("Invalid player alert preset");
        }
        defaults.validate();
        messagePattern = privateMessagePattern.isEmpty() ? null : Pattern.compile(privateMessagePattern);
    }

    public boolean matchesPrivateMessage(String message) {
        return privateMessage && messagePattern != null && messagePattern.matcher(message).find();
    }

    public void initialize(PlayerAlertsConfig config) {
        config.inventoryFull &= inventoryFull;
        config.privateMessage &= privateMessage;
    }

    public PlayerAlertsConfig newConfig() {
        PlayerAlertsConfig config = defaults.copy();
        initialize(config);
        return config;
    }
}
