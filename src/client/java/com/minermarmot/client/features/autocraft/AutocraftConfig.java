package com.minermarmot.client.features.autocraft;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AutocraftConfig {
    public boolean enabled;
    public int delayMillis = 1000;
    public Map<String, Boolean> recipes = new LinkedHashMap<>();

    public AutocraftConfig copy() {
        AutocraftConfig copy = new AutocraftConfig();
        copy.enabled = enabled;
        copy.delayMillis = delayMillis;
        copy.recipes.putAll(recipes);
        return copy;
    }

    public void validate() {
        if (delayMillis < 50 || delayMillis > 600000 || recipes == null || recipes.size() > 256
                || recipes.containsKey(null) || recipes.containsValue(null)) {
            throw new IllegalArgumentException("Invalid autocrafter settings");
        }
    }
}
