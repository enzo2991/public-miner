package com.minermarmot.client.features.tnt;

import com.google.gson.annotations.SerializedName;

public enum TntMode {
    @SerializedName("builder") BUILDER,
    @SerializedName("carpet") CARPET;

    public String translationKey() {
        return "minermarmot.tnt." + (this == BUILDER ? "builder" : "carpet");
    }
}
