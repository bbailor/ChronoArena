package server;

import shared.Config;
import shared.Messages.*;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Fixed-rate game loop.
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

    // For duplicate UDP detection: track last processed seq per player
    private final Map<String, Long> lastSeqProcessed = new HashMap<>();

    // Item ID counter
    private int itemCounter = 0;
    private long lastItemSpawnMs = 0;
    private final long ITEM_SPAWN_INTERVAL_MS = Config.getLong("item.spawn.interval.ms");

    // Arena bounds (match GUI)
    static final int ARENA_W = 800;
    static final int ARENA_H = 600;

    public GameLoop(GameState state,
                    ConcurrentLinkedQueue<PlayerAction> actionQueue,
                    ClientManager clientManager) {
        this.state         = state;
        this.actionQueue   = actionQueue;
        this.clientManager = clientManager;
        this.tickRateMs    = Config.getLong("tick.rate.ms");
    }

    @Override
    public void run() {
        state.running      = true;
        state.roundStartMs = System.currentTimeMillis();
        lastItemSpawnMs    = state.roundStartMs;

        System.out.println("[GameLoop] Round started. Duration: "
                + Config.getInt("round.duration.seconds") + "s  Tick: " + tickRateMs + "ms");

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

        // 4. Unfreeze players whose timer expired
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
            // duplicate or out-of-order packet - discard
            return;
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
            case FREEZE_RAY: applyFreezeRay(player); break;
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
    //  Freeze-ray attack logic
    // ------------------------------------------------------------------ //
    private static final int FREEZE_RANGE = 80; // pixels

    private void applyFreezeRay(GameState.ServerPlayer attacker) {
        if (!attacker.hasWeapon) return;

        // Find nearest unfrozen player within range
        GameState.ServerPlayer target = null;
        double bestDist = Double.MAX_VALUE;

        for (GameState.ServerPlayer p : state.players.values()) {
            if (p.id.equals(attacker.id) || p.isFrozen()) continue;
            double dist = Math.hypot(p.x - attacker.x, p.y - attacker.y);
            if (dist <= FREEZE_RANGE && dist < bestDist) {
                bestDist = dist;
                target   = p;
            }
        }

        if (target != null) {
            long freezeUntil = System.currentTimeMillis() + state.FREEZE_DURATION_MS;
            target.frozenUntilMs = freezeUntil;
            target.score = Math.max(0, target.score - state.TAG_PENALTY);
            attacker.hasWeapon = false; // weapon consumed
            System.out.println("[GameLoop] " + attacker.name + " froze " + target.name);
        }
    }

    // ------------------------------------------------------------------ //
    //  Zone logic
    // ------------------------------------------------------------------ //
    private void updateZones() {
        long now = System.currentTimeMillis();

        for (GameState.Zone zone : state.zones) {

            // Find players currently inside this zone
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

            // Award points to owner each tick
            if (zone.ownerPlayerId != null && !zone.contested) {
                GameState.ServerPlayer owner = state.players.get(zone.ownerPlayerId);
                if (owner != null) {
                    owner.score += state.POINTS_PER_ZONE_TICK;
                }
            }
        }
    }

    /**
     * Fairness rule: last owner keeps zone for GRACE_TIMER_MS after leaving.
     * If not back in time, zone becomes unclaimed.
     */
    private void handleEmptyZone(GameState.Zone zone, long now) {
        zone.contested      = false;
        zone.capturingPlayer = null;

        if (zone.ownerPlayerId != null) {
            // Start grace timer if not already running
            if (zone.graceExpiresMs == 0) {
                zone.graceExpiresMs = now + state.GRACE_TIMER_MS;
            } else if (now > zone.graceExpiresMs) {
                // Grace expired - zone is lost
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
        zone.graceExpiresMs = 0; // player is back, cancel grace

        if (player.id.equals(zone.ownerPlayerId)) {
            // Already the owner - reset capture tracker
            zone.capturingPlayer = null;
            zone.captureStartMs  = 0;
        } else {
            // Capturing a new zone
            if (!player.id.equals(zone.capturingPlayer)) {
                zone.capturingPlayer = player.id;
                zone.captureStartMs  = now;
            }
            // Check if capture complete
            if (zone.captureProgress() >= 1.0) {
                System.out.println("[GameLoop] Zone " + zone.id
                        + " captured by " + player.name);
                zone.ownerPlayerId   = player.id;
                zone.capturingPlayer = null;
                zone.captureStartMs  = 0;
            }
        }
    }

    /**
     * Contest rule: zone is contested - no one earns points.
     * First-arrived player keeps capture progress; newcomers reset it.
     */
    private void handleContestedZone(GameState.Zone zone,
                                      List<GameState.ServerPlayer> inside,
                                      long now) {
        zone.contested      = true;
        zone.graceExpiresMs = 0;

        // If the current owner is among the contested players, hold the zone
        // If no owner, the conflict freezes capture progress
        boolean ownerPresent = inside.stream()
                .anyMatch(p -> p.id.equals(zone.ownerPlayerId));

        if (!ownerPresent) {
            // Reset capture - contested with no clear winner
            zone.capturingPlayer = null;
            zone.captureStartMs  = 0;
        }
        // If owner is present, they hold the zone (no capture progress reset)
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
                    kind = "weapon";
                } else if (item.isSpeedBoost) {
                    player.speedBoostUntilMs = System.currentTimeMillis() + state.SPEED_BOOST_DURATION_MS;
                    player.speed = player.baseSpeed * state.SPEED_BOOST_MULTIPLIER;
                    kind = "speed boost";
                } else {
                    player.score += state.ENERGY_VALUE;
                    kind = "energy";
                }
                it.remove(); // item consumed
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

        // 3 equally likely types: weapon, energy, speed boost
        int type = rng.nextInt(3);
        boolean isWeapon     = (type == 0);
        boolean isSpeedBoost = (type == 2);
        String  kindName     = isWeapon ? "weapon" : (isSpeedBoost ? "speed boost" : "energy");

        state.items.put(id, new GameState.Item(id, x, y, isWeapon, isSpeedBoost));
        System.out.println("[GameLoop] Spawned " + kindName + " at (" + x + "," + y + ")");
    }

    // ------------------------------------------------------------------ //
    //  Misc
    // ------------------------------------------------------------------ //
    private void updateFreezeTimers() {
        // Restore speed when a speed boost expires
        long now = System.currentTimeMillis();
        for (GameState.ServerPlayer p : state.players.values()) {
            if (p.speedBoostUntilMs > 0 && now >= p.speedBoostUntilMs) {
                p.speed            = p.baseSpeed;
                p.speedBoostUntilMs = 0;
            }
        }
    }

    private void endRound() {
        state.running = false;

        // Find winner
        String       winnerId   = null;
        int          bestScore  = -1;
        Map<String,Integer> finalScores = new HashMap<>();

        for (GameState.ServerPlayer p : state.players.values()) {
            finalScores.put(p.id, p.score);
            if (p.score > bestScore) {
                bestScore = p.score;
                winnerId  = p.id;
            }
        }

        System.out.println("[GameLoop] Round over. Winner: " + winnerId + " with " + bestScore + " pts");
        clientManager.broadcastRoundEnd(winnerId, finalScores);
    }
}
