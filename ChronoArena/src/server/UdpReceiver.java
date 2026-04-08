package server;

import shared.Config;
import shared.Messages.PlayerAction;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/**
 * Multithreaded UDP receiver.
 *
 * A single DatagramSocket receives packets from all players.
 * Each packet is handed off to a worker thread pool so the
 * main receive loop is never blocked by deserialization.
 *
 * Workers deserialize the PlayerAction and put it on the
 * shared ConcurrentLinkedQueue for the game loop to drain.
 *
 * Out-of-order and duplicate detection happens in GameLoop,
 * keyed on (playerId, sequenceNumber).
 */
public class UdpReceiver implements Runnable {

    private final ConcurrentLinkedQueue<PlayerAction> actionQueue;
    private final DatagramSocket socket;
    private final ExecutorService workers;
    private volatile boolean running = true;

    // Max UDP payload: serialized PlayerAction is well under 512 bytes
    private static final int BUF_SIZE = 512;

    public UdpReceiver(ConcurrentLinkedQueue<PlayerAction> actionQueue) throws SocketException {
        int port    = Config.getInt("server.udp.port");
        // SO_REUSEADDR lets us restart immediately without "Address already in use"
        DatagramSocket ds = new DatagramSocket(null);
        ds.setReuseAddress(true);
        ds.bind(new java.net.InetSocketAddress(port));
        this.socket = ds;
        // One thread per CPU core for deserializing packets
        this.workers     = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()));
        this.actionQueue = actionQueue;
        System.out.println("[UdpReceiver] Listening on UDP port " + port);
    }

    @Override
    public void run() {
        byte[] buf = new byte[BUF_SIZE];
        while (running) {
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(pkt);
                // Copy bytes before reusing buffer
                byte[] data = new byte[pkt.getLength()];
                System.arraycopy(pkt.getData(), 0, data, 0, pkt.getLength());
                workers.submit(() -> deserializeAndEnqueue(data));
            } catch (SocketException e) {
                if (running) System.err.println("[UdpReceiver] Socket error: " + e.getMessage());
            } catch (IOException e) {
                System.err.println("[UdpReceiver] Receive error: " + e.getMessage());
            }
        }
    }

    private void deserializeAndEnqueue(byte[] data) {
        try (ObjectInputStream ois =
                     new ObjectInputStream(new ByteArrayInputStream(data))) {
            PlayerAction action = (PlayerAction) ois.readObject();
            actionQueue.offer(action);
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("[UdpReceiver] Bad packet: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        socket.close();
        workers.shutdownNow();
    }
}
