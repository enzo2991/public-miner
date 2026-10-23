package com.minermarmot.client.features.mining;

import net.minecraft.util.Mth;

final class DolphinCamera {
    private boolean initialized;
    private float startYaw, startPitch, targetYaw, targetPitch, yaw, pitch;
    private int elapsed, duration;

    void tick(float currentYaw, float currentPitch, float wantedYaw, float wantedPitch) {
        if (!matches(currentYaw, currentPitch) || Math.abs(Mth.wrapDegrees(targetYaw - wantedYaw)) > 0.001f
                || Math.abs(targetPitch - wantedPitch) > 0.001f) {
            initialized = true;
            startYaw = currentYaw; startPitch = currentPitch;
            targetYaw = currentYaw + Mth.wrapDegrees(wantedYaw - currentYaw);
            targetPitch = wantedPitch;
            float distance = Math.max(Math.abs(targetYaw - startYaw), Math.abs(targetPitch - startPitch));
            duration = distance < 0.001f ? 0 : Math.max(2, (int) Math.ceil(distance / 45));
            elapsed = 0;
        }
        float t = duration == 0 ? 1 : Math.min(1, (float) ++elapsed / duration);
        float eased = t * t * (3 - 2 * t);
        yaw = startYaw + (targetYaw - startYaw) * eased;
        pitch = startPitch + (targetPitch - startPitch) * eased;
    }

    boolean matches(float currentYaw, float currentPitch) {
        return initialized && Math.abs(Mth.wrapDegrees(yaw - currentYaw)) < 0.001f
                && Math.abs(pitch - currentPitch) < 0.001f;
    }

    boolean ready() { return initialized && elapsed >= duration; }
    float yaw() { return yaw; }
    float pitch() { return pitch; }
    void reset() { initialized = false; }
}
