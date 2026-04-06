package server;

import shared.Messages.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages every connected TCP client.
 *
 * - One thread per client (ClientHandler) reads incoming TCP messages.
 * - broadcastState() and broadcastRoundEnd() push to all clients in parallel.
 * - killClient() lets the server admin forcibly disconnect a misbehaving client.
 */
public class ClientManager {

    private final Map<String, ClientHandler> handlers = new ConcurrentHashMap<>();
    private final GameState state;
    private final ConcurrentLinkedQueue<PlayerAction> actionQueue;
    private final AtomicInteger playerCounter = new AtomicInteger(0);

    public ClientManager(GameState state,
                          ConcurrentLinkedQueue<PlayerAction> actionQueue) {
        this.state       = state;
        this.actionQueue = actionQueue;
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

            // Assign player ID and starting position
            String playerId  = "P" + playerCounter.incrementAndGet();
            int[]  startPos  = nextStartPosition();

            GameState.ServerPlayer player = new GameState.ServerPlayer(
                    playerId, req.playerName, startPos[0], startPos[1]);
            state.players.put(playerId, player);

            // Send JOIN_ACK
            JoinAck ack = new JoinAck();
            ack.assignedPlayerId = playerId;
            ack.success          = true;
            ack.message          = "Welcome, " + req.playerName + "!";
            out.writeObject(new TcpMessage(MsgType.JOIN_ACK, ack));
            out.flush();

            // Start handler thread
            ClientHandler handler = new ClientHandler(playerId, socket, in, out, this);
            handlers.put(playerId, handler);
            new Thread(handler, "ClientHandler-" + playerId).start();

            System.out.println("[ClientManager] " + req.playerName
                    + " joined as " + playerId + " at (" + startPos[0] + "," + startPos[1] + ")");

        } catch (IOException | ClassNotFoundException e) {
            System.err.println("[ClientManager] Failed to accept client: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ //
    //  Broadcast helpers
    // ------------------------------------------------------------------ //
    public void broadcastState(GameStateSnapshot snap) {
        TcpMessage msg = new TcpMessage(MsgType.GAME_STATE, snap);
        broadcast(msg);
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
    //  Kill switch - admin can disconnect any client
    // ------------------------------------------------------------------ //
    public void killClient(String playerId) {
        ClientHandler handler = handlers.get(playerId);
        if (handler != null) {
            System.out.println("[KillSwitch] Disconnecting " + playerId);
            handler.send(new TcpMessage(MsgType.KILL_SWITCH, "You have been removed by the server."));
            handler.disconnect();
        }
    }

    public void removeClient(String playerId) {
        handlers.remove(playerId);
        state.players.remove(playerId);
        System.out.println("[ClientManager] " + playerId + " removed.");
    }

    public Set<String> getConnectedPlayerIds() {
        return handlers.keySet();
    }

    // Starting positions spread around the arena
    private static final int[][] START_POSITIONS = {
        {40, 40}, {720, 40}, {40, 520}, {720, 520},
        {380, 40}, {380, 520}, {40, 280}, {720, 280}
    };

    private int startPosIndex = 0;
    private int[] nextStartPosition() {
        return START_POSITIONS[startPosIndex++ % START_POSITIONS.length];
    }

    // ------------------------------------------------------------------ //
    //  ClientHandler - one per connected player
    // ------------------------------------------------------------------ //
    static class ClientHandler implements Runnable {
        final String             playerId;
        private final Socket     socket;
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
                    if (msg.type == MsgType.LEAVE) {
                        System.out.println("[ClientHandler] " + playerId + " sent LEAVE");
                        break;
                    }
                    // Other client→server TCP messages can be handled here
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
