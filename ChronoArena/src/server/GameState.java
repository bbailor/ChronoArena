package server;

import shared.Config;
import shared.Messages.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    // --- Round timing ---
    long roundStartMs;
    long roundDurationMs;
    boolean running = false;
    long tickNumber  = 0;

    // --- Tunables from config ---
    final int   POINTS_PER_ZONE_TICK  = Config.getInt("points.per.zone.tick");
    final int   ENERGY_VALUE          = Config.getInt("item.energy.value");
    final int   TAG_PENALTY           = Config.getInt("tag.penalty.points");
    final long  GRACE_TIMER_MS        = Config.getLong("grace.timer.ms");
    final long  FREEZE_DURATION_MS        = Config.getLong("freeze.duration.ms");
    final long  ZONE_CAPTURE_TIME_MS      = Config.getLong("zone.capture.time.ms");
    final long  SPEED_BOOST_DURATION_MS   = Config.getLong("speed.boost.duration.ms");
    final int   SPEED_BOOST_MULTIPLIER    = Config.getInt("speed.boost.multiplier");

    GameState() {
        roundDurationMs = Config.getLong("round.duration.seconds") * 1000L;
        initZones();
    }

    private void initZones() {
        // Three zones on the map - positions match the GUI constants
        zones.add(new Zone("A", 80,  100, 120, 120));
        zones.add(new Zone("B", 320, 100, 120, 120));
        zones.add(new Zone("C", 560, 100, 120, 120));
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
            PlayerInfo pi    = new PlayerInfo();
            pi.id            = p.id;
            pi.name          = p.name;
            pi.x             = p.x;
            pi.y             = p.y;
            pi.frozen             = p.isFrozen();
            pi.frozenUntilMs      = p.frozenUntilMs;
            pi.hasWeapon          = p.hasWeapon;
            pi.score              = p.score;
            pi.speedBoosted       = p.isSpeedBoosted();
            pi.speedBoostUntilMs  = p.speedBoostUntilMs;
            snap.players.add(pi);
            snap.scores.put(p.id, p.score);
        }

        for (Zone z : zones) {
            ZoneInfo zi          = new ZoneInfo();
            zi.id                = z.id;
            zi.x                 = z.x;
            zi.y                 = z.y;
            zi.width             = z.width;
            zi.height            = z.height;
            zi.ownerPlayerId     = z.ownerPlayerId;
            zi.contested         = z.contested;
            zi.captureProgress   = z.captureProgress();
            zi.graceExpiresMs    = z.graceExpiresMs;
            snap.zones.add(zi);
        }

        for (Item item : items.values()) {
            ItemInfo ii       = new ItemInfo();
            ii.id             = item.id;
            ii.x              = item.x;
            ii.y              = item.y;
            ii.isWeapon       = item.isWeapon;
            ii.isSpeedBoost   = item.isSpeedBoost;
            snap.items.add(ii);
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
        int score    = 0;
        boolean hasWeapon = false;
        long frozenUntilMs     = 0;
        long speedBoostUntilMs = 0;
        long lastSeqNum        = -1; // UDP dedup

        // Speed: normally 5px per tick; can be boosted
        int speed    = 5;
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
        String  ownerPlayerId    = null;
        boolean contested        = false;
        long    graceExpiresMs   = 0;       // 0 = no grace running
        long    captureStartMs   = 0;       // when current capture began
        String  capturingPlayer  = null;    // who is currently capturing

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

        double captureProgress() {
            if (capturingPlayer == null || captureStartMs == 0) return 0;
            long elapsed = System.currentTimeMillis() - captureStartMs;
            long cap     = Config.getLong("zone.capture.time.ms");
            return Math.min(1.0, (double) elapsed / cap);
        }
    }

    static class Item {
        final String  id;
        final int     x, y;
        final boolean isWeapon;
        final boolean isSpeedBoost;

        Item(String id, int x, int y, boolean isWeapon, boolean isSpeedBoost) {
            this.id          = id;
            this.x           = x;
            this.y           = y;
            this.isWeapon    = isWeapon;
            this.isSpeedBoost = isSpeedBoost;
        }
    }
}
