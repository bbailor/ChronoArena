package client;

import gui.CustomUI;
import gui.GameUI;
import gui.HeadlessUI;   // swap to CustomUI (or any GameUI impl) to change the UI
import shared.Config;
import gui.SwingUI;
import shared.Messages.*;

import java.io.*;
import java.net.*;
import java.util.Map;

/**
 * Client engine — handles all networking.
 * Has zero knowledge of any specific GUI framework.
 *
 * TO SWITCH GUI:  change the one line in main() that creates the GameUI.
 *
 *   GameUI ui = new HeadlessUI();   ← console only (current)
 *   GameUI ui = new CustomUI();     ← your blank canvas
 *   GameUI ui = new MySwingScreen(); ← any class that implements GameUI
 */
public class Client {

    public static void main(String[] args) throws Exception {

        // ── SWAP THIS LINE TO CHANGE THE GUI ─────────────────────────── //
        // GameUI ui = new CustomUI();
        GameUI ui = new SwingUI();
        // ─────────────────────────────────────────────────────────────── //

        String defaultIp = Config.get("server.ip");

        // 1. Lobby: get player name (and optional IP override) from the UI
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

        // 2. TCP connect + JOIN
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

        String myPlayerId = ack.assignedPlayerId;
        System.out.println("[Client] Joined as " + myPlayerId);

        // 3. State cache + UDP sender
        StateCache     stateCache = new StateCache(myPlayerId);
        DatagramSocket udpSocket  = new DatagramSocket();
        InetAddress    serverAddr = InetAddress.getByName(serverIp);
        UdpSender      udpSender  = new UdpSender(udpSocket, serverAddr, udpPort, myPlayerId);

        // 4. Tell the UI the game is starting
        ui.onGameStart(myPlayerId, playerName);

        // 5. Input polling thread — asks UI for an action and sends it via UDP
        startInputPoller(ui, udpSender);

        // 6. TCP listener loop (blocks this thread until round ends or disconnect)
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
    //  TCP listener
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
