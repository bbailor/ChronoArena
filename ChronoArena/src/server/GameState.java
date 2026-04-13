package server;

import shared.Config;
import shared.Messages.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Authoritative game state - only mutated by the game loop thread.
 * All fields are package-private; the game loop reads and writes them
 * directly. Clients receive a serialized snapshot, never this object.
 */
public class GameState {

    // --- Players ---
    final Map<String, ServerPlayer> players = new ConcurrentHashMap<>();

    // --- Zones ---
    final List<Zone> zones = new ArrayList<>();

    // --- Items ---
    final Map<String, Item> items = new ConcurrentHashMap<>();

    // --- Freeze-ray beams (short-lived, for client visuals) ---
    final List<FreezeBeam> activeBeams = new CopyOnWriteArrayList<>();

    // --- Round timing ---
    long roundStartMs;
    long roundDurationMs;
    boolean running  = false;
    long tickNumber  = 0;

    // --- Tunables (non-final so lobby config can override them before start) ---
    int   POINTS_PER_ZONE_TICK  = Config.getInt("points.per.zone.tick");
    int   ENERGY_VALUE          = Config.getInt("item.energy.value");
    int   TAG_PENALTY           = Config.getInt("tag.penalty.points");
    int   SCORE_STEAL_AMOUNT    = Config.getInt("score.steal.amount");
    long  GRACE_TIMER_MS        = Config.getLong("grace.timer.ms");
    long  FREEZE_DURATION_MS    = Config.getLong("freeze.duration.ms");
    long  ZONE_CAPTURE_TIME_MS  = Config.getLong("zone.capture.time.ms");
    long  SPEED_BOOST_DURATION_MS = Config.getLong("speed.boost.duration.ms");
    int   SPEED_BOOST_MULTIPLIER  = Config.getInt("speed.boost.multiplier");

    GameState() {
        roundDurationMs = Config.getLong("round.duration.seconds") * 1000L;
        initZones();
    }

    /**
     * Apply lobby configuration overrides.
     * Must be called before the game loop starts.
     */
    void applyConfig(LobbyConfig config) {
        roundDurationMs         = config.roundDurationSeconds * 1000L;
        POINTS_PER_ZONE_TICK    = config.pointsPerZoneTick;
        TAG_PENALTY             = config.tagPenaltyPoints;
        FREEZE_DURATION_MS      = config.freezeDurationSeconds * 1000L;
        ZONE_CAPTURE_TIME_MS    = config.zoneCaptureTimeSeconds * 1000L;
        SPEED_BOOST_DURATION_MS = config.speedBoostDurationSeconds * 1000L;
        System.out.println("[GameState] Config applied: "
            + config.roundDurationSeconds + "s round, "
            + config.maxPlayers + " max players, "
            + config.pointsPerZoneTick + " pts/zone/tick");
    }

    private void initZones() {
        // Randomly place one zone in each of the three vertical columns so
        // every part of the 800x600 arena is strategically relevant.
        Random rng   = new Random();
        int zoneW    = 120, zoneH = 120;
        int margin   = 50;
        int colW     = 800 / 3; // ~266 px per column

        for (int col = 0; col < 3; col++) {
            int minX = col * colW + margin;
            int maxX = (col + 1) * colW - zoneW - margin;
            if (maxX <= minX) maxX = minX + 1;
            int x = minX + rng.nextInt(maxX - minX);
            int y = margin + rng.nextInt(600 - zoneH - margin * 2);
            zones.add(new Zone(String.valueOf((char) ('A' + col)), x, y, zoneW, zoneH));
        }
        System.out.println("[GameState] Zones randomised: "
            + zones.get(0).x + "," + zones.get(0).y + "  "
            + zones.get(1).x + "," + zones.get(1).y + "  "
            + zones.get(2).x + "," + zones.get(2).y);
    }

    long timeRemainingMs() {
        return Math.max(0, roundDurationMs - (System.currentTimeMillis() - roundStartMs));
    }

    /** Build a serializable snapshot for broadcasting */
    GameStateSnapshot snapshot() {
        GameStateSnapshot snap = new GameStateSnapshot();
        snap.tickNumber             = tickNumber;
        snap.roundTimeRemainingMs   = timeRemainingMs();
        snap.players                = new ArrayList<>();
        snap.zones                  = new ArrayList<>();
        snap.items                  = new ArrayList<>();
        snap.scores                 = new HashMap<>();

        for (ServerPlayer p : players.values()) {
            PlayerInfo pi        = new PlayerInfo();
            pi.id                = p.id;
            pi.name              = p.name;
            pi.x                 = p.x;
            pi.y                 = p.y;
            pi.frozen            = p.isFrozen();
            pi.frozenUntilMs     = p.frozenUntilMs;
            pi.hasWeapon         = p.hasWeapon;
            pi.hasScoreSteal     = p.hasScoreSteal;
            pi.score             = p.score;
            pi.speedBoosted      = p.isSpeedBoosted();
            pi.speedBoostUntilMs = p.speedBoostUntilMs;
            pi.colorIndex        = p.colorIndex;
            snap.players.add(pi);
            snap.scores.put(p.id, p.score);
        }

        for (Zone z : zones) {
            ZoneInfo zi        = new ZoneInfo();
            zi.id              = z.id;
            zi.x               = z.x;
            zi.y               = z.y;
            zi.width           = z.width;
            zi.height          = z.height;
            zi.ownerPlayerId   = z.ownerPlayerId;
            zi.contested       = z.contested;
            zi.captureProgress = z.captureProgress(ZONE_CAPTURE_TIME_MS);
            zi.graceExpiresMs  = z.graceExpiresMs;
            snap.zones.add(zi);
        }

        for (Item item : items.values()) {
            ItemInfo ii       = new ItemInfo();
            ii.id             = item.id;
            ii.x              = item.x;
            ii.y              = item.y;
            ii.isWeapon       = item.isWeapon;
            ii.isSpeedBoost   = item.isSpeedBoost;
            ii.isScoreSteal   = item.isScoreSteal;
            ii.despawnAtMs    = item.despawnAtMs;
            snap.items.add(ii);
        }

        long now = System.currentTimeMillis();
        snap.beams = new ArrayList<>();
        for (FreezeBeam beam : activeBeams) {
            if (beam.expiresAtMs > now) {
                FreezeBeamInfo bi = new FreezeBeamInfo();
                bi.fromX       = beam.fromX;
                bi.fromY       = beam.fromY;
                bi.toX         = beam.toX;
                bi.toY         = beam.toY;
                bi.expiresAtMs = beam.expiresAtMs;
                snap.beams.add(bi);
            }
        }

        return snap;
    }

    // ------------------------------------------------------------------ //
    //  Inner model classes (live only on the server)
    // ------------------------------------------------------------------ //

    static class ServerPlayer {
        final String id;
        final String name;
        int x, y;
        int score         = 0;
        boolean hasWeapon     = false;
        boolean hasScoreSteal = false; // score steal weapon
        long frozenUntilMs     = 0;
        long speedBoostUntilMs = 0;
        long lastSeqNum        = -1; // UDP dedup
        int  colorIndex        = 0;  // player-chosen color (index into PLAYER_COLORS)

        // Speed: normally 5px per tick; can be boosted
        int speed     = 5;
        int baseSpeed = 5;

        ServerPlayer(String id, String name, int startX, int startY) {
            this.id   = id;
            this.name = name;
            this.x    = startX;
            this.y    = startY;
        }

        boolean isFrozen() {
            return System.currentTimeMillis() < frozenUntilMs;
        }

        boolean isSpeedBoosted() {
            return System.currentTimeMillis() < speedBoostUntilMs;
        }
    }

    static class Zone {
        final String id;
        final int x, y, width, height;
        String  ownerPlayerId   = null;
        boolean contested       = false;
        long    graceExpiresMs  = 0;       // 0 = no grace running
        long    captureStartMs  = 0;       // when current capture began
        String  capturingPlayer = null;    // who is currently capturing

        Zone(String id, int x, int y, int w, int h) {
            this.id     = id;
            this.x      = x;
            this.y      = y;
            this.width  = w;
            this.height = h;
        }

        boolean contains(int px, int py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }

        /** captureProgress now takes the duration so it respects lobby config */
        double captureProgress(long captureDurationMs) {
            if (capturingPlayer == null || captureStartMs == 0) return 0;
            long elapsed = System.currentTimeMillis() - captureStartMs;
            return Math.min(1.0, (double) elapsed / captureDurationMs);
        }
    }

    static class Item {
        final String  id;
        final int     x, y;
        final boolean isWeapon;
        final boolean isSpeedBoost;
        final boolean isScoreSteal;
        final long    despawnAtMs; // epoch ms when this item expires

        Item(String id, int x, int y, boolean isWeapon, boolean isSpeedBoost,
             boolean isScoreSteal, long despawnAtMs) {
            this.id           = id;
            this.x            = x;
            this.y            = y;
            this.isWeapon     = isWeapon;
            this.isSpeedBoost = isSpeedBoost;
            this.isScoreSteal = isScoreSteal;
            this.despawnAtMs  = despawnAtMs;
        }
    }

    /** Short-lived beam created when a freeze ray fires (server-side record). */
    static class FreezeBeam {
        final int  fromX, fromY;
        final int  toX,   toY;
        final long expiresAtMs;

        FreezeBeam(int fromX, int fromY, int toX, int toY, long expiresAtMs) {
            this.fromX       = fromX;
            this.fromY       = fromY;
            this.toX         = toX;
            this.toY         = toY;
            this.expiresAtMs = expiresAtMs;
        }
    }
}