package com.jlucraft.scoring;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.google.gson.Gson;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** The Paper server tick is the explicit clock input; no background reset or polling timer exists. */
final class ActivityProbe implements Listener, AutoCloseable {
    static final long SLICE_MS = 300_000;
    private static final int MAX_CATCHUP_SLICES = 12;
    private static final long RETRY_MS = 30_000;
    private static final Duration REPORT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration FLUSH_TIMEOUT = Duration.ofSeconds(3);
    private static final int SHUTDOWN_WAIT_SECONDS = 5;
    private final JavaPlugin plugin;
    private final Gson json = new Gson();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ThreadPoolExecutor queue = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128));
    private final AtomicBoolean pumping = new AtomicBoolean();
    private final ActivityWindow measurements = new ActivityWindow();
    private final Set<UUID> online = new HashSet<>();
    private final ActivityOutbox outbox;
    private final URI sliceEndpoint;
    private final URI flushEndpoint;
    private final String token;
    private long window;
    private volatile long retryAfter;
    private long persistRetryAfter;
    private boolean closed;

    ActivityProbe(JavaPlugin plugin) throws IOException {
        this.plugin = plugin;
        String source = plugin.getConfig().getString("activity.source", "");
        URI endpoint = URI.create(plugin.getConfig().getString("activity.endpoint", "http://127.0.0.1:8093"));
        token = System.getenv(plugin.getConfig().getString("activity.token_env", "UNION_ACTIVITY_TOKEN"));
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost()) || endpoint.getPort() <= 0
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !source.matches("[A-Za-z0-9._-]{1,128}") || token == null || token.length() < 32 || token.length() > 256
                || !token.chars().allMatch(c -> c >= 33 && c <= 126)) throw new IllegalArgumentException("Configure activity source, loopback endpoint, and activity token environment variable");
        sliceEndpoint = endpoint.resolve("/v1/activity/" + source + "/slice");
        flushEndpoint = endpoint.resolve("/v1/activity/" + source + "/flush");
        outbox = new ActivityOutbox(plugin.getDataFolder().toPath().resolve("activity-outbox"));
        window = aligned(System.currentTimeMillis());
        for (Player player : plugin.getServer().getOnlinePlayers()) join(player.getUniqueId());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        pump(System.currentTimeMillis());
    }
    private static long aligned(long now) { return now - Math.floorMod(now, SLICE_MS); }
    private void join(UUID player) { online.add(player); measurements.metrics(player); }
    private ActivityAccumulator metrics(UUID player) { return measurements.metrics(player); }
    private synchronized void advance(long now) {
        if (closed) return;
        if (measurements.blocked() && now < persistRetryAfter) { pump(now); return; }
        if (now < window) {
            // A backward host clock discards the unclosed window so no slice is counted twice.
            if (!measurements.blocked()) { window = aligned(now); measurements.reset(); for (UUID player : online) metrics(player); }
            pump(now); return;
        }
        long elapsed = (now - window) / SLICE_MS;
        long count = Math.min(elapsed, MAX_CATCHUP_SLICES);
        for (int index = 0; index < count; index++) {
            boolean recovering = measurements.blocked();
            try {
                boolean delivered = measurements.persist(rows -> {
                    Map<String, Object> report = Map.of("window_start_ms", window, "window_end_ms", window + SLICE_MS, "players", rows);
                    return outbox.persist(window, json.toJson(report), rows.size());
                });
                if (!delivered) plugin.getLogger().warning("Oversized activity window retained as rejected evidence; no activity credit was requested");
            } catch (IOException error) {
                if (!recovering) plugin.getLogger().warning("Activity evidence persistence failed; the closed window is retained in memory and later observations are paused until recovery");
                persistRetryAfter = now + RETRY_MS;
                pump(now);
                return;
            }
            window += SLICE_MS;
            if (recovering) {
                window = aligned(now);
                plugin.getLogger().warning("Activity evidence persistence recovered; observations skipped during the pause were not credited");
            }
            for (UUID player : online) metrics(player);
            if (recovering) break;
        }
        if (elapsed > MAX_CATCHUP_SLICES) window = aligned(now);
        pump(now);
    }
    private void pump(long now) {
        if (now < retryAfter || !pumping.compareAndSet(false, true)) return;
        try {
            queue.execute(() -> {
                try {
                    boolean retry = outbox.deliver(body -> send(sliceEndpoint, body, REPORT_TIMEOUT),
                            () -> plugin.getLogger().warning("Permanently rejected activity window retained as rejected evidence and was not credited"));
                    if (retry) retryAfter = System.currentTimeMillis() + RETRY_MS;
                } catch (IOException error) { retryAfter = System.currentTimeMillis() + RETRY_MS; }
                finally { pumping.set(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException error) { pumping.set(false); }
    }
    private ActivityOutbox.Delivery send(URI endpoint, String body, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return ActivityOutbox.classify(status);
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); return ActivityOutbox.Delivery.Retry; }
        catch (IOException error) { return ActivityOutbox.Delivery.Retry; }
    }
    private synchronized void world(UUID player) { advance(System.currentTimeMillis()); if (!closed) metrics(player).worldInteraction(); }
    private synchronized void progress(UUID player) { advance(System.currentTimeMillis()); if (!closed) metrics(player).progress(); }
    @EventHandler public synchronized void tick(ServerTickEndEvent event) { advance(System.currentTimeMillis()); }
    @EventHandler public synchronized void joined(PlayerJoinEvent event) { advance(System.currentTimeMillis()); join(event.getPlayer().getUniqueId()); }
    @EventHandler public synchronized void left(PlayerQuitEvent event) { advance(System.currentTimeMillis()); online.remove(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public synchronized void moved(PlayerMoveEvent event) {
        advance(System.currentTimeMillis());
        if (closed || event instanceof PlayerTeleportEvent || event.getFrom().getWorld() != event.getTo().getWorld()) return;
        var from = event.getFrom(); var to = event.getTo();
        metrics(event.getPlayer().getUniqueId()).move(to.getWorld().getUID().toString(), from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ(), to.getYaw(), to.getPitch());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void broken(BlockBreakEvent event) { world(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void placed(BlockPlaceEvent event) { world(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void interacted(PlayerInteractEvent event) { if (event.getClickedBlock() != null) world(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void entity(PlayerInteractEntityEvent event) { world(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void container(InventoryClickEvent event) { if (event.getWhoClicked() instanceof Player player && event.getClickedInventory() != null) world(player.getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void crafted(CraftItemEvent event) { if (event.getWhoClicked() instanceof Player player) progress(player.getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void traded(PlayerTradeEvent event) { progress(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR) public void advanced(PlayerAdvancementDoneEvent event) { progress(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void chatted(AsyncChatEvent event) { progress(event.getPlayer().getUniqueId()); }
    @Override public synchronized void close() {
        if (closed) return;
        advance(System.currentTimeMillis()); closed = true;
        if (measurements.blocked()) plugin.getLogger().severe("Activity shutdown with unpersisted evidence retained only in memory; this window has no durable delivery guarantee");
        queue.shutdown();
        try { if (!queue.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) queue.shutdownNow(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); queue.shutdownNow(); }
        if (send(flushEndpoint, "{}", FLUSH_TIMEOUT) != ActivityOutbox.Delivery.Accepted) plugin.getLogger().warning("Activity batch remains pending for the next explicit flush");
    }
}
