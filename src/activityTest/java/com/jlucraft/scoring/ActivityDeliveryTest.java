package com.jlucraft.scoring;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Plain JVM regressions for durable evidence and delivery ordering. */
final class ActivityDeliveryTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static void run() throws IOException {
        failedPersistencePreservesExactMeasurements();
        Path directory = Files.createTempDirectory(Path.of("build"), "activity-delivery-");
        try {
            oversizedWindowsAreRetainedWithoutSending(directory.resolve("limits"));
            for (int status : new int[] {400, 409, 410, 413, 415, 422}) {
                rejectedReportsDoNotBlockLaterReports(directory.resolve("status-" + status), status);
            }
            transientFailuresRemainPending(directory.resolve("retry"));
            failedQuarantineDoesNotBlockLaterReports(directory.resolve("quarantine"));
            diskWriteFailureRetainsTheWindow(directory.resolve("disk"));
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Activity delivery and persistence regression cases passed");
    }
    private static void failedPersistencePreservesExactMeasurements() throws IOException {
        ActivityWindow window = new ActivityWindow();
        UUID player = UUID.randomUUID();
        window.metrics(player).worldInteraction();
        window.metrics(player).progress();
        List<Map<String, Map<String, Number>>> attempts = new ArrayList<>();
        try {
            window.persist(rows -> { attempts.add(rows); throw new IOException("injected write failure"); });
            throw new AssertionError("write failure swallowed");
        } catch (IOException expected) {
            check(window.blocked(), "failed write must freeze the window");
        }
        window.metrics(player).worldInteraction();
        window.metrics(UUID.randomUUID()).progress();
        check(window.persist(rows -> { attempts.add(rows); return true; }), "recovery writes a deliverable window");
        check(attempts.getFirst().equals(attempts.getLast()), "retry must retain exactly the closed window");
        check(!window.blocked(), "successful write resumes collection");
        window.persist(rows -> { check(rows.isEmpty(), "successful write clears old measurements"); return true; });
    }
    private static void oversizedWindowsAreRetainedWithoutSending(Path directory) throws IOException {
        ActivityOutbox outbox = new ActivityOutbox(directory);
        check(outbox.persist(1, "at-limit", ActivityOutbox.MAX_REPORT_PLAYERS), "limit is inclusive");
        check(!outbox.persist(2, "all-257-players", ActivityOutbox.MAX_REPORT_PLAYERS + 1), "oversized report cannot be sent");
        check(outbox.persist(3, "later-valid", 1), "later report is deliverable");
        List<String> sent = new ArrayList<>();
        check(!outbox.deliver(body -> { sent.add(body); return ActivityOutbox.Delivery.Accepted; }, () -> {}), "no retries expected");
        check(sent.equals(List.of("at-limit", "later-valid")), "only bounded reports are sent");
        check(Files.readString(directory.resolve("2.rejected")).equals("all-257-players"), "full rejected evidence is retained");
    }
    private static void rejectedReportsDoNotBlockLaterReports(Path directory, int status) throws IOException {
        ActivityOutbox outbox = new ActivityOutbox(directory);
        outbox.persist(1, "bad-report", 1);
        outbox.persist(2, "valid-report", 1);
        List<String> sent = new ArrayList<>();
        int[] warnings = {0};
        check(!outbox.deliver(body -> {
            sent.add(body);
            return ActivityOutbox.classify(body.equals("bad-report") ? status : 204);
        }, () -> warnings[0]++), "permanent rejection must not schedule another retry");
        check(sent.equals(List.of("bad-report", "valid-report")), "valid report behind rejection must be delivered");
        check(warnings[0] == 1, "rejection is reported");
        check(Files.readString(directory.resolve("1.json.rejected")).equals("bad-report"), "rejected body retained");
        check(!outbox.deliver(body -> { throw new AssertionError("rejected report retried"); }, () -> {}), "drained queue remains empty");
    }
    private static void transientFailuresRemainPending(Path directory) throws IOException {
        ActivityOutbox outbox = new ActivityOutbox(directory);
        outbox.persist(1, "retry", 1);
        for (int status : new int[] {401, 403, 429, 500, 503}) {
            check(outbox.deliver(body -> ActivityOutbox.classify(status), () -> {}), "transient or operator-repairable error must retry");
            check(Files.exists(directory.resolve("1.json")), "retryable evidence must remain pending");
        }
    }
    private static void failedQuarantineDoesNotBlockLaterReports(Path directory) throws IOException {
        ActivityOutbox outbox = new ActivityOutbox(directory);
        outbox.persist(1, "bad-report", 1);
        outbox.persist(2, "valid-report", 1);
        Files.writeString(directory.resolve("1.json.rejected"), "earlier-rejection");
        List<String> sent = new ArrayList<>();
        check(outbox.deliver(body -> {
            sent.add(body);
            return body.equals("bad-report") ? ActivityOutbox.Delivery.Rejected : ActivityOutbox.Delivery.Accepted;
        }, () -> {}), "failed quarantine must retain a retry signal");
        check(sent.equals(List.of("bad-report", "valid-report")), "failed quarantine cannot block later delivery");
        check(Files.readString(directory.resolve("1.json")).equals("bad-report"), "unquarantined evidence remains pending");
        check(Files.readString(directory.resolve("1.json.rejected")).equals("earlier-rejection"), "existing rejection evidence is preserved");
    }
    private static void diskWriteFailureRetainsTheWindow(Path directory) throws IOException {
        ActivityOutbox outbox = new ActivityOutbox(directory);
        Files.createDirectory(directory.resolve("1.next"));
        ActivityWindow window = new ActivityWindow();
        window.metrics(UUID.randomUUID()).progress();
        try {
            window.persist(rows -> outbox.persist(1, "original-evidence", rows.size()));
            throw new AssertionError("blocked disk path should fail");
        } catch (IOException expected) {
            check(window.blocked(), "disk failure freezes measurements");
        }
        Files.delete(directory.resolve("1.next"));
        window.persist(rows -> {
            check(rows.size() == 1, "evidence survives disk failure");
            return outbox.persist(1, "original-evidence", rows.size());
        });
        check(Files.readString(directory.resolve("1.json")).equals("original-evidence"), "recovery persists the evidence");
    }
}
