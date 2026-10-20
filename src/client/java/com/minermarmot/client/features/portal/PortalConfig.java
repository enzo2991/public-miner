package com.minermarmot.client.features.portal;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PortalConfig {
    public int displayedWaypointCount = 3;
    public int waypointDisplayDistance = 256;
    public Map<String, Boolean> monitors = new LinkedHashMap<>();

    public PortalConfig copy() {
        PortalConfig copy = new PortalConfig();
        copy.displayedWaypointCount = displayedWaypointCount;
        copy.waypointDisplayDistance = waypointDisplayDistance;
        copy.monitors.putAll(monitors);
        return copy;
    }

    public void validate() {
        if (displayedWaypointCount < 0 || displayedWaypointCount > 100
                || waypointDisplayDistance < 1 || waypointDisplayDistance > 4096
                || monitors == null || monitors.size() > 128 || monitors.containsKey(null) || monitors.containsValue(null)) {
            throw new IllegalArgumentException("Invalid portal settings");
        }
    }
}
