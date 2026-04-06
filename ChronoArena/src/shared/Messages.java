package shared;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * All network messages. Both TCP (via ObjectOutputStream) and UDP
 * (serialized bytes) use these types.
 *
 * UDP packets include a sequence number and player ID so the server
 * can detect out-of-order and duplicate packets.
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
    //  TCP messages (Server ↔ Client)
    // ------------------------------------------------------------------ //
    public enum MsgType {
        // Client → Server (TCP)
        JOIN_REQUEST,
        LEAVE,

        // Server → Client (TCP)
        JOIN_ACK,         // welcome + assigned player id
        GAME_STATE,       // full authoritative snapshot
        SCORE_UPDATE,     // lightweight score-only push
        ROUND_END,        // final scores, winner
        KILL_SWITCH,      // server tells client to disconnect
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

    /** Server → Client: full game snapshot (sent every tick) */
    public static class GameStateSnapshot implements Serializable {
        public long tickNumber;
        public long roundTimeRemainingMs;
        public List<PlayerInfo>  players;
        public List<ZoneInfo>    zones;
        public List<ItemInfo>    items;
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
        public boolean isWeapon; // false = energy coin
    }

    /** Client → Server: JOIN_REQUEST payload */
    public static class JoinRequest implements Serializable {
        public String playerName;
    }

    /** Server → Client: JOIN_ACK payload */
    public static class JoinAck implements Serializable {
        public String  assignedPlayerId;
        public boolean success;
        public String  message;
    }
}
