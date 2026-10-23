package com.minermarmot.client.features.mining;

import java.util.function.IntPredicate;

final class DolphinPath {
    enum Phase { ASCENDING, DESCENDING, SHIFTING, TURNING }

    private final WorldBorderManager border;
    private Phase phase;
    private boolean nextAscending;
    private float yaw;
    private float headingYaw;
    private double upperPassageY;
    private double columnX;
    private double columnZ;
    private double destinationX;
    private double destinationZ;
    private double bestProgress;
    private int stalledTicks;
    private int turnDistance;

    DolphinPath(WorldBorderManager border) {
        this.border = border;
    }

    void start(double x, double y, double z, float initialYaw, float initialPitch, int minY, int maxY) {
        border.reset();
        // Limits take priority; horizontal aiming retains the upward default and max-Y endpoint maneuver.
        phase = y > maxY || y > minY && initialPitch > 0 ? Phase.DESCENDING : Phase.ASCENDING;
        yaw = WorldBorderManager.cardinalYaw(initialYaw);
        headingYaw = yaw;
        columnX = Math.floor(x) + 0.5;
        columnZ = Math.floor(z) + 0.5;
        resetProgress(progress(x, y, z));
    }

    boolean update(double x, double y, double z, boolean endVertical, double minX, double maxX,
                   double minZ, double maxZ, int spacing, MiningConfig settings) {
        if (horizontal() && !nextAscending && y < upperPassageY - 3) {
            recoverFromUpperFall(x, y, z);
        }
        if (phase == Phase.ASCENDING && (y >= settings.dolphinMaxY || endVertical)
                || phase == Phase.DESCENDING && (y <= settings.dolphinMinY || endVertical)) {
            nextAscending = phase == Phase.DESCENDING;
            upperPassageY = y;
            turnDistance = spacing;
            var action = border.update(columnX, columnZ, yaw, minX, maxX, minZ, maxZ, spacing);
            if (action == WorldBorderManager.Action.BLOCKED) return false;
            phase = action == WorldBorderManager.Action.FIRST_TURN ? Phase.TURNING : Phase.SHIFTING;
            if (phase == Phase.TURNING) yaw = border.yaw();
            int distance = phase == Phase.TURNING ? spacing : settings.dolphinAdvanceBlocks;
            destinationX = columnX + WorldBorderManager.stepX(yaw) * distance;
            destinationZ = columnZ + WorldBorderManager.stepZ(yaw) * distance;
            if (destinationX - 0.3 < minX || destinationX + 0.3 > maxX
                    || destinationZ - 0.3 < minZ || destinationZ + 0.3 > maxZ) return false;
            resetProgress(progress(x, y, z));
        } else if (horizontal() && reachedDestination(x, z)) {
            if (phase == Phase.TURNING) {
                var action = border.update(destinationX, destinationZ, yaw, minX, maxX, minZ, maxZ, turnDistance);
                if (action != WorldBorderManager.Action.SECOND_TURN) return false;
                yaw = border.yaw();
                headingYaw = yaw;
            }
            columnX = destinationX;
            columnZ = destinationZ;
            phase = nextAscending ? Phase.ASCENDING : Phase.DESCENDING;
            resetProgress(progress(x, y, z));
        }
        double progress = progress(x, y, z);
        if (progress > bestProgress + 0.05) resetProgress(progress);
        else stalledTicks++;
        // Falling during a horizontal escape is not progress towards the next column.
        int timeout = horizontal() && nextAscending && y < settings.dolphinMinY ? 40
                : Math.max(200, settings.autoBoostIntervalTicks() + 40);
        return stalledTicks <= timeout;
    }

    private double progress(double x, double y, double z) {
        return horizontal() ? x * WorldBorderManager.stepX(yaw) + z * WorldBorderManager.stepZ(yaw)
                : phase == Phase.ASCENDING ? y : -y;
    }

    private void resetProgress(double progress) {
        bestProgress = progress;
        stalledTicks = 0;
    }

    void blockBroken() { stalledTicks = 0; }

    void recoverFromUpperFall(double x, double y, double z) {
        // Resume in the actual fallen column, including an unfinished border turn.
        border.reset();
        phase = Phase.DESCENDING;
        yaw = headingYaw;
        columnX = Math.floor(x) + 0.5;
        columnZ = Math.floor(z) + 0.5;
        resetProgress(-y);
    }

    Phase phase() { return phase; }
    float yaw() { return yaw; }
    boolean horizontal() { return phase == Phase.SHIFTING || phase == Phase.TURNING; }
    boolean boosts() { return phase == Phase.ASCENDING || horizontal() && !nextAscending; }
    float pitch() { return horizontal() ? 0 : phase == Phase.ASCENDING ? -90 : 90; }
    double centerX() { return columnX; }
    double centerZ() { return columnZ; }
    double destinationX() { return destinationX; }
    double destinationZ() { return destinationZ; }

    boolean reachedDestination(double x, double z) {
        return (x - destinationX) * WorldBorderManager.stepX(yaw)
                + (z - destinationZ) * WorldBorderManager.stepZ(yaw) >= -0.001;
    }

    static boolean openAbove(IntPredicate isAirAtOffset) {
        for (int offset = 3; offset <= 10; offset++) {
            if (!isAirAtOffset.test(offset)) return false;
        }
        return true;
    }
}
