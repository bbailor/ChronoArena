package shared;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * All network messages. Both TCP (via ObjectOutputStream) and UDP
 * (serialized bytes) use these types.
 */
public class Messages {

    // ------------------------------------------------------------------ //
    //  Action types (sent Client → Server over UDP)
    // ------------------------------------------------------------------ //
    public enum ActionType {
        MOVE_UP, MOVE_DOWN, MOVE_LEFT, MOVE_RIGHT,
        MOVE_UL, MOVE_UR, MOVE_DL, MOVE_DR,   // diagonal
        FREEZE_RAY,                             // use freeze weapon
        NONE
    }

    /** UDP payload: one player action per packet */
    public static class PlayerAction implements Serializable {
        public String playerId;
        public ActionType action;
        public long sequenceNumber;  // monotonically increasing per player
        public long clientTimestamp; // for latency tracking

        public PlayerAction(String playerId, ActionType action, long seq) {
            this.playerId        = playerId;
            this.action          = action;
            this.sequenceNumber  = seq;
            this.clientTimestamp = System.currentTimeMillis();
        }
    }

    // ------------------------------------------------------------------ //
    //  TCP message types (Server ↔ Client)
    // ------------------------------------------------------------------ //
    public enum MsgType {
        // Client → Server (TCP)
        JOIN_REQUEST,
        LEAVE,
        LOBBY_CONFIG_UPDATE,  // host updates game config (LobbyConfig payload)
        LOBBY_START,          // host requests game start

        // Server → Client (TCP)
        JOIN_ACK,
        LOBBY_UPDATE,         // lobby state broadcast (LobbyState payload)
        GAME_STARTING,        // transition from lobby phase to game phase
        GAME_STATE,           // full authoritative snapshot
        SCORE_UPDATE,         // lightweight score-only push
        ROUND_END,            // final scores, winner
        KILL_SWITCH,          // server tells client to disconnect
        ERROR
    }

    public static class TcpMessage implements Serializable {
        public MsgType type;
        public Object payload;  // cast based on type

        public TcpMessage(MsgType type, Object payload) {
            this.type    = type;
            this.payload = payload;
        }
    }

    // ------------------------------------------------------------------ //
    //  Lobby messages
    // ------------------------------------------------------------------ //

    /** Game settings configurable by the host during the lobby phase */
    public static class LobbyConfig implements Serializable {
        public int roundDurationSeconds      = 180;
        public int maxPlayers                = 8;
        public int pointsPerZoneTick         = 2;
        public int freezeDurationSeconds     = 3;
        public int zoneCaptureTimeSeconds    = 2;
        public int itemSpawnIntervalSeconds  = 8;
        public int tagPenaltyPoints          = 10;
        public int speedBoostDurationSeconds = 5;
    }

    /** A single player's entry in the lobby list */
    public static class LobbyPlayerInfo implements Serializable {
        public String  id;
        public String  name;
        public boolean isHost;
    }

    /** Full lobby state broadcast to all clients on any lobby change */
    public static class LobbyState implements Serializable {
        public List<LobbyPlayerInfo> players;
        public LobbyConfig           config;
        public String                hostPlayerId;
    }

    // ------------------------------------------------------------------ //
    //  Join handshake
    // ------------------------------------------------------------------ //

    /** Client → Server: JOIN_REQUEST payload */
    public static class JoinRequest implements Serializable {
        public String playerName;
    }

    /** Server → Client: JOIN_ACK payload */
    public static class JoinAck implements Serializable {
        public String     assignedPlayerId;
        public boolean    success;
        public String     message;
        public boolean    isHost;       // true if this player is the lobby host
        public LobbyState lobbyState;   // current lobby state at join time
    }

    // ------------------------------------------------------------------ //
    //  Game state messages
    // ------------------------------------------------------------------ //

    /** Server → Client: full game snapshot (sent every tick) */
    public static class GameStateSnapshot implements Serializable {
        public long tickNumber;
        public long roundTimeRemainingMs;
        public List<PlayerInfo>     players;
        public List<ZoneInfo>       zones;
        public List<ItemInfo>       items;
        public Map<String, Integer> scores; // playerId → score
    }

    public static class PlayerInfo implements Serializable {
        public String  id;
        public String  name;
        public int     x, y;
        public boolean frozen;
        public long    frozenUntilMs;
        public boolean hasWeapon;
        public int     score;
        public boolean speedBoosted;
        public long    speedBoostUntilMs;
    }

    public static class ZoneInfo implements Serializable {
        public String  id;             // "A", "B", "C" ...
        public int     x, y, width, height;
        public String  ownerPlayerId;  // null = unclaimed
        public boolean contested;
        public double  captureProgress; // 0.0 – 1.0
        public long    graceExpiresMs;  // non-zero if grace timer running
    }

    public static class ItemInfo implements Serializable {
        public String  id;
        public int     x, y;
        public boolean isWeapon;     // true = freeze weapon
        public boolean isSpeedBoost; // true = speed boost (isWeapon must be false)
    }
}
