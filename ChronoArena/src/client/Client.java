package client;

import gui.GameUI;
import gui.SwingUI;
import shared.Config;
import shared.Messages.*;

import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * Client engine — handles all networking.
 * Has zero knowledge of any specific GUI framework.
 *
 * TO SWITCH GUI:  change the one line in main() that creates the GameUI.
 *
 *   GameUI ui = new HeadlessUI();   ← console only
 *   GameUI ui = new CustomUI();     ← your blank canvas
 *   GameUI ui = new SwingUI();      ← full Swing implementation (default)
 */
public class Client {

    public static void main(String[] args) throws Exception {

        // ── SWAP THIS LINE TO CHANGE THE GUI ─────────────────────────── //
        GameUI ui = new SwingUI();
        // ─────────────────────────────────────────────────────────────── //

        String defaultIp = Config.get("server.ip");

        // 1. Pre-lobby: get player name (and optional IP override) from the UI
        String playerName = ui.promptPlayerName(defaultIp);
        if (playerName == null || playerName.isBlank()) {
            System.out.println("[Client] No name entered. Exiting.");
            return;
        }

        String serverIp = (ui.getServerIpOverride() != null && !ui.getServerIpOverride().isBlank())
                ? ui.getServerIpOverride()
                : defaultIp;

        int tcpPort = Config.getInt("server.tcp.port");
        int udpPort = Config.getInt("server.udp.port");

        System.out.println("[Client] Connecting to " + serverIp + ":" + tcpPort);

        // 2. TCP connect + JOIN_REQUEST
        Socket             tcpSocket = new Socket(serverIp, tcpPort);
        ObjectOutputStream tcpOut    = new ObjectOutputStream(tcpSocket.getOutputStream());
        tcpOut.flush();
        ObjectInputStream  tcpIn     = new ObjectInputStream(tcpSocket.getInputStream());

        JoinRequest req = new JoinRequest();
        req.playerName  = playerName;
        tcpOut.writeObject(new TcpMessage(MsgType.JOIN_REQUEST, req));
        tcpOut.flush();

        TcpMessage ackMsg = (TcpMessage) tcpIn.readObject();
        if (ackMsg.type != MsgType.JOIN_ACK) {
            System.err.println("[Client] Unexpected response from server. Exiting.");
            tcpSocket.close();
            return;
        }
        JoinAck ack = (JoinAck) ackMsg.payload;
        if (!ack.success) {
            System.err.println("[Client] Join refused: " + ack.message);
            tcpSocket.close();
            return;
        }

        String  myPlayerId = ack.assignedPlayerId;
        boolean isHost     = ack.isHost;
        System.out.println("[Client] Joined as " + myPlayerId + (isHost ? " [HOST]" : ""));

        // 3. Lobby phase — show lobby UI and wait for game to start
        //    The UI can send TcpMessages back to the server via the provided Consumer.
        LinkedBlockingQueue<TcpMessage> lobbyOutQueue = new LinkedBlockingQueue<>();
        Consumer<TcpMessage> lobbySender = lobbyOutQueue::offer;

        ui.onLobbyJoined(myPlayerId, playerName, isHost, ack.lobbyState, lobbySender);

        // Background thread drains outgoing lobby messages (config updates, start request)
        Thread lobbyOutThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    TcpMessage toSend = lobbyOutQueue.take();
                    synchronized (tcpOut) {
                        tcpOut.writeObject(toSend);
                        tcpOut.flush();
                        tcpOut.reset();
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (IOException e) {
                    System.err.println("[Client] Lobby send error: " + e.getMessage());
                    break;
                }
            }
        }, "LobbyOutThread");
        lobbyOutThread.setDaemon(true);
        lobbyOutThread.start();

        // Main thread listens for lobby updates until GAME_STARTING
        lobbyLoop: while (true) {
            TcpMessage msg = (TcpMessage) tcpIn.readObject();
            switch (msg.type) {
                case LOBBY_UPDATE -> ui.onLobbyUpdate((LobbyState) msg.payload);
                case GAME_STARTING -> { break lobbyLoop; }
                case KILL_SWITCH   -> { ui.onKilled(); tcpSocket.close(); return; }
                default            -> { /* ignore */ }
            }
        }

        lobbyOutThread.interrupt();

        // 4. Set up game-phase networking
        StateCache     stateCache = new StateCache(myPlayerId);
        DatagramSocket udpSocket  = new DatagramSocket();
        InetAddress    serverAddr = InetAddress.getByName(serverIp);
        UdpSender      udpSender  = new UdpSender(udpSocket, serverAddr, udpPort, myPlayerId);

        // 5. Tell the UI the game is starting
        ui.onGameStart(myPlayerId, playerName);

        // 6. Input polling thread — asks UI for an action and sends it via UDP
        startInputPoller(ui, udpSender);

        // 7. TCP listener loop (blocks until round ends or disconnect)
        tcpListenerLoop(tcpIn, stateCache, ui, tcpSocket);
    }

    // ------------------------------------------------------------------ //
    //  Input polling
    // ------------------------------------------------------------------ //

    private static void startInputPoller(GameUI ui, UdpSender udpSender) {
        long pollMs = Config.getLong("tick.rate.ms");
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                ActionType action = ui.getNextAction();
                if (action != null && action != ActionType.NONE) {
                    udpSender.sendAction(action);
                }
                try { Thread.sleep(pollMs); }
                catch (InterruptedException e) { break; }
            }
        }, "InputPoller");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ //
    //  TCP listener (game phase)
    // ------------------------------------------------------------------ //

    @SuppressWarnings("unchecked")
    private static void tcpListenerLoop(ObjectInputStream tcpIn,
                                         StateCache cache,
                                         GameUI ui,
                                         Socket socket) {
        try {
            while (true) {
                TcpMessage msg = (TcpMessage) tcpIn.readObject();
                switch (msg.type) {
                    case GAME_STATE -> {
                        GameStateSnapshot snap = (GameStateSnapshot) msg.payload;
                        cache.update(snap);
                        ui.onStateUpdate(snap);
                    }
                    case ROUND_END -> {
                        Map<String, Object> payload = (Map<String, Object>) msg.payload;
                        String               winnerId = (String) payload.get("winnerId");
                        Map<String, Integer> scores   = (Map<String, Integer>) payload.get("scores");
                        ui.onRoundEnd(winnerId, scores, cache.getPlayers());
                        return;
                    }
                    case KILL_SWITCH -> { ui.onKilled(); return; }
                    default -> { }
                }
            }
        } catch (EOFException | SocketException e) {
            ui.onDisconnected();
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("[Client] TCP error: " + e.getMessage());
            ui.onDisconnected();
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
