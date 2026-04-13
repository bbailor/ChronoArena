package server;

import shared.Config;
import shared.Messages.PlayerAction;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.swing.SwingUtilities;

/**
 * Server entry point.
 *
 * Starts:
 *  - TCP listener (one thread per client, spawned by ClientManager)
 *  - UDP receiver (multithreaded worker pool)
 *  - Game loop (fixed-rate tick thread — waits on lobby start signal)
 *  - Kill-switch console (reads from stdin)
 *
 * Run: java -cp out server.Server
 * Or with custom config: java -Dconfig=/path/to/game.properties -cp out server.Server
 */
public class Server {

    public static void main(String[] args) throws IOException {
        int tcpPort = Config.getInt("server.tcp.port");
        int udpPort = Config.getInt("server.udp.port");

        System.out.println("=== ChronoArena Server ===");
        System.out.println("TCP port: " + tcpPort + "  UDP port: " + udpPort);

        // Shared state and action queue
        ConcurrentLinkedQueue<PlayerAction> actionQueue = new ConcurrentLinkedQueue<>();
        GameState     gameState     = new GameState();
        ClientManager clientManager = new ClientManager(gameState);

        // Game loop (waits on latch until lobby host starts the game)
        GameLoop gameLoop = new GameLoop(gameState, actionQueue, clientManager);
        clientManager.setGameLoop(gameLoop);
        new Thread(gameLoop, "GameLoop").start();

        // UDP receiver
        UdpReceiver udpReceiver = new UdpReceiver(actionQueue);
        new Thread(udpReceiver, "UdpReceiver").start();

        // Kill-switch console thread
        new Thread(() -> runKillSwitch(clientManager), "KillSwitch").start();

        // ── Optional: launch the server admin GUI ────────────────────
        // Comment this block out to run the server headless.
        final GameState     gsRef = gameState;
        final ClientManager cmRef = clientManager;
        SwingUtilities.invokeLater(() -> {
            ServerGUI serverGui = new ServerGUI(gsRef, cmRef);
            serverGui.setVisible(true);
        });

        // TCP listener (blocks, accepts new clients)
        // SO_REUSEADDR lets us restart immediately without waiting for TIME_WAIT
        ServerSocket tcpServer = new ServerSocket();
        tcpServer.setReuseAddress(true);
        tcpServer.bind(new java.net.InetSocketAddress(tcpPort));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Server] Shutting down...");
            try { tcpServer.close(); } catch (IOException ignored) {}
            udpReceiver.stop();
        }, "ShutdownHook"));

        System.out.println("[Server] Ready. Waiting for players...");
        while (true) {
            Socket client = tcpServer.accept();
            new Thread(() -> clientManager.acceptClient(client), "AcceptClient").start();
        }
    }

    /**
     * Kill-switch: type "kill P1" (or whatever player ID) in the server console
     * to forcibly disconnect a misbehaving client.
     */
    private static void runKillSwitch(ClientManager cm) {
        Scanner sc = new Scanner(System.in);
        System.out.println("[KillSwitch] Commands: 'kill <playerId>'  'list'  'quit'");
        while (sc.hasNextLine()) {
            String line = sc.nextLine().trim();
            if (line.startsWith("kill ")) {
                cm.killClient(line.substring(5).trim());
            } else if (line.equals("list")) {
                System.out.println("Connected: " + cm.getConnectedPlayerIds());
            } else if (line.equals("quit")) {
                System.exit(0);
            }
        }
    }
}
