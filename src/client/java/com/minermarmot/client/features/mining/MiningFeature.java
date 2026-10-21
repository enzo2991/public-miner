package com.minermarmot.client.features.mining;

import com.google.gson.annotations.SerializedName;

public enum MiningFeature {
    @SerializedName("autoMine") AUTO_MINE("autoMine"),
    @SerializedName("autoSprint") AUTO_SPRINT("autoSprint"),
    @SerializedName("autoBoost") AUTO_BOOST("autoBoost"),
    @SerializedName("autoCameraLock") AUTO_CAMERA_LOCK("autoCameraLock"),
    @SerializedName("autoCenterBlock") AUTO_CENTER_BLOCK("autoCenterBlock"),
    @SerializedName("autoFlightHeight") AUTO_FLIGHT_HEIGHT("autoFlightHeight"),
    @SerializedName("obstacleMiner") OBSTACLE_MINER("obstacleMiner"),
    @SerializedName("worldBorder") WORLD_BORDER("worldBorder"),
    @SerializedName("placementWithoutDelay") PLACEMENT_WITHOUT_DELAY("placementWithoutDelay");

    private final String id;

    MiningFeature(String id) {
        this.id = id;
    }

    public String translationKey() {
        return "minermarmot.mining." + id;
    }
}
