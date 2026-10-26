package com.jlucraft.scoring;

/** Plain JVM checks keep probe arithmetic independent of a running game server. */
public final class ActivityAccumulatorTest {
    private static long field(ActivityAccumulator value, String name) { return value.snapshot().get(name).longValue(); }
    private static void equal(long expected, long actual, String message) {
        if (expected != actual) throw new AssertionError(message + ": " + expected + " != " + actual);
    }
    public static void main(String[] args) throws java.io.IOException {
        fixedViewHasNoArtificialEntropy();
        crossingTheYawBoundaryDoesNotLookLikeAFullRotation();
        repeatedTwoPositionMovementRemainsTwoPositions();
        teleportsAndInvalidCoordinatesDoNotInflateMovement();
        reportsContainCountsAndNoCoordinatesOrChat();
        System.out.println("5 activity accumulator cases passed");
        ActivityDeliveryTest.run();
    }
    private static void fixedViewHasNoArtificialEntropy() {
        ActivityAccumulator value = new ActivityAccumulator();
        for (int i = 0; i < 100; i++) value.move("world", i, 64, 0, i + 1, 64, 0, 30, 5);
        equal(0, field(value, "yaw_variance_milli"), "fixed yaw");
        equal(0, field(value, "pitch_variance_milli"), "fixed pitch");
        equal(100, field(value, "look_samples"), "actual observations");
    }
    private static void crossingTheYawBoundaryDoesNotLookLikeAFullRotation() {
        ActivityAccumulator value = new ActivityAccumulator();
        value.move("world", 0, 64, 0, 0, 64, 0, 179, 0);
        value.move("world", 0, 64, 0, 0, 64, 0, -179, 0);
        equal(2000, field(value, "yaw_variance_milli"), "two-degree wrap");
    }
    private static void repeatedTwoPositionMovementRemainsTwoPositions() {
        ActivityAccumulator value = new ActivityAccumulator();
        for (int i = 0; i < 50; i++) value.move("world", i % 2, 64, 0, (i + 1) % 2, 64, 0, i * 10, i % 10);
        equal(2, field(value, "distinct_positions"), "round trip positions");
        equal(50, field(value, "movement_steps"), "step count");
    }
    private static void teleportsAndInvalidCoordinatesDoNotInflateMovement() {
        ActivityAccumulator value = new ActivityAccumulator();
        value.move("world", 0, 64, 0, 10000, 64, 0, 30, 2);
        value.move("world", 0, 64, 0, Double.NaN, 64, 0, 30, 2);
        equal(0, field(value, "movement_steps"), "discarded movement");
        equal(0, field(value, "look_samples"), "discarded observations");
    }
    private static void reportsContainCountsAndNoCoordinatesOrChat() {
        ActivityAccumulator value = new ActivityAccumulator();
        value.worldInteraction(); value.worldInteraction(); value.progress();
        equal(2, field(value, "world_interactions"), "world interactions");
        equal(1, field(value, "progress_events"), "progress count");
        if (!value.snapshot().keySet().equals(java.util.Set.of("movement_steps", "distinct_positions", "look_samples", "yaw_variance_milli", "pitch_variance_milli", "world_interactions", "progress_events"))) throw new AssertionError("unexpected telemetry field");
    }
}
