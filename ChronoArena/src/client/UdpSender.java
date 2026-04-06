package client;

import shared.Messages.ActionType;
import shared.Messages.PlayerAction;

import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sends UDP action packets to the server.
 *
 * Each packet carries a monotonically increasing sequence number
 * so the server can detect and discard out-of-order / duplicate packets.
 *
 * Call sendAction() from the GUI's key/input handler.
 * This class is thread-safe.
 */
public class UdpSender {

    private final DatagramSocket socket;
    private final InetAddress    serverAddr;
    private final int            serverPort;
    private final String         playerId;
    private final AtomicLong     seq = new AtomicLong(0);

    public UdpSender(DatagramSocket socket,
                      InetAddress serverAddr,
                      int serverPort,
                      String playerId) {
        this.socket     = socket;
        this.serverAddr = serverAddr;
        this.serverPort = serverPort;
        this.playerId   = playerId;
    }

    public void sendAction(ActionType action) {
        PlayerAction pa = new PlayerAction(playerId, action, seq.incrementAndGet());
        try {
            byte[] data = serialize(pa);
            DatagramPacket pkt = new DatagramPacket(data, data.length, serverAddr, serverPort);
            socket.send(pkt);
        } catch (IOException e) {
            System.err.println("[UdpSender] Send failed: " + e.getMessage());
        }
    }

    private byte[] serialize(Object obj) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(obj);
        }
        return baos.toByteArray();
    }
}
