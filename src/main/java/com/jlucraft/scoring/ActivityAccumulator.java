package com.jlucraft.scoring;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Window-local counts only. Coordinates, chat text, and player names never leave the process. */
final class ActivityAccumulator {
    private static final int MAX_POSITIONS = 256;
    private static final int MAX_SAMPLES = 100_000;
    private static final int MAX_COUNTER = 1_000_000;
    private static final double MIN_STEP_SQUARED = 0.0625;
    private static final double MAX_STEP_SQUARED = 4096;
    private static final double VARIANCE_SCALE = 1000;
    private record Block(String world, int x, int y, int z) {}
    private final Set<Block> positions = new HashSet<>();
    private int movement;
    private int looks;
    private int worldInteractions;
    private int progress;
    private double yawMean;
    private double yawM2;
    private double pitchMean;
    private double pitchM2;
    private double lastYaw;
    private double unwrappedYaw;

    void move(String world, double x, double y, double z, double toX, double toY, double toZ, double yaw, double pitch) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(toX) || !Double.isFinite(toY) || !Double.isFinite(toZ)
                || !Double.isFinite(yaw) || !Double.isFinite(pitch)) return;
        double dx = toX - x, dy = toY - y, dz = toZ - z;
        double squared = dx * dx + dy * dy + dz * dz;
        if (squared > MAX_STEP_SQUARED) return;
        if (squared >= MIN_STEP_SQUARED) {
            movement = increment(movement);
            if (positions.size() < MAX_POSITIONS) positions.add(new Block(world, (int) Math.floor(toX), (int) Math.floor(toY), (int) Math.floor(toZ)));
        }
        if (looks >= MAX_SAMPLES) return;
        if (looks == 0) unwrappedYaw = yaw;
        else {
            double delta = ((yaw - lastYaw) % 360 + 540) % 360 - 180;
            unwrappedYaw += delta;
        }
        lastYaw = yaw;
        looks++;
        double yawDelta = unwrappedYaw - yawMean;
        yawMean += yawDelta / looks;
        yawM2 += yawDelta * (unwrappedYaw - yawMean);
        double pitchDelta = pitch - pitchMean;
        pitchMean += pitchDelta / looks;
        pitchM2 += pitchDelta * (pitch - pitchMean);
    }
    void worldInteraction() { worldInteractions = increment(worldInteractions); }
    void progress() { progress = increment(progress); }
    private static int increment(int value) { return Math.min(MAX_COUNTER, value + 1); }
    private long variance(double sum) { return looks > 1 ? Math.max(0, (long) (sum / (looks - 1) * VARIANCE_SCALE)) : 0; }
    Map<String, Number> snapshot() {
        return Map.of("movement_steps", movement, "distinct_positions", positions.size(), "look_samples", looks,
                "yaw_variance_milli", variance(yawM2), "pitch_variance_milli", variance(pitchM2),
                "world_interactions", worldInteractions, "progress_events", progress);
    }
}
