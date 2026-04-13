package server;

import shared.Config;
import shared.Messages.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages every connected TCP client and owns the lobby state machine.
 *
 * Lobby phase:
 *   - Players join and appear in the lobby player list.
 *   - The first player to join becomes the "host" and may change game config.
 *   - When the host clicks "Start Game", the lobby transitions to the game phase.
 *
 * Game phase:
 *   - broadcastState() and broadcastRoundEnd() push to all clients in parallel.
 *   - killClient() lets the server admin forcibly disconnect a misbehaving client.
 */
public class ClientManager {

    // ── Phase ──────────────────────────────────────────────────────────
    private enum Phase { LOBBY, GAME }
    private volatile Phase phase = Phase.LOBBY;

    // ── Shared state ───────────────────────────────────────────────────
    private final Map<String, ClientHandler> handlers = new ConcurrentHashMap<>();
    private final GameState state;
    private final AtomicInteger playerCounter = new AtomicInteger(0);

    // ── Lobby state ────────────────────────────────────────────────────
    private volatile String     hostPlayerId = null;
    private          LobbyConfig lobbyConfig  = buildDefaultLobbyConfig();
    // Ordered list of player IDs in join order (for lobby display)
    private final List<String>  lobbyOrder   = new CopyOnWriteArrayList<>();

    // ── Game loop reference (set after construction) ───────────────────
    private GameLoop gameLoop;

    public ClientManager(GameState state) {
        this.state = state;
    }

    /** Must be called before any client connects. */
    public void setGameLoop(GameLoop gameLoop) {
        this.gameLoop = gameLoop;
    }

    // ------------------------------------------------------------------ //
    //  Accept a new TCP connection
    // ------------------------------------------------------------------ //
    public void acceptClient(Socket socket) {
        try {
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            ObjectInputStream  in  = new ObjectInputStream(socket.getInputStream());

            // Read JOIN_REQUEST
            TcpMessage msg = (TcpMessage) in.readObject();
            if (msg.type != MsgType.JOIN_REQUEST) {
                socket.close();
                return;
            }
            JoinRequest req = (JoinRequest) msg.payload;

            // Reject if game already running or lobby is full
            if (phase == Phase.GAME) {
                JoinAck nack = new JoinAck();
                nack.success = false;
                nack.message = "Game already in progress.";
                out.writeObject(new TcpMessage(MsgType.JOIN_ACK, nack));
                out.flush();
                socket.close();
                return;
            }
            int maxP = lobbyConfig.maxPlayers;
            if (handlers.size() >= maxP) {
                JoinAck nack = new JoinAck();
                nack.success = false;
                nack.message = "Lobby is full (" + maxP + "/" + maxP + ").";
                out.writeObject(new TcpMessage(MsgType.JOIN_ACK, nack));
                out.flush();
                socket.close();
                return;
            }

            // Assign player ID and starting position
            String playerId = "P" + playerCounter.incrementAndGet();
            int[]  startPos = nextStartPosition();

            boolean isHost = (hostPlayerId == null);
            if (isHost) hostPlayerId = playerId;

            // Add to game state (player exists in state but game hasn't started)
            GameState.ServerPlayer player = new GameState.ServerPlayer(
                    playerId, req.playerName, startPos[0], startPos[1]);
            state.players.put(playerId, player);
            lobbyOrder.add(playerId);

            // Build JOIN_ACK with current lobby state
            JoinAck ack          = new JoinAck();
            ack.assignedPlayerId = playerId;
            ack.success          = true;
            ack.message          = "Welcome, " + req.playerName + "!";
            ack.isHost           = isHost;
            ack.lobbyState       = buildLobbyState();

            out.writeObject(new TcpMessage(MsgType.JOIN_ACK, ack));
            out.flush();

            // Start per-client handler thread
            ClientHandler handler = new ClientHandler(playerId, socket, in, out, this);
            handlers.put(playerId, handler);
            new Thread(handler, "ClientHandler-" + playerId).start();

            System.out.println("[ClientManager] " + req.playerName
                    + " joined as " + playerId
                    + (isHost ? " [HOST]" : "")
                    + " at (" + startPos[0] + "," + startPos[1] + ")");

            // Broadcast updated lobby to everyone (including the new player)
            broadcastLobbyUpdate();

        } catch (IOException | ClassNotFoundException e) {
            System.err.println("[ClientManager] Failed to accept client: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ //
    //  Lobby message handlers (called from ClientHandler threads)
    // ------------------------------------------------------------------ //

    /** Host sent a config update. Apply and re-broadcast lobby state. */
    void handleLobbyConfigUpdate(String senderId, LobbyConfig config) {
        if (!senderId.equals(hostPlayerId)) return; // only host may change config
        if (phase != Phase.LOBBY) return;
        lobbyConfig = config;
        System.out.println("[ClientManager] Host updated config: "
                + config.roundDurationSeconds + "s, "
                + config.maxPlayers + " players");
        broadcastLobbyUpdate();
    }

    /** Host pressed "Start Game". Apply config, signal game loop, broadcast GAME_STARTING. */
    void handleLobbyStart(String senderId) {
        if (!senderId.equals(hostPlayerId)) return;
        if (phase != Phase.LOBBY) return;
        if (handlers.isEmpty()) return;

        phase = Phase.GAME;
        System.out.println("[ClientManager] Game starting! Players: " + handlers.size());

        // Apply final lobby config to game state and game loop
        state.applyConfig(lobbyConfig);
        if (gameLoop != null) {
            gameLoop.applyConfig(lobbyConfig);
            gameLoop.signalStart();
        }

        broadcast(new TcpMessage(MsgType.GAME_STARTING, null));
    }

    // ------------------------------------------------------------------ //
    //  Lobby state helpers
    // ------------------------------------------------------------------ //

    private LobbyState buildLobbyState() {
        LobbyState ls   = new LobbyState();
        ls.hostPlayerId = hostPlayerId;
        ls.config       = lobbyConfig;
        ls.players      = new ArrayList<>();

        for (String pid : lobbyOrder) {
            GameState.ServerPlayer p = state.players.get(pid);
            if (p == null) continue;
            LobbyPlayerInfo lpi = new LobbyPlayerInfo();
            lpi.id     = pid;
            lpi.name   = p.name;
            lpi.isHost = pid.equals(hostPlayerId);
            ls.players.add(lpi);
        }
        return ls;
    }

    void broadcastLobbyUpdate() {
        if (phase != Phase.LOBBY) return;
        broadcast(new TcpMessage(MsgType.LOBBY_UPDATE, buildLobbyState()));
    }

    // ------------------------------------------------------------------ //
    //  Game-phase broadcast helpers
    // ------------------------------------------------------------------ //
    public void broadcastState(GameStateSnapshot snap) {
        broadcast(new TcpMessage(MsgType.GAME_STATE, snap));
    }

    public void broadcastRoundEnd(String winnerId, Map<String, Integer> scores) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("winnerId", winnerId);
        payload.put("scores",   scores);
        broadcast(new TcpMessage(MsgType.ROUND_END, payload));
    }

    private void broadcast(TcpMessage msg) {
        for (ClientHandler handler : handlers.values()) {
            handler.send(msg);
        }
    }

    // ------------------------------------------------------------------ //
    //  Kill switch — admin can disconnect any client
    // ------------------------------------------------------------------ //
    public void killClient(String playerId) {
        ClientHandler handler = handlers.get(playerId);
        if (handler != null) {
            System.out.println("[KillSwitch] Disconnecting " + playerId);
            handler.send(new TcpMessage(MsgType.KILL_SWITCH,
                    "You have been removed by the server."));
            handler.disconnect();
        }
    }

    public void removeClient(String playerId) {
        handlers.remove(playerId);
        lobbyOrder.remove(playerId);
        state.players.remove(playerId);
        System.out.println("[ClientManager] " + playerId + " removed.");

        // If the host left during lobby, promote the next player
        if (phase == Phase.LOBBY && playerId.equals(hostPlayerId)) {
            hostPlayerId = lobbyOrder.isEmpty() ? null : lobbyOrder.get(0);
            if (hostPlayerId != null)
                System.out.println("[ClientManager] " + hostPlayerId + " is now the host.");
        }

        if (phase == Phase.LOBBY) broadcastLobbyUpdate();
    }

    public Set<String> getConnectedPlayerIds() {
        return handlers.keySet();
    }

    // ------------------------------------------------------------------ //
    //  Helpers
    // ------------------------------------------------------------------ //

    /** Build a LobbyConfig seeded from game.properties defaults. */
    private static LobbyConfig buildDefaultLobbyConfig() {
        LobbyConfig c = new LobbyConfig();
        try {
            c.roundDurationSeconds      = Config.getInt("round.duration.seconds");
            c.maxPlayers                = Config.getInt("max.players");
            c.pointsPerZoneTick         = Config.getInt("points.per.zone.tick");
            c.freezeDurationSeconds     = (int)(Config.getLong("freeze.duration.ms") / 1000);
            c.zoneCaptureTimeSeconds    = (int)(Config.getLong("zone.capture.time.ms") / 1000);
            c.itemSpawnIntervalSeconds  = (int)(Config.getLong("item.spawn.interval.ms") / 1000);
            c.tagPenaltyPoints          = Config.getInt("tag.penalty.points");
            c.speedBoostDurationSeconds = (int)(Config.getLong("speed.boost.duration.ms") / 1000);
        } catch (RuntimeException e) {
            // If any key is missing, keep the class-level defaults
            System.err.println("[ClientManager] Could not load some defaults: " + e.getMessage());
        }
        return c;
    }

    // Starting positions spread around the arena
    private static final int[][] START_POSITIONS = {
        { 40,  40}, {720,  40}, { 40, 520}, {720, 520},
        {380,  40}, {380, 520}, { 40, 280}, {720, 280}
    };
    private int startPosIndex = 0;
    private int[] nextStartPosition() {
        return START_POSITIONS[startPosIndex++ % START_POSITIONS.length];
    }

    // ------------------------------------------------------------------ //
    //  ClientHandler — one per connected player
    // ------------------------------------------------------------------ //
    static class ClientHandler implements Runnable {
        final String              playerId;
        private final Socket      socket;
        private final ObjectInputStream  in;
        private final ObjectOutputStream out;
        private final ClientManager      manager;
        private volatile boolean running = true;

        ClientHandler(String playerId, Socket socket,
                      ObjectInputStream in, ObjectOutputStream out,
                      ClientManager manager) {
            this.playerId = playerId;
            this.socket   = socket;
            this.in       = in;
            this.out      = out;
            this.manager  = manager;
        }

        @Override
        public void run() {
            try {
                while (running) {
                    TcpMessage msg = (TcpMessage) in.readObject();
                    switch (msg.type) {
                        case LEAVE -> {
                            System.out.println("[ClientHandler] " + playerId + " sent LEAVE");
                            return;
                        }
                        case LOBBY_CONFIG_UPDATE ->
                            manager.handleLobbyConfigUpdate(playerId, (LobbyConfig) msg.payload);
                        case LOBBY_START ->
                            manager.handleLobbyStart(playerId);
                        default -> { /* ignore unknown messages */ }
                    }
                }
            } catch (EOFException | SocketException e) {
                System.out.println("[ClientHandler] " + playerId + " disconnected.");
            } catch (IOException | ClassNotFoundException e) {
                System.err.println("[ClientHandler] Error from " + playerId + ": " + e.getMessage());
            } finally {
                disconnect();
            }
        }

        /** Thread-safe send */
        synchronized void send(TcpMessage msg) {
            if (!running) return;
            try {
                out.writeObject(msg);
                out.flush();
                out.reset(); // prevent object graph buildup
            } catch (IOException e) {
                System.err.println("[ClientHandler] Send failed to " + playerId);
                disconnect();
            }
        }

        void disconnect() {
            running = false;
            try { socket.close(); } catch (IOException ignored) {}
            manager.removeClient(playerId);
        }
    }
}
