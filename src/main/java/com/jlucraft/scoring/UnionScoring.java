package com.jlucraft.scoring;

import com.google.gson.Gson;
import org.bukkit.plugin.java.JavaPlugin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Gameplay plugins call this API. It does not authenticate, route, or replace players.
 * Player score keys MUST be stable federation member IDs resolved from the player's
 * authenticated forwarded Minecraft UUID and the verified identity projection.
 * Minecraft names are display labels, never authorization or score attribution keys.
 * This adapter cannot detect a game mode that supplies another member's key.
 */
public final class UnionScoring extends JavaPlugin {
    private final Gson json = new Gson();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ThreadPoolExecutor queue = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256));
    private final Set<CompletableFuture<Void>> pending = ConcurrentHashMap.newKeySet();
    private URI endpoint;
    private String token;
    private String league;
    private ActivityProbe activity;
    private boolean scoringEnabled;

    @Override public void onEnable() {
        saveDefaultConfig();
        if (getConfig().getBoolean("activity.enabled", false)) {
            try { activity = new ActivityProbe(this); }
            catch (java.io.IOException | RuntimeException error) {
                getLogger().severe("Cannot initialize activity probe; check source, endpoint, token and data directory");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }
        if (!getConfig().getBoolean("scoring.enabled", true)) return;
        endpoint = URI.create(getConfig().getString("endpoint", "http://127.0.0.1:8091"));
        token = System.getenv("UNION_SCORING_TOKEN");
        league = getConfig().getString("league");
        var assigned = getDataFolder().toPath().toAbsolutePath().getParent().getParent().resolve("union-game-session.json");
        if (java.nio.file.Files.exists(assigned)) {
            try {
                var session = json.fromJson(java.nio.file.Files.readString(assigned), com.google.gson.JsonObject.class);
                if (session.get("protocol_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported game adapter version");
                league = session.get("league").getAsString();
            }
            catch (java.io.IOException | RuntimeException error) {
                getLogger().severe("Cannot read game session binding");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }
        scoringEnabled = true;
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost()) || endpoint.getUserInfo()!=null || token==null || token.length()<32 || league==null) {
            getLogger().severe("Configure loopback scoring endpoint, league and UNION_SCORING_TOKEN");
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    /** eventId must be persisted by the game mode and reused unchanged on retry. */
    public CompletableFuture<Void> score(String eventId, Map<String, Long> players, Map<String, Long> teams) {
        return submit("/v1/score", Map.of("league", league, "event", eventId, "players", Map.copyOf(players), "teams", Map.copyOf(teams)));
    }
    /** Queue finish after all scores; a failure must be resolved before declaring completion. */
    public CompletableFuture<Void> finish() { return submit("/v1/finish", Map.of("league", league)); }
    private CompletableFuture<Void> submit(String path, Object payload) {
        final CompletableFuture<Void> result = new CompletableFuture<>();
        final String body = json.toJson(payload);
        if (!isEnabled() || !scoringEnabled) return CompletableFuture.failedFuture(new IllegalStateException("Scoring disabled"));
        pending.add(result);
        try {
            queue.execute(() -> {
                try {
                    HttpRequest request = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                    var response = http.send(request, HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode()!=204) throw new IllegalStateException("Score rejected: HTTP " + response.statusCode());
                    result.complete(null);
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); result.completeExceptionally(error); }
                catch (Exception error) { result.completeExceptionally(error); }
                finally { pending.remove(result); }
            });
        } catch (java.util.concurrent.RejectedExecutionException error) {
            pending.remove(result);
            result.completeExceptionally(error);
        }
        return result;
    }
    @Override public void onDisable() {
        if (activity != null) activity.close();
        queue.shutdown();
        try { queue.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        queue.shutdownNow();
        for (CompletableFuture<Void> future : pending) {
            future.completeExceptionally(new IllegalStateException("Scoring disabled: pending score dropped on plugin shutdown"));
        }
        pending.clear();
    }
}
