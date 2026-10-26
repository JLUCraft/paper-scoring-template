package com.jlucraft.scoring;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** A failed close freezes the original measurements until their durable write succeeds. */
final class ActivityWindow {
    @FunctionalInterface interface Writer {
        boolean write(Map<String, Map<String, Number>> rows) throws IOException;
    }
    private final Map<UUID, ActivityAccumulator> players = new HashMap<>();
    private boolean blocked;

    ActivityAccumulator metrics(UUID player) {
        // Observations taken during a reported pause stay out of the closed slice.
        if (blocked) return new ActivityAccumulator();
        return players.computeIfAbsent(player, ignored -> new ActivityAccumulator());
    }
    boolean blocked() { return blocked; }
    void reset() { players.clear(); }
    boolean persist(Writer writer) throws IOException {
        Map<String, Map<String, Number>> rows = new HashMap<>();
        players.forEach((player, metric) -> rows.put(player.toString().replace("-", ""), metric.snapshot()));
        try {
            boolean delivered = writer.write(Map.copyOf(rows));
            reset();
            blocked = false;
            return delivered;
        } catch (IOException error) {
            blocked = true;
            throw error;
        }
    }
}
