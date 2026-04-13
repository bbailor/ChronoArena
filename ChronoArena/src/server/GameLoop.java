package server;

import shared.Config;
import shared.Messages.*;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

/**
 * Fixed-rate game loop.
 *
 * Waits on a start latch until the lobby host presses "Start Game",
 * then runs the standard fixed-rate tick until the round timer expires.
 *
 * Each tick:
 *  1. Drain the action queue (all UDP inputs received this tick)
 *  2. Apply movements and actions in queue order (fairness by arrival)
 *  3. Update zone ownership, item collection, scoring
 *  4. Notify the broadcaster to push a state snapshot to all clients
 */
public class GameLoop implements Runnable {

    private final GameState state;
    private final ConcurrentLinkedQueue<PlayerAction> actionQueue;
    private final ClientManager clientManager;
    private final long tickRateMs;

    // Blocks run() until the lobby host signals start
    private final CountDownLatch startLatch = new CountDownLatch(1);

    // For duplicate UDP detection: track last processed seq per player
    private final Map<String, Long> lastSeqProcessed = new HashMap<>();

    // Item spawning
    private int  itemCounter        = 0;
    private long lastItemSpawnMs    = 0;
    private long ITEM_SPAWN_INTERVAL_MS;

    // Arena bounds (match GUI)
    static final int ARENA_W = 800;
    static final int ARENA_H = 600;

    public GameLoop(GameState state,
                    ConcurrentLinkedQueue<PlayerAction> actionQueue,
                    ClientManager clientManager) {
        this.state              = state;
        this.actionQueue        = actionQueue;
        this.clientManager      = clientManager;
        this.tickRateMs         = Config.getLong("tick.rate.ms");
        this.ITEM_SPAWN_INTERVAL_MS = Config.getLong("item.spawn.interval.ms");
    }

    // ------------------------------------------------------------------ //
    //  Lobby-to-game transition
    // ------------------------------------------------------------------ //

    /** Called by ClientManager when the host clicks "Start Game". */
    public void signalStart() {
        startLatch.countDown();
    }

    /**
     * Apply lobby config values that live in GameLoop (item spawn rate).
     * Called by ClientManager just before signalStart().
     */
    public void applyConfig(LobbyConfig config) {
        ITEM_SPAWN_INTERVAL_MS = config.itemSpawnIntervalSeconds * 1000L;
    }

    // ------------------------------------------------------------------ //
    //  Thread entry point
    // ------------------------------------------------------------------ //

    @Override
    public void run() {
        // Wait until lobby host starts the game
        try {
            startLatch.await();
        } catch (InterruptedException e) {
            System.out.println("[GameLoop] Interrupted before start.");
            return;
        }

        state.running      = true;
        state.roundStartMs = System.currentTimeMillis();
        lastItemSpawnMs    = state.roundStartMs;

        System.out.println("[GameLoop] Round started. Duration: "
                + (state.roundDurationMs / 1000) + "s  Tick: " + tickRateMs + "ms");

        while (state.running && state.timeRemainingMs() > 0) {
            long tickStart = System.currentTimeMillis();

            tick();

            long elapsed = System.currentTimeMillis() - tickStart;
            long sleep   = tickRateMs - elapsed;
            if (sleep > 0) {
                try { Thread.sleep(sleep); } catch (InterruptedException ignored) {}
            }
        }

        endRound();
    }

    // ------------------------------------------------------------------ //
    //  Main tick
    // ------------------------------------------------------------------ //
    private void tick() {
        state.tickNumber++;

        // 1. Drain and process all queued actions
        List<PlayerAction> batch = new ArrayList<>();
        PlayerAction a;
        while ((a = actionQueue.poll()) != null) {
            batch.add(a);
        }

        for (PlayerAction action : batch) {
            processAction(action);
        }

        // 2. Update zone capture timers and award zone points
        updateZones();

        // 3. Spawn items periodically
        maybeSpawnItem();

        // 4. Unfreeze players / restore speed when timers expire
        updateFreezeTimers();

        // 5. Broadcast authoritative state to all clients
        clientManager.broadcastState(state.snapshot());
    }

    // ------------------------------------------------------------------ //
    //  Action processing
    // ------------------------------------------------------------------ //
    private void processAction(PlayerAction action) {
        // --- Deduplication and out-of-order check ---
        long lastSeq = lastSeqProcessed.getOrDefault(action.playerId, -1L);
        if (action.sequenceNumber <= lastSeq) {
            return; // duplicate or out-of-order packet
        }
        lastSeqProcessed.put(action.playerId, action.sequenceNumber);

        GameState.ServerPlayer player = state.players.get(action.playerId);
        if (player == null || player.isFrozen()) return;

        switch (action.action) {
            case MOVE_UP:    move(player,  0, -player.speed); break;
            case MOVE_DOWN:  move(player,  0,  player.speed); break;
            case MOVE_LEFT:  move(player, -player.speed, 0);  break;
            case MOVE_RIGHT: move(player,  player.speed, 0);  break;
            case MOVE_UL:    move(player, -player.speed, -player.speed); break;
            case MOVE_UR:    move(player,  player.speed, -player.speed); break;
            case MOVE_DL:    move(player, -player.speed,  player.speed); break;
            case MOVE_DR:    move(player,  player.speed,  player.speed); break;
            case FREEZE_RAY:  applyFreezeRay(player);  break;
            case SCORE_STEAL: applyScoreSteal(player); break;
            default: break;
        }

        // After moving, check item pickup
        checkItemPickup(player);
    }

    private void move(GameState.ServerPlayer p, int dx, int dy) {
        int nx = Math.max(0, Math.min(ARENA_W - 20, p.x + dx));
        int ny = Math.max(0, Math.min(ARENA_H - 20, p.y + dy));
        p.x = nx;
        p.y = ny;
    }

    // ------------------------------------------------------------------ //
    //  Score-steal attack logic
    // ------------------------------------------------------------------ //
    private void applyScoreSteal(GameState.ServerPlayer attacker) {
        if (!attacker.hasScoreSteal) return;

        // Find nearest player within the same range as the freeze ray
        GameState.ServerPlayer target   = null;
        double                 bestDist = Double.MAX_VALUE;

        for (GameState.ServerPlayer p : state.players.values()) {
            if (p.id.equals(attacker.id)) continue;
            double dist = Math.hypot(p.x - attacker.x, p.y - attacker.y);
            if (dist <= FREEZE_RANGE && dist < bestDist) {
                bestDist = dist;
                target   = p;
            }
        }

        if (target != null) {
            int stolen         = Math.min(state.SCORE_STEAL_AMOUNT, target.score);
            target.score       = Math.max(0, target.score - stolen);
            attacker.score    += stolen;
            attacker.hasScoreSteal = false; // consumed on use
            System.out.println("[GameLoop] " + attacker.name
                    + " stole " + stolen + " pts from " + target.name);
        }
    }

    // ------------------------------------------------------------------ //
    //  Freeze-ray attack logic
    // ------------------------------------------------------------------ //
    private static final int FREEZE_RANGE = 80; // pixels

    private void applyFreezeRay(GameState.ServerPlayer attacker) {
        if (!attacker.hasWeapon) return;

        GameState.ServerPlayer target  = null;
        double                 bestDist = Double.MAX_VALUE;

        for (GameState.ServerPlayer p : state.players.values()) {
            if (p.id.equals(attacker.id) || p.isFrozen()) continue;
            double dist = Math.hypot(p.x - attacker.x, p.y - attacker.y);
            if (dist <= FREEZE_RANGE && dist < bestDist) {
                bestDist = dist;
                target   = p;
            }
        }

        if (target != null) {
            target.frozenUntilMs = System.currentTimeMillis() + state.FREEZE_DURATION_MS;
            target.score         = Math.max(0, target.score - state.TAG_PENALTY);
            attacker.hasWeapon   = false;
            System.out.println("[GameLoop] " + attacker.name + " froze " + target.name);
        }
    }

    // ------------------------------------------------------------------ //
    //  Zone logic
    // ------------------------------------------------------------------ //
    private void updateZones() {
        long now = System.currentTimeMillis();

        for (GameState.Zone zone : state.zones) {

            List<GameState.ServerPlayer> inside = new ArrayList<>();
            for (GameState.ServerPlayer p : state.players.values()) {
                if (!p.isFrozen() && zone.contains(p.x, p.y)) {
                    inside.add(p);
                }
            }

            if (inside.isEmpty()) {
                handleEmptyZone(zone, now);
            } else if (inside.size() == 1) {
                handleSinglePlayerInZone(zone, inside.get(0), now);
            } else {
                handleContestedZone(zone, inside, now);
            }

            // Award points to owner each tick (only when uncontested)
            if (zone.ownerPlayerId != null && !zone.contested) {
                GameState.ServerPlayer owner = state.players.get(zone.ownerPlayerId);
                if (owner != null) {
                    owner.score += state.POINTS_PER_ZONE_TICK;
                }
            }
        }
    }

    private void handleEmptyZone(GameState.Zone zone, long now) {
        zone.contested       = false;
        zone.capturingPlayer = null;

        if (zone.ownerPlayerId != null) {
            if (zone.graceExpiresMs == 0) {
                zone.graceExpiresMs = now + state.GRACE_TIMER_MS;
            } else if (now > zone.graceExpiresMs) {
                System.out.println("[GameLoop] Zone " + zone.id
                        + " lost by " + zone.ownerPlayerId + " (grace expired)");
                zone.ownerPlayerId  = null;
                zone.graceExpiresMs = 0;
            }
        }
    }

    private void handleSinglePlayerInZone(GameState.Zone zone,
                                           GameState.ServerPlayer player,
                                           long now) {
        zone.contested      = false;
        zone.graceExpiresMs = 0;

        if (player.id.equals(zone.ownerPlayerId)) {
            zone.capturingPlayer = null;
            zone.captureStartMs  = 0;
        } else {
            if (!player.id.equals(zone.capturingPlayer)) {
                zone.capturingPlayer = player.id;
                zone.captureStartMs  = now;
            }
            if (zone.captureProgress(state.ZONE_CAPTURE_TIME_MS) >= 1.0) {
                System.out.println("[GameLoop] Zone " + zone.id
                        + " captured by " + player.name);
                zone.ownerPlayerId   = player.id;
                zone.capturingPlayer = null;
                zone.captureStartMs  = 0;
            }
        }
    }

    private void handleContestedZone(GameState.Zone zone,
                                      List<GameState.ServerPlayer> inside,
                                      long now) {
        zone.contested      = true;
        zone.graceExpiresMs = 0;

        boolean ownerPresent = inside.stream()
                .anyMatch(p -> p.id.equals(zone.ownerPlayerId));

        if (!ownerPresent) {
            zone.capturingPlayer = null;
            zone.captureStartMs  = 0;
        }
    }

    // ------------------------------------------------------------------ //
    //  Item pickup
    // ------------------------------------------------------------------ //
    private static final int PICKUP_RANGE = 20;

    private void checkItemPickup(GameState.ServerPlayer player) {
        Iterator<Map.Entry<String, GameState.Item>> it = state.items.entrySet().iterator();
        while (it.hasNext()) {
            GameState.Item item = it.next().getValue();
            double dist = Math.hypot(item.x - player.x, item.y - player.y);
            if (dist <= PICKUP_RANGE) {
                String kind;
                if (item.isWeapon) {
                    player.hasWeapon = true;
                    kind = "freeze-ray";
                } else if (item.isScoreSteal) {
                    player.hasScoreSteal = true;
                    kind = "score-steal";
                } else if (item.isSpeedBoost) {
                    player.speedBoostUntilMs = System.currentTimeMillis() + state.SPEED_BOOST_DURATION_MS;
                    player.speed = player.baseSpeed * state.SPEED_BOOST_MULTIPLIER;
                    kind = "speed boost";
                } else {
                    player.score += state.ENERGY_VALUE;
                    kind = "energy";
                }
                it.remove();
                System.out.println("[GameLoop] " + player.name + " picked up " + kind);
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Item spawning
    // ------------------------------------------------------------------ //
    private final Random rng = new Random();

    private void maybeSpawnItem() {
        long now = System.currentTimeMillis();
        if (now - lastItemSpawnMs < ITEM_SPAWN_INTERVAL_MS) return;
        lastItemSpawnMs = now;

        int    x    = rng.nextInt(ARENA_W - 40) + 20;
        int    y    = rng.nextInt(ARENA_H - 40) + 20;
        String id   = "item-" + (++itemCounter);

        int    type          = rng.nextInt(4);
        boolean isWeapon     = (type == 0);
        boolean isSpeedBoost = (type == 1);
        boolean isScoreSteal = (type == 2);
        // type == 3 → energy coin
        String  kindName     = isWeapon ? "freeze-ray"
                             : isSpeedBoost ? "speed boost"
                             : isScoreSteal ? "score-steal"
                             : "energy";

        state.items.put(id, new GameState.Item(id, x, y, isWeapon, isSpeedBoost, isScoreSteal));
        System.out.println("[GameLoop] Spawned " + kindName + " at (" + x + "," + y + ")");
    }

    // ------------------------------------------------------------------ //
    //  Misc
    // ------------------------------------------------------------------ //
    private void updateFreezeTimers() {
        long now = System.currentTimeMillis();
        for (GameState.ServerPlayer p : state.players.values()) {
            if (p.speedBoostUntilMs > 0 && now >= p.speedBoostUntilMs) {
                p.speed             = p.baseSpeed;
                p.speedBoostUntilMs = 0;
            }
        }
    }

    private void endRound() {
        state.running = false;

        String              winnerId   = null;
        int                 bestScore  = -1;
        Map<String,Integer> finalScores = new HashMap<>();

        for (GameState.ServerPlayer p : state.players.values()) {
            finalScores.put(p.id, p.score);
            if (p.score > bestScore) {
                bestScore = p.score;
                winnerId  = p.id;
            }
        }

        System.out.println("[GameLoop] Round over. Winner: " + winnerId
                + " with " + bestScore + " pts");
        clientManager.broadcastRoundEnd(winnerId, finalScores);
    }
}