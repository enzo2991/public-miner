package com.minermarmot.client.features.mining;

public final class WorldBorderManager {
    public enum Action { NONE, FIRST_TURN, SECOND_TURN, BLOCKED }

    private boolean turning;
    private int nextTurn = 1;
    private int turn;
    private int distance;
    private float yaw;
    private double startX;
    private double startZ;

    public Action update(double x, double z, float currentYaw, double minX, double maxX,
                         double minZ, double maxZ, int selectedDistance) {
        if (selectedDistance == 0) {
            reset();
            return Action.NONE;
        }
        if (turning) {
            double progress = (x - startX) * directionX(yaw) + (z - startZ) * directionZ(yaw);
            if (progress >= distance - 0.001) {
                yaw = normalizeYaw(yaw + turn * 90);
                turning = false;
                nextTurn = -turn;
                return Action.SECOND_TURN;
            }
            if (distanceToBorder(x, z, yaw, minX, maxX, minZ, maxZ) <= 0.35) {
                return Action.BLOCKED;
            }
            return Action.NONE;
        }
        float forward = normalizeYaw(currentYaw);
        if (distanceToBorder(x, z, forward, minX, maxX, minZ, maxZ) > 5) {
            return Action.NONE;
        }
        float lateral = normalizeYaw(forward + nextTurn * 90);
        if (distanceToBorder(x, z, lateral, minX, maxX, minZ, maxZ) < selectedDistance + 0.35) {
            return Action.BLOCKED;
        }
        turning = true;
        turn = nextTurn;
        distance = selectedDistance;
        yaw = lateral;
        startX = x;
        startZ = z;
        return Action.FIRST_TURN;
    }

    public boolean isTurning() {
        return turning;
    }

    public float yaw() {
        return yaw;
    }

    public double endX() {
        return startX + directionX(yaw - turn * 90) * distance;
    }

    public double endZ() {
        return startZ + directionZ(yaw - turn * 90) * distance;
    }

    public void reset() {
        // Mining pauses and spawn trips must keep the serpentine turn direction.
        turning = false;
    }

    public static float cardinalYaw(float yaw) {
        return Math.floorMod(Math.round(yaw / 90), 4) * 90.0f;
    }

    public static int stepX(float yaw) {
        return switch ((int) cardinalYaw(yaw) / 90) {
            case 1 -> -1;
            case 3 -> 1;
            default -> 0;
        };
    }

    public static int stepZ(float yaw) {
        return switch ((int) cardinalYaw(yaw) / 90) {
            case 0 -> 1;
            case 2 -> -1;
            default -> 0;
        };
    }

    public static double distanceToBorder(double x, double z, float yaw, double minX, double maxX,
                                          double minZ, double maxZ) {
        double dx = directionX(yaw);
        double dz = directionZ(yaw);
        double alongX = dx == 0 ? Double.POSITIVE_INFINITY : (dx > 0 ? maxX - x : minX - x) / dx;
        double alongZ = dz == 0 ? Double.POSITIVE_INFINITY : (dz > 0 ? maxZ - z : minZ - z) / dz;
        return Math.min(alongX, alongZ);
    }

    public static boolean isFrontWall(double dx, double dz, float yaw) {
        double forward = dx * directionX(yaw) + dz * directionZ(yaw);
        double side = dx * directionZ(yaw) - dz * directionX(yaw);
        return forward > 0 && forward >= Math.abs(side);
    }

    private static float normalizeYaw(float yaw) {
        return (yaw % 360 + 360) % 360;
    }

    private static double directionX(float yaw) {
        double value = -Math.sin(Math.toRadians(yaw));
        return Math.abs(value) < 1.0e-10 ? 0 : value;
    }

    private static double directionZ(float yaw) {
        double value = Math.cos(Math.toRadians(yaw));
        return Math.abs(value) < 1.0e-10 ? 0 : value;
    }
}
