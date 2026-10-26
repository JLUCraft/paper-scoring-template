package com.jlucraft.scoring;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Durable pending and rejected reports. Rejected evidence is never sent again automatically. */
final class ActivityOutbox {
    // Matches the oracle's MAX_REPORT_PLAYERS limit.
    static final int MAX_REPORT_PLAYERS = 256;
    private static final int MAX_REPORT_BYTES = 512 * 1024;
    private static final long MAX_OUTBOX_BYTES = 64L * 1024 * 1024;
    enum Delivery { Accepted, Rejected, Retry }
    @FunctionalInterface interface Sender { Delivery send(String body); }
    private final Path directory;

    ActivityOutbox(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
    }
    static Delivery classify(int status) {
        return switch (status) {
            case 204 -> Delivery.Accepted;
            case 400, 409, 410, 413, 415, 422 -> Delivery.Rejected;
            default -> Delivery.Retry;
        };
    }
    boolean persist(long start, String report, int playerCount) throws IOException {
        byte[] content = report.getBytes(StandardCharsets.UTF_8);
        long bytes = 0;
        try (var files = Files.list(directory)) {
            for (Path path : files.toList()) {
                long size = Files.size(path);
                if (size > MAX_OUTBOX_BYTES - bytes) throw new IOException("Activity outbox full");
                bytes += size;
            }
        }
        if (content.length > MAX_OUTBOX_BYTES - bytes) throw new IOException("Activity outbox full");
        boolean deliverable = playerCount <= MAX_REPORT_PLAYERS && content.length <= MAX_REPORT_BYTES;
        Path target = directory.resolve(start + (deliverable ? ".json" : ".rejected"));
        Path temporary = directory.resolve(start + ".next");
        Files.write(temporary, content);
        try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) { file.force(true); }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return deliverable;
    }
    boolean deliver(Sender sender, Runnable rejected) throws IOException {
        boolean retry = false;
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    Delivery delivery = sender.send(Files.readString(file));
                    if (delivery == Delivery.Retry) return true;
                    if (delivery == Delivery.Rejected) {
                        Files.move(file, file.resolveSibling(file.getFileName() + ".rejected"));
                        rejected.run();
                    } else Files.delete(file);
                } catch (IOException error) {
                    // A failed quarantine or unreadable report must not hide later valid reports.
                    retry = true;
                }
            }
        }
        return retry;
    }
}
