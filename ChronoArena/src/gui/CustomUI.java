package gui;

import java.util.List;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import shared.Messages.*;

import java.util.function.Consumer;

public class CustomUI implements GameUI {

    private volatile GameStateSnapshot latestSnapshot;
    private String myPlayerId;

    // Key state
    private final Set<Integer> heldKeys = Collections.synchronizedSet(new HashSet<>());

    // Pending one-shot action (freeze ray) - consumed once per press
    private final AtomicReference<ActionType> pendingAction =
            new AtomicReference<>(ActionType.NONE);

    private JFrame frame;
    private ArenaPanel arenaPanel;

    // ---- Notification system ----
    private static class Notification {
        final String text;
        final Color  color;
        final long   expireMs;
        Notification(String text, Color color, long durationMs) {
            this.text     = text;
            this.color    = color;
            this.expireMs = System.currentTimeMillis() + durationMs;
        }
    }
    private final CopyOnWriteArrayList<Notification> notifications = new CopyOnWriteArrayList<>();
    private volatile boolean wasFrozen = false;

    // ------------------------------------------------------------------ //
    //  Lifecycle
    // ------------------------------------------------------------------ //

    @Override
    public void onGameStart(String myPlayerId, String playerName) {
        this.myPlayerId = myPlayerId;

        SwingUtilities.invokeLater(() -> {
            frame = new JFrame("ChronoArena — " + playerName);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            arenaPanel = new ArenaPanel();
            frame.add(arenaPanel, BorderLayout.CENTER);

            frame.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    heldKeys.add(e.getKeyCode());
                    if (e.getKeyCode() == KeyEvent.VK_SPACE) {
                        pendingAction.set(ActionType.FREEZE_RAY);
                    }
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                        System.exit(0);
                    }
                }
                @Override
                public void keyReleased(KeyEvent e) {
                    heldKeys.remove(e.getKeyCode());
                }
            });

            frame.setFocusable(true);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            frame.requestFocus();
        });
    }

    @Override
    public void onStateUpdate(GameStateSnapshot snapshot) {
        // Detect freeze event for local player → show notification
        if (myPlayerId != null && snapshot.players != null) {
            for (PlayerInfo p : snapshot.players) {
                if (p.id.equals(myPlayerId)) {
                    if (p.frozen && !wasFrozen) {
                        notifications.add(new Notification("TAGGED!  −10 PTS", Color.RED, 2500));
                    }
                    wasFrozen = p.frozen;
                    break;
                }
            }
        }

        latestSnapshot = snapshot;
        if (arenaPanel != null) {
            SwingUtilities.invokeLater(arenaPanel::repaint);
        }
    }

    @Override
    public void onRoundEnd(String winnerId, Map<String, Integer> scores,
                            List<PlayerInfo> players) {
        SwingUtilities.invokeLater(() -> {
            StringBuilder sb = new StringBuilder("ROUND OVER\n\n");
            scores.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> {
                    String name = players.stream()
                        .filter(p -> p.id.equals(e.getKey()))
                        .map(p -> p.name).findFirst().orElse(e.getKey());
                    String marker = e.getKey().equals(winnerId) ? " ★" : "";
                    sb.append(String.format("%-14s %d pts%s\n", name, e.getValue(), marker));
                });
            JOptionPane.showMessageDialog(frame, sb.toString(), "Final Scores",
                    JOptionPane.INFORMATION_MESSAGE);
            System.exit(0);
        });
    }

    @Override
    public void onKilled() {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(frame, "Removed by server.",
                    "Disconnected", JOptionPane.WARNING_MESSAGE);
            System.exit(0);
        });
    }

    @Override
    public void onDisconnected() {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(frame, "Lost connection to server.",
                    "Disconnected", JOptionPane.ERROR_MESSAGE);
            System.exit(0);
        });
    }

    // ------------------------------------------------------------------ //
    //  Input
    // ------------------------------------------------------------------ //

    @Override
    public ActionType getNextAction() {
        ActionType oneShot = pendingAction.getAndSet(ActionType.NONE);
        if (oneShot != ActionType.NONE) return oneShot;

        boolean up    = heldKeys.contains(KeyEvent.VK_W) || heldKeys.contains(KeyEvent.VK_UP);
        boolean down  = heldKeys.contains(KeyEvent.VK_S) || heldKeys.contains(KeyEvent.VK_DOWN);
        boolean left  = heldKeys.contains(KeyEvent.VK_A) || heldKeys.contains(KeyEvent.VK_LEFT);
        boolean right = heldKeys.contains(KeyEvent.VK_D) || heldKeys.contains(KeyEvent.VK_RIGHT);

        if (up   && left)  return ActionType.MOVE_UL;
        if (up   && right) return ActionType.MOVE_UR;
        if (down && left)  return ActionType.MOVE_DL;
        if (down && right) return ActionType.MOVE_DR;
        if (up)            return ActionType.MOVE_UP;
        if (down)          return ActionType.MOVE_DOWN;
        if (left)          return ActionType.MOVE_LEFT;
        if (right)         return ActionType.MOVE_RIGHT;

        return ActionType.NONE;
    }

    // ------------------------------------------------------------------ //
    //  Lobby
    // ------------------------------------------------------------------ //
    //  Lobby
    // ------------------------------------------------------------------ //
    private Consumer<TcpMessage> lobbySender;

    @Override
    public void onLobbyJoined(String myPlayerId, String playerName,
                               boolean isHost, LobbyState initialState,
                               Consumer<TcpMessage> messageSender) {
        this.lobbySender = messageSender;
        // TODO: show a lobby screen; call lobbySender.accept(new TcpMessage(MsgType.LOBBY_START, null))
        // when the host is ready to start, or auto-start here for testing:
        if (isHost) messageSender.accept(new TcpMessage(MsgType.LOBBY_START, null));
    }

    @Override
    public void onLobbyUpdate(LobbyState state) {
        // TODO: refresh player list and config display
    }

    // ------------------------------------------------------------------ //
    private String enteredName = "Player";
    private String enteredIp   = null;

    @Override
    public String promptPlayerName(String defaultServerIp) {
        JTextField nameField = new JTextField("Player", 15);
        JTextField ipField   = new JTextField(defaultServerIp, 15);
        JPanel panel = new JPanel(new GridLayout(2, 2, 6, 6));
        panel.add(new JLabel("Your name:"));   panel.add(nameField);
        panel.add(new JLabel("Server IP:"));   panel.add(ipField);

        int result = JOptionPane.showConfirmDialog(null, panel,
                "ChronoArena — Join", JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) return null;

        enteredName = nameField.getText().trim();
        enteredIp   = ipField.getText().trim();
        return enteredName.isEmpty() ? "Player" : enteredName;
    }

    @Override
    public String getServerIpOverride() { return enteredIp; }


    // ------------------------------------------------------------------ //
    //  Arena Panel
    // ------------------------------------------------------------------ //

    class ArenaPanel extends JPanel {

        static final int ARENA_W = 800;
        static final int ARENA_H = 600;
        static final int HUD_H   = 60;

        // 8 distinct player colors
        private final Color[] PLAYER_COLORS = {
            new Color(30,  120, 220),   // blue
            new Color(220,  50,  50),   // red
            new Color(50,  190,  70),   // green
            new Color(210, 160,   0),   // gold
            new Color(180,  60, 200),   // purple
            new Color(0,   200, 200),   // teal
            new Color(230, 120,  20),   // orange
            new Color(220, 220,  50),   // yellow
        };

        ArenaPanel() {
            setPreferredSize(new Dimension(ARENA_W, ARENA_H + HUD_H));
            setBackground(Color.DARK_GRAY);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            GameStateSnapshot snap = latestSnapshot;
            if (snap == null) {
                g.setColor(Color.WHITE);
                g.setFont(new Font("Monospaced", Font.BOLD, 16));
                g.drawString("Waiting for server...", 20, 40);
                return;
            }

            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // Build stable color map (same order each frame)
            Map<String, Color> playerColors = buildColorMap(snap);

            drawHUD(g2, snap, playerColors);
            drawZones(g2, snap, playerColors);
            drawItems(g2, snap);
            drawPlayers(g2, snap, playerColors);
            drawNotifications(g2);
        }

        /** Assign a stable color to each player by insertion order. */
        private Map<String, Color> buildColorMap(GameStateSnapshot snap) {
            Map<String, Color> map = new LinkedHashMap<>();
            int i = 0;
            for (PlayerInfo p : snap.players) {
                map.put(p.id, PLAYER_COLORS[i++ % PLAYER_COLORS.length]);
            }
            return map;
        }

        // ---- HUD ----

        private void drawHUD(Graphics2D g, GameStateSnapshot snap,
                             Map<String, Color> playerColors) {
            // Background
            g.setColor(new Color(15, 15, 15));
            g.fillRect(0, 0, getWidth(), HUD_H);
            g.setColor(new Color(60, 60, 60));
            g.drawLine(0, HUD_H - 1, getWidth(), HUD_H - 1);

            // Title
            g.setFont(new Font("Monospaced", Font.BOLD, 16));
            g.setColor(new Color(255, 200, 0));
            g.drawString("CHRONOARENA", 10, 22);

            // Timer
            long sec = snap.roundTimeRemainingMs / 1000;
            String timeStr = String.format("TIME  %02d:%02d", sec / 60, sec % 60);
            g.setFont(new Font("Monospaced", Font.BOLD, 20));
            g.setColor(sec <= 30 ? Color.RED : Color.WHITE);
            FontMetrics fm = g.getFontMetrics();
            int timeX = (getWidth() - fm.stringWidth(timeStr)) / 2;
            g.drawString(timeStr, timeX, 38);

            // Scores — sorted highest first, drawn right-aligned
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(snap.scores.entrySet());
            sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());

            // Build name lookup
            Map<String, String> nameMap = new HashMap<>();
            for (PlayerInfo p : snap.players) nameMap.put(p.id, p.name);

            int scoreX = getWidth() - 10;
            g.setFont(new Font("Monospaced", Font.BOLD, 13));
            for (Map.Entry<String, Integer> e : sorted) {
                String label = String.format("%s: %d",
                    nameMap.getOrDefault(e.getKey(), e.getKey()), e.getValue());
                FontMetrics sfm = g.getFontMetrics();
                scoreX -= sfm.stringWidth(label) + 14;

                // Colored box behind own score
                Color pc = playerColors.getOrDefault(e.getKey(), Color.GRAY);
                if (e.getKey().equals(myPlayerId)) {
                    g.setColor(pc);
                    g.fillRoundRect(scoreX - 4, 8, sfm.stringWidth(label) + 8, 22, 6, 6);
                    g.setColor(Color.WHITE);
                } else {
                    g.setColor(pc.darker());
                    g.fillRoundRect(scoreX - 4, 8, sfm.stringWidth(label) + 8, 22, 6, 6);
                    g.setColor(new Color(220, 220, 220));
                }
                g.drawString(label, scoreX, 24);
            }

            // ESC hint
            g.setFont(new Font("Monospaced", Font.PLAIN, 10));
            g.setColor(new Color(120, 120, 120));
            g.drawString("[ESC] quit  [WASD] move  [SPACE] freeze", 10, HUD_H - 8);
        }

        // ---- Zones ----

        private void drawZones(Graphics2D g, GameStateSnapshot snap,
                               Map<String, Color> playerColors) {
            Map<String, String> nameMap = new HashMap<>();
            for (PlayerInfo p : snap.players) nameMap.put(p.id, p.name);

            for (ZoneInfo z : snap.zones) {
                int zy = z.y + HUD_H;

                // Fill
                Color fill;
                if (z.contested) {
                    fill = new Color(200, 100, 0, 90);
                } else if (z.ownerPlayerId != null) {
                    Color pc = playerColors.getOrDefault(z.ownerPlayerId, Color.GREEN);
                    fill = new Color(pc.getRed(), pc.getGreen(), pc.getBlue(), 60);
                } else {
                    fill = new Color(80, 80, 80, 60);
                }
                g.setColor(fill);
                g.fillRect(z.x, zy, z.width, z.height);

                // Border
                Color border;
                if (z.contested) {
                    border = Color.ORANGE;
                } else if (z.ownerPlayerId != null) {
                    border = playerColors.getOrDefault(z.ownerPlayerId, Color.GREEN);
                } else {
                    border = new Color(150, 150, 150);
                }
                g.setColor(border);
                Stroke prev = g.getStroke();
                g.setStroke(new BasicStroke(z.contested ? 2.5f : 1.5f));
                g.drawRect(z.x, zy, z.width, z.height);
                g.setStroke(prev);

                // Zone ID label (top-left)
                g.setFont(new Font("Monospaced", Font.BOLD, 13));
                g.setColor(Color.WHITE);
                g.drawString("ZONE " + z.id, z.x + 6, zy + 18);

                // Status label (second line)
                String statusLabel;
                Color statusColor;
                if (z.contested) {
                    statusLabel = "CONTESTED";
                    statusColor = Color.ORANGE;
                } else if (z.ownerPlayerId != null) {
                    String ownerName = nameMap.getOrDefault(z.ownerPlayerId, z.ownerPlayerId);
                    statusLabel = "CONTROLLED";
                    statusColor = playerColors.getOrDefault(z.ownerPlayerId, Color.GREEN).brighter();
                    // Owner name on third line
                    g.setFont(new Font("Monospaced", Font.PLAIN, 11));
                    g.setColor(statusColor);
                    g.drawString(ownerName, z.x + 6, zy + 48);
                } else {
                    statusLabel = "UNCLAIMED";
                    statusColor = new Color(180, 180, 180);
                }
                g.setFont(new Font("Monospaced", Font.BOLD, 12));
                g.setColor(statusColor);
                g.drawString(statusLabel, z.x + 6, zy + 34);

                // Grace timer indicator
                if (z.graceExpiresMs > 0 && z.ownerPlayerId != null) {
                    long remaining = z.graceExpiresMs - System.currentTimeMillis();
                    if (remaining > 0) {
                        g.setFont(new Font("Monospaced", Font.PLAIN, 10));
                        g.setColor(new Color(255, 200, 0));
                        g.drawString(String.format("grace %.1fs", remaining / 1000.0),
                                     z.x + 6, zy + z.height - 14);
                    }
                }

                // Capture progress bar along bottom of zone
                if (z.captureProgress > 0 && z.captureProgress < 1.0) {
                    // Background track
                    g.setColor(new Color(0, 0, 0, 100));
                    g.fillRect(z.x, zy + z.height - 6, z.width, 6);
                    // Progress fill
                    g.setColor(new Color(255, 220, 0));
                    g.fillRect(z.x, zy + z.height - 6,
                               (int)(z.width * z.captureProgress), 6);
                }
            }
        }

        // ---- Items ----

        private void drawItems(Graphics2D g, GameStateSnapshot snap) {
            for (ItemInfo item : snap.items) {
                int ix = item.x;
                int iy = item.y + HUD_H;
                if (item.isWeapon) {
                    // Weapon: cyan circle with snowflake/ICE label
                    g.setColor(new Color(0, 220, 255, 200));
                    g.fillOval(ix - 11, iy - 11, 22, 22);
                    g.setColor(new Color(0, 100, 150));
                    g.drawOval(ix - 11, iy - 11, 22, 22);
                    g.setColor(Color.WHITE);
                    g.setFont(new Font("Monospaced", Font.BOLD, 9));
                    g.drawString("❄", ix - 5, iy + 4);
                } else if (item.isScoreSteal) {
                    // Score steal: purple circle with skull symbol
                    g.setColor(new Color(180, 60, 220, 200));
                    g.fillOval(ix - 11, iy - 11, 22, 22);
                    g.setColor(new Color(100, 0, 140));
                    g.drawOval(ix - 11, iy - 11, 22, 22);
                    g.setColor(Color.WHITE);
                    g.setFont(new Font("Monospaced", Font.BOLD, 11));
                    g.drawString("\u2620", ix - 6, iy + 5);
                } else if (item.isSpeedBoost) {
                    // Speed boost: green circle with arrow symbol
                    g.setColor(new Color(0, 255, 136, 200));
                    g.fillOval(ix - 11, iy - 11, 22, 22);
                    g.setColor(new Color(0, 160, 80));
                    g.drawOval(ix - 11, iy - 11, 22, 22);
                    g.setColor(new Color(0, 60, 30));
                    g.setFont(new Font("Monospaced", Font.BOLD, 11));
                    g.drawString("▶", ix - 6, iy + 5);
                } else {
                    // Energy coin: gold circle with $ symbol
                    g.setColor(new Color(255, 215, 0, 220));
                    g.fillOval(ix - 10, iy - 10, 20, 20);
                    g.setColor(new Color(180, 140, 0));
                    g.drawOval(ix - 10, iy - 10, 20, 20);
                    g.setColor(new Color(100, 70, 0));
                    g.setFont(new Font("Monospaced", Font.BOLD, 11));
                    g.drawString("★", ix - 6, iy + 5);
                }
            }
        }

        // ---- Players ----

        private void drawPlayers(Graphics2D g, GameStateSnapshot snap,
                                 Map<String, Color> playerColors) {
            for (PlayerInfo p : snap.players) {
                int px = p.x;
                int py = p.y + HUD_H;
                boolean isMe = p.id.equals(myPlayerId);
                Color c = playerColors.getOrDefault(p.id, Color.GRAY);

                // Frozen aura — bright blue glow
                if (p.frozen) {
                    g.setColor(new Color(100, 180, 255, 80));
                    g.fillOval(px - 20, py - 20, 40, 40);
                    g.setColor(new Color(100, 200, 255, 160));
                    g.fillOval(px - 16, py - 16, 32, 32);
                }

                // Player body circle
                g.setColor(isMe ? c.brighter() : c);
                g.fillOval(px - 12, py - 12, 24, 24);

                // Border: white for self, darker shade for others
                g.setColor(isMe ? Color.WHITE : c.darker());
                Stroke prev = g.getStroke();
                g.setStroke(new BasicStroke(isMe ? 2.5f : 1.5f));
                g.drawOval(px - 12, py - 12, 24, 24);
                g.setStroke(prev);

                // Weapon indicator: small cyan dot on top-right of circle
                if (p.hasWeapon) {
                    g.setColor(new Color(0, 220, 255));
                    g.fillOval(px + 4, py - 18, 10, 10);
                    g.setColor(Color.WHITE);
                    g.setFont(new Font("Monospaced", Font.BOLD, 7));
                    g.drawString("❄", px + 5, py - 10);
                }
                // Score steal indicator: small purple dot below freeze dot
                if (p.hasScoreSteal) {
                    g.setColor(new Color(180, 60, 220));
                    g.fillOval(px + 4, py - 6, 10, 10);
                    g.setColor(Color.WHITE);
                    g.setFont(new Font("Monospaced", Font.BOLD, 7));
                    g.drawString("\u2620", px + 4, py + 2);
                }

                // "FROZEN" label above frozen players
                if (p.frozen) {
                    g.setFont(new Font("Monospaced", Font.BOLD, 10));
                    g.setColor(new Color(100, 200, 255));
                    FontMetrics fm = g.getFontMetrics();
                    String frozenLabel = "FROZEN";
                    g.drawString(frozenLabel, px - fm.stringWidth(frozenLabel) / 2, py - 22);
                }

                // Name label (above player, or "YOU" for self)
                g.setFont(new Font("Monospaced", Font.BOLD, 11));
                g.setColor(Color.WHITE);
                String nameLabel = isMe ? "YOU" : p.name;
                FontMetrics nfm = g.getFontMetrics();
                int nameOffset = p.frozen ? 34 : 22;
                g.drawString(nameLabel, px - nfm.stringWidth(nameLabel) / 2, py - nameOffset);

                // Score below player
                g.setFont(new Font("Monospaced", Font.PLAIN, 10));
                g.setColor(new Color(200, 200, 200));
                String scoreStr = String.valueOf(p.score);
                FontMetrics sfm = g.getFontMetrics();
                g.drawString(scoreStr, px - sfm.stringWidth(scoreStr) / 2, py + 22);
            }
        }

        // ---- Notifications (TAGGED! etc.) ----

        private void drawNotifications(Graphics2D g) {
            long now = System.currentTimeMillis();
            // Purge expired
            notifications.removeIf(n -> now > n.expireMs);

            int ny = getHeight() / 2 - 20;
            for (Notification n : notifications) {
                long remaining = n.expireMs - now;
                float alpha = Math.min(1f, remaining / 400f); // fade out last 400ms
                Color c = new Color(n.color.getRed(), n.color.getGreen(),
                                    n.color.getBlue(), (int)(alpha * 220));

                g.setFont(new Font("Monospaced", Font.BOLD, 28));
                FontMetrics fm = g.getFontMetrics();
                int nx = (getWidth() - fm.stringWidth(n.text)) / 2;

                // Shadow
                g.setColor(new Color(0, 0, 0, (int)(alpha * 180)));
                g.drawString(n.text, nx + 2, ny + 2);

                g.setColor(c);
                g.drawString(n.text, nx, ny);
                ny += 38;
            }
        }
    }
}