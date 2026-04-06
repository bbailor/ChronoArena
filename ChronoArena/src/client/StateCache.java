package client;

import shared.Messages.GameStateSnapshot;
import shared.Messages.PlayerInfo;
import shared.Messages.ZoneInfo;
import shared.Messages.ItemInfo;

import java.util.*;

/**
 * Thread-safe local copy of the authoritative game state.
 *
 * The TCP listener thread writes; the GUI (EDT) reads.
 * Synchronize on this object for consistent reads.
 */
public class StateCache {

    private final String myPlayerId;
    private volatile GameStateSnapshot latest;

    public StateCache(String myPlayerId) {
        this.myPlayerId = myPlayerId;
    }

    public synchronized void update(GameStateSnapshot snap) {
        this.latest = snap;
    }

    public synchronized GameStateSnapshot getLatest() {
        return latest;
    }

    // --- Convenience accessors for the GUI ---

    public synchronized List<PlayerInfo> getPlayers() {
        if (latest == null) return Collections.emptyList();
        return latest.players;
    }

    public synchronized List<ZoneInfo> getZones() {
        if (latest == null) return Collections.emptyList();
        return latest.zones;
    }

    public synchronized List<ItemInfo> getItems() {
        if (latest == null) return Collections.emptyList();
        return latest.items;
    }

    public synchronized long getTimeRemainingMs() {
        if (latest == null) return 0;
        return latest.roundTimeRemainingMs;
    }

    public synchronized Map<String, Integer> getScores() {
        if (latest == null) return Collections.emptyMap();
        return latest.scores;
    }

    public synchronized PlayerInfo getMyPlayer() {
        if (latest == null) return null;
        return latest.players.stream()
                .filter(p -> p.id.equals(myPlayerId))
                .findFirst().orElse(null);
    }

    public String getMyPlayerId() { return myPlayerId; }
}
