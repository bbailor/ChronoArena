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
    //  Sprite loading
    // ------------------------------------------------------------------ //

    // One entry per colorIndex — must match PlayerRegistry.UNLOCK_THRESHOLDS order
    static final String[] SPRITE_NAMES = {
        "BlueGuy", "RedGuy", "YellowGuy", "GreenGuy",
        "OrangeGuy", "LightBlueGuy", "PurpleGuy", "BlackGuy"
    };
    // Fallback tint colors used if the PNG can't be loaded
    static final Color[] FALLBACK_COLORS = {
        new Color(91,  110, 225), new Color(172,  50,  50),
        new Color(251,  242,  54), new Color(24, 107,   24),
        new Color(223,  113, 38), new Color(95,   205, 228),
        new Color(118, 66,  138), new Color(0, 0,  0),
    };

    private final java.awt.image.BufferedImage[] sprites =
            new java.awt.image.BufferedImage[SPRITE_NAMES.length];

    private void loadSprites() {
        for (int i = 0; i < SPRITE_NAMES.length; i++) {
            try {
                sprites[i] = javax.imageio.ImageIO.read(
                        getClass().getResource("/" + SPRITE_NAMES[i] + ".png"));
            } catch (Exception e) {
                System.err.println("[CustomUI] Could not load " + SPRITE_NAMES[i]
                        + ".png — will use circle fallback.");
                sprites[i] = null;
            }
        }
    }

    java.awt.image.BufferedImage spriteFor(int colorIndex) {
        if (colorIndex >= 0 && colorIndex < sprites.length) return sprites[colorIndex];
        return null;
    }

    // ------------------------------------------------------------------ //
    //  Lobby
    // ------------------------------------------------------------------ //
    private Consumer<TcpMessage> lobbySender;
    private volatile LobbyState  currentLobbyState;
    private JFrame  lobbyFrame;
    private String  myLobbyPlayerId;
    private boolean iAmHost;

    // Unlock data fetched before connecting
    private java.util.List<Integer> myUnlockedColors = java.util.List.of(0);
    private int myWins = 0;

    private String enteredName = "Player";
    private String enteredIp   = null;

    @Override
    public String promptPlayerName(String defaultServerIp) {
        JTextField nameField = new JTextField("Player", 15);
        JTextField ipField   = new JTextField(
                defaultServerIp != null ? defaultServerIp : "127.0.0.1", 15);
        JPanel p1 = new JPanel(new GridLayout(2, 2, 6, 6));
        p1.add(new JLabel("Your name:")); p1.add(nameField);
        p1.add(new JLabel("Server IP:")); p1.add(ipField);

        int r = JOptionPane.showConfirmDialog(null, p1,
                "ChronoArena — Join", JOptionPane.OK_CANCEL_OPTION);
        if (r != JOptionPane.OK_OPTION) return null;

        enteredName = nameField.getText().trim();
        if (enteredName.isEmpty()) enteredName = "Player";
        enteredIp = ipField.getText().trim();

        // Fetch unlock profile from server
        fetchProfile(enteredName, enteredIp.isBlank() ? defaultServerIp : enteredIp);

        // Load sprites now that we're about to show the lobby
        loadSprites();

        return enteredName;
    }

    @Override
    public String getServerIpOverride() { return enteredIp; }

    private void fetchProfile(String name, String host) {
        if (host == null || host.isBlank()) host = "127.0.0.1";
        int port = shared.Config.getInt("server.tcp.port");
        try (java.net.Socket s = new java.net.Socket(host, port);
             java.io.ObjectOutputStream out2 =
                     new java.io.ObjectOutputStream(s.getOutputStream());
             java.io.ObjectInputStream in2 =
                     new java.io.ObjectInputStream(s.getInputStream())) {
            out2.flush();
            ProfileRequest req = new ProfileRequest();
            req.playerName = name;
            out2.writeObject(new TcpMessage(MsgType.PROFILE_REQUEST, req));
            out2.flush();
            TcpMessage resp = (TcpMessage) in2.readObject();
            if (resp.type == MsgType.PROFILE_RESPONSE) {
                ProfileResponse pr = (ProfileResponse) resp.payload;
                myWins           = pr.wins;
                myUnlockedColors = pr.unlockedColors != null
                        ? pr.unlockedColors : java.util.List.of(0);
            }
        } catch (Exception e) {
            System.err.println("[CustomUI] Profile fetch failed: " + e.getMessage()
                    + " — defaulting to color 0.");
        }
    }

    @Override
    public void onLobbyJoined(String myPlayerId, String playerName,
                               boolean isHost, LobbyState initialState,
                               Consumer<TcpMessage> messageSender) {
        this.myPlayerId      = myPlayerId;
        this.myLobbyPlayerId = myPlayerId;
        this.iAmHost         = isHost;
        this.lobbySender     = messageSender;
        this.currentLobbyState = initialState;
        SwingUtilities.invokeLater(this::buildLobbyFrame);
    }

    @Override
    public void onLobbyUpdate(LobbyState state) {
        this.currentLobbyState = state;
        SwingUtilities.invokeLater(this::refreshLobbyFrame);
    }

    // ------------------------------------------------------------------ //
    //  Lobby window
    // ------------------------------------------------------------------ //

    private JPanel lobbyPlayerListPanel;
    private JLabel lobbyWinsLabel;
    private JPanel lobbyColorPanel;
    private JButton startButton;

    private void buildLobbyFrame() {
        lobbyFrame = new JFrame("ChronoArena — Lobby");
        lobbyFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        lobbyFrame.setLayout(new BorderLayout(10, 10));
        lobbyFrame.getContentPane().setBackground(new Color(20, 20, 30));

        JLabel title = new JLabel("CHRONOARENA  —  LOBBY", SwingConstants.CENTER);
        title.setFont(new Font("Monospaced", Font.BOLD, 20));
        title.setForeground(new Color(255, 200, 0));
        title.setBorder(BorderFactory.createEmptyBorder(14, 0, 4, 0));
        lobbyFrame.add(title, BorderLayout.NORTH);

        JPanel center = new JPanel(new GridLayout(1, 2, 16, 0));
        center.setOpaque(false);
        center.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));

        // Left: player list
        JPanel leftPanel = new JPanel(new BorderLayout(0, 6));
        leftPanel.setOpaque(false);
        JLabel playersTitle = new JLabel("Players", SwingConstants.LEFT);
        playersTitle.setFont(new Font("Monospaced", Font.BOLD, 14));
        playersTitle.setForeground(Color.LIGHT_GRAY);
        leftPanel.add(playersTitle, BorderLayout.NORTH);
        lobbyPlayerListPanel = new JPanel();
        lobbyPlayerListPanel.setLayout(new BoxLayout(lobbyPlayerListPanel, BoxLayout.Y_AXIS));
        lobbyPlayerListPanel.setOpaque(false);
        leftPanel.add(lobbyPlayerListPanel, BorderLayout.CENTER);
        center.add(leftPanel);

        // Right: wins + color picker
        JPanel rightPanel = new JPanel(new BorderLayout(0, 6));
        rightPanel.setOpaque(false);
        lobbyWinsLabel = new JLabel("Wins: " + myWins, SwingConstants.LEFT);
        lobbyWinsLabel.setFont(new Font("Monospaced", Font.PLAIN, 13));
        lobbyWinsLabel.setForeground(new Color(180, 180, 220));
        rightPanel.add(lobbyWinsLabel, BorderLayout.NORTH);
        JLabel colorTitle = new JLabel("Choose your skin:");
        colorTitle.setFont(new Font("Monospaced", Font.BOLD, 14));
        colorTitle.setForeground(Color.LIGHT_GRAY);
        rightPanel.add(colorTitle, BorderLayout.CENTER);
        lobbyColorPanel = buildColorPickerPanel();
        rightPanel.add(lobbyColorPanel, BorderLayout.SOUTH);
        center.add(rightPanel);

        lobbyFrame.add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 8));
        bottom.setOpaque(false);
        startButton = new JButton(iAmHost ? "▶  Start Game" : "Waiting for host…");
        startButton.setFont(new Font("Monospaced", Font.BOLD, 15));
        startButton.setEnabled(iAmHost);
        startButton.setBackground(new Color(50, 180, 80));
        startButton.setForeground(Color.WHITE);
        startButton.setBorderPainted(false);
        startButton.setFocusPainted(false);
        startButton.setPreferredSize(new Dimension(220, 40));
        startButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        startButton.addActionListener(e ->
                lobbySender.accept(new TcpMessage(MsgType.LOBBY_START, null)));
        bottom.add(startButton);
        lobbyFrame.add(bottom, BorderLayout.SOUTH);

        refreshLobbyPlayerList();
        lobbyFrame.setSize(640, 460);
        lobbyFrame.setLocationRelativeTo(null);
        lobbyFrame.setVisible(true);
    }

    /** 2×4 grid of skin swatches showing sprite thumbnails. */
    private JPanel buildColorPickerPanel() {
        JPanel grid = new JPanel(new GridLayout(2, 4, 8, 8));
        grid.setOpaque(false);

        int currentColor = 0;
        if (currentLobbyState != null) {
            for (LobbyPlayerInfo lpi : currentLobbyState.players) {
                if (lpi.id.equals(myLobbyPlayerId)) { currentColor = lpi.colorIndex; break; }
            }
        }

        Set<Integer> takenByOthers = new HashSet<>();
        if (currentLobbyState != null) {
            for (LobbyPlayerInfo lpi : currentLobbyState.players) {
                if (!lpi.id.equals(myLobbyPlayerId)) takenByOthers.add(lpi.colorIndex);
            }
        }

        for (int i = 0; i < SPRITE_NAMES.length; i++) {
            final int idx = i;
            boolean unlocked = myUnlockedColors.contains(i);
            boolean taken    = takenByOthers.contains(i);

            JButton btn = new JButton();
            btn.setPreferredSize(new Dimension(90, 80));
            btn.setLayout(new BorderLayout(2, 2));
            btn.setFocusPainted(false);
            btn.setBackground(unlocked && !taken ? new Color(35, 35, 55) : new Color(20, 20, 30));

            // Sprite thumbnail
            java.awt.image.BufferedImage img = sprites[i];
            Icon icon;
            if (img != null) {
                icon = new ImageIcon(img.getScaledInstance(44, 44, Image.SCALE_SMOOTH));
            } else {
                // Colored square fallback
                java.awt.image.BufferedImage sq =
                        new java.awt.image.BufferedImage(44, 44,
                                java.awt.image.BufferedImage.TYPE_INT_ARGB);
                Graphics2D sg = sq.createGraphics();
                sg.setColor(unlocked ? FALLBACK_COLORS[i] : FALLBACK_COLORS[i].darker().darker());
                sg.fillRoundRect(4, 4, 36, 36, 8, 8);
                sg.dispose();
                icon = new ImageIcon(sq);
            }
            // If locked or taken, dim the icon
            if (!unlocked || taken) {
                java.awt.image.BufferedImage dim =
                        new java.awt.image.BufferedImage(44, 44,
                                java.awt.image.BufferedImage.TYPE_INT_ARGB);
                Graphics2D dg = dim.createGraphics();
                dg.drawImage(((ImageIcon) icon).getImage(), 0, 0, 44, 44, null);
                dg.setColor(new Color(0, 0, 0, 140));
                dg.fillRect(0, 0, 44, 44);
                dg.dispose();
                icon = new ImageIcon(dim);
            }
            btn.setIcon(icon);
            btn.setHorizontalTextPosition(SwingConstants.CENTER);
            btn.setVerticalTextPosition(SwingConstants.BOTTOM);

            String labelText = SPRITE_NAMES[i].replace("Guy", "");
            if (!unlocked) {
                labelText = server.PlayerRegistry.UNLOCK_THRESHOLDS[i] + " wins";
            } else if (taken) {
                labelText = "taken";
            }
            btn.setText("<html><center><font size='1'>" + labelText + "</font></center></html>");
            btn.setForeground(unlocked && !taken ? Color.WHITE : new Color(110, 110, 120));

            // Highlight currently selected with gold border
            if (i == currentColor) {
                btn.setBorder(BorderFactory.createLineBorder(new Color(255, 200, 0), 3));
            } else {
                btn.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 80), 1));
            }

            btn.setEnabled(unlocked && !taken);
            btn.addActionListener(e ->
                    lobbySender.accept(new TcpMessage(MsgType.LOBBY_COLOR_CHANGE, idx)));
            grid.add(btn);
        }
        return grid;
    }

    private void refreshLobbyFrame() {
        if (lobbyFrame == null || currentLobbyState == null) return;
        refreshLobbyPlayerList();
        rebuildLobbyColorPanel();
        if (startButton != null) {
            boolean nowHost = currentLobbyState.hostPlayerId != null
                    && currentLobbyState.hostPlayerId.equals(myLobbyPlayerId);
            iAmHost = nowHost;
            startButton.setEnabled(nowHost);
            startButton.setText(nowHost ? "▶  Start Game" : "Waiting for host…");
        }
        lobbyFrame.repaint();
    }

    private void refreshLobbyPlayerList() {
        if (lobbyPlayerListPanel == null || currentLobbyState == null) return;
        lobbyPlayerListPanel.removeAll();
        for (LobbyPlayerInfo lpi : currentLobbyState.players) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            row.setOpaque(false);

            java.awt.image.BufferedImage img = spriteFor(lpi.colorIndex);
            if (img != null) {
                row.add(new JLabel(new ImageIcon(
                        img.getScaledInstance(24, 24, Image.SCALE_SMOOTH))));
            } else {
                JLabel dot = new JLabel("●");
                dot.setForeground(FALLBACK_COLORS[lpi.colorIndex % FALLBACK_COLORS.length]);
                row.add(dot);
            }

            String skinName = (lpi.colorIndex >= 0 && lpi.colorIndex < SPRITE_NAMES.length)
                    ? SPRITE_NAMES[lpi.colorIndex].replace("Guy", "") : "?";
            JLabel nameLbl = new JLabel(
                    lpi.name + (lpi.isHost ? " [HOST]" : "") + "  (" + skinName + ")");
            nameLbl.setFont(new Font("Monospaced", Font.PLAIN, 13));
            nameLbl.setForeground(lpi.id.equals(myLobbyPlayerId)
                    ? new Color(255, 220, 80) : Color.LIGHT_GRAY);
            row.add(nameLbl);
            lobbyPlayerListPanel.add(row);
        }
        lobbyPlayerListPanel.revalidate();
        lobbyPlayerListPanel.repaint();
    }

    /** Swap out just the color picker panel in the right column. */
    private void rebuildLobbyColorPanel() {
        if (lobbyColorPanel == null) return;
        Container parent = lobbyColorPanel.getParent();
        if (parent == null) return;
        parent.remove(lobbyColorPanel);
        lobbyColorPanel = buildColorPickerPanel();
        parent.add(lobbyColorPanel, BorderLayout.SOUTH);
        parent.revalidate();
        parent.repaint();
    }

    // ------------------------------------------------------------------ //
    //  Game start — close lobby, open arena
    // ------------------------------------------------------------------ //

    @Override
    public void onGameStart(String myPlayerId, String playerName) {
        this.myPlayerId = myPlayerId;
        SwingUtilities.invokeLater(() -> {
            if (lobbyFrame != null) { lobbyFrame.dispose(); lobbyFrame = null; }

            frame = new JFrame("ChronoArena — " + playerName);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            arenaPanel = new ArenaPanel();
            frame.add(arenaPanel, BorderLayout.CENTER);

            frame.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    heldKeys.add(e.getKeyCode());
                    if (e.getKeyCode() == KeyEvent.VK_SPACE)
                        pendingAction.set(ActionType.FREEZE_RAY);
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) System.exit(0);
                }
                @Override public void keyReleased(KeyEvent e) { heldKeys.remove(e.getKeyCode()); }
            });

            frame.setFocusable(true);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            frame.requestFocus();
        });
    }

    // ------------------------------------------------------------------ //
    //  Arena Panel
    // ------------------------------------------------------------------ //

    class ArenaPanel extends JPanel {

        static final int ARENA_W    = 800;
        static final int ARENA_H    = 600;
        static final int HUD_H      = 60;
        static final int SPRITE_SIZE = 32;

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
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Map<String, String> nameMap = new HashMap<>();
            for (PlayerInfo p : snap.players) nameMap.put(p.id, p.name);

            drawHUD(g2, snap, nameMap);
            drawZones(g2, snap, nameMap);
            drawItems(g2, snap);
            drawPlayers(g2, snap);
            drawNotifications(g2);
        }

        // ── HUD ──────────────────────────────────────────────────────

        private void drawHUD(Graphics2D g, GameStateSnapshot snap,
                             Map<String, String> nameMap) {
            g.setColor(new Color(15, 15, 15));
            g.fillRect(0, 0, getWidth(), HUD_H);
            g.setColor(new Color(60, 60, 60));
            g.drawLine(0, HUD_H - 1, getWidth(), HUD_H - 1);

            g.setFont(new Font("Monospaced", Font.BOLD, 16));
            g.setColor(new Color(255, 200, 0));
            g.drawString("CHRONOARENA", 10, 22);

            long sec = snap.roundTimeRemainingMs / 1000;
            String timeStr = String.format("TIME  %02d:%02d", sec / 60, sec % 60);
            g.setFont(new Font("Monospaced", Font.BOLD, 20));
            g.setColor(sec <= 30 ? Color.RED : Color.WHITE);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(timeStr, (getWidth() - fm.stringWidth(timeStr)) / 2, 38);

            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(snap.scores.entrySet());
            sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
            int scoreX = getWidth() - 10;
            g.setFont(new Font("Monospaced", Font.BOLD, 13));
            for (Map.Entry<String, Integer> e : sorted) {
                int ci = snap.players.stream().filter(p -> p.id.equals(e.getKey()))
                        .mapToInt(p -> p.colorIndex).findFirst().orElse(0);
                String label = String.format("%s: %d",
                        nameMap.getOrDefault(e.getKey(), e.getKey()), e.getValue());
                FontMetrics sfm = g.getFontMetrics();
                scoreX -= sfm.stringWidth(label) + 28;
                Color pc = FALLBACK_COLORS[ci % FALLBACK_COLORS.length];
                g.setColor(e.getKey().equals(myPlayerId) ? pc : pc.darker());
                g.fillRoundRect(scoreX - 4, 8, sfm.stringWidth(label) + 8, 22, 6, 6);
                java.awt.image.BufferedImage icon = sprites[ci];
                if (icon != null) g.drawImage(icon, scoreX - 22, 10, 18, 18, null);
                g.setColor(e.getKey().equals(myPlayerId) ? Color.WHITE : new Color(220, 220, 220));
                g.drawString(label, scoreX, 24);
            }

            g.setFont(new Font("Monospaced", Font.PLAIN, 10));
            g.setColor(new Color(120, 120, 120));
            g.drawString("[ESC] quit  [WASD] move  [SPACE] freeze", 10, HUD_H - 8);
        }

        // ── Zones ─────────────────────────────────────────────────────

        private void drawZones(Graphics2D g, GameStateSnapshot snap,
                               Map<String, String> nameMap) {
            for (ZoneInfo z : snap.zones) {
                int zy = z.y + HUD_H;
                int ownerCi = snap.players.stream()
                        .filter(p -> p.id.equals(z.ownerPlayerId))
                        .mapToInt(p -> p.colorIndex).findFirst().orElse(-1);
                Color pc = ownerCi >= 0 ? FALLBACK_COLORS[ownerCi % FALLBACK_COLORS.length] : null;

                g.setColor(z.contested ? new Color(200, 100, 0, 90)
                        : pc != null ? new Color(pc.getRed(), pc.getGreen(), pc.getBlue(), 60)
                        : new Color(80, 80, 80, 60));
                g.fillRect(z.x, zy, z.width, z.height);

                g.setColor(z.contested ? Color.ORANGE : (pc != null ? pc : new Color(150, 150, 150)));
                Stroke prev = g.getStroke();
                g.setStroke(new BasicStroke(z.contested ? 2.5f : 1.5f));
                g.drawRect(z.x, zy, z.width, z.height);
                g.setStroke(prev);

                g.setFont(new Font("Monospaced", Font.BOLD, 13));
                g.setColor(Color.WHITE);
                g.drawString("ZONE " + z.id, z.x + 6, zy + 18);

                String statusLabel; Color statusColor;
                if (z.contested) {
                    statusLabel = "CONTESTED"; statusColor = Color.ORANGE;
                } else if (z.ownerPlayerId != null) {
                    statusLabel = "CONTROLLED";
                    statusColor = pc != null ? pc.brighter() : Color.GREEN;
                    g.setFont(new Font("Monospaced", Font.PLAIN, 11));
                    g.setColor(statusColor);
                    g.drawString(nameMap.getOrDefault(z.ownerPlayerId, z.ownerPlayerId),
                                 z.x + 6, zy + 48);
                } else {
                    statusLabel = "UNCLAIMED"; statusColor = new Color(180, 180, 180);
                }
                g.setFont(new Font("Monospaced", Font.BOLD, 12));
                g.setColor(statusColor);
                g.drawString(statusLabel, z.x + 6, zy + 34);

                if (z.graceExpiresMs > 0 && z.ownerPlayerId != null) {
                    long rem = z.graceExpiresMs - System.currentTimeMillis();
                    if (rem > 0) {
                        g.setFont(new Font("Monospaced", Font.PLAIN, 10));
                        g.setColor(new Color(255, 200, 0));
                        g.drawString(String.format("grace %.1fs", rem / 1000.0),
                                     z.x + 6, zy + z.height - 14);
                    }
                }
                if (z.captureProgress > 0 && z.captureProgress < 1.0) {
                    g.setColor(new Color(0, 0, 0, 100));
                    g.fillRect(z.x, zy + z.height - 6, z.width, 6);
                    g.setColor(new Color(255, 220, 0));
                    g.fillRect(z.x, zy + z.height - 6, (int)(z.width * z.captureProgress), 6);
                }
            }
        }

        // ── Items ─────────────────────────────────────────────────────

        private void drawItems(Graphics2D g, GameStateSnapshot snap) {
            for (ItemInfo item : snap.items) {
                int ix = item.x, iy = item.y + HUD_H;
                if (item.isWeapon) {
                    g.setColor(new Color(0, 220, 255, 200)); g.fillOval(ix-11,iy-11,22,22);
                    g.setColor(new Color(0,100,150));        g.drawOval(ix-11,iy-11,22,22);
                    g.setColor(Color.WHITE); g.setFont(new Font("Monospaced",Font.BOLD,9));
                    g.drawString("❄", ix-5, iy+4);
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
                    g.setColor(new Color(0,255,136,200)); g.fillOval(ix-11,iy-11,22,22);
                    g.setColor(new Color(0,160,80));      g.drawOval(ix-11,iy-11,22,22);
                    g.setColor(new Color(0,60,30)); g.setFont(new Font("Monospaced",Font.BOLD,11));
                    g.drawString("▶", ix-6, iy+5);
                } else {
                    g.setColor(new Color(255,215,0,220)); g.fillOval(ix-10,iy-10,20,20);
                    g.setColor(new Color(180,140,0));     g.drawOval(ix-10,iy-10,20,20);
                    g.setColor(new Color(100,70,0)); g.setFont(new Font("Monospaced",Font.BOLD,11));
                    g.drawString("★", ix-6, iy+5);
                }
            }
        }

        // ── Players — drawn as ___Guy.png sprites ─────────────────────

        private void drawPlayers(Graphics2D g, GameStateSnapshot snap) {
            for (PlayerInfo p : snap.players) {
                int px = p.x, py = p.y + HUD_H;
                boolean isMe = p.id.equals(myPlayerId);
                int half = SPRITE_SIZE / 2;

                // Frozen aura
                if (p.frozen) {
                    g.setColor(new Color(100, 180, 255, 80));
                    g.fillOval(px-half-6, py-half-6, SPRITE_SIZE+12, SPRITE_SIZE+12);
                    Stroke prev = g.getStroke();
                    g.setColor(new Color(100, 200, 255, 160));
                    g.setStroke(new BasicStroke(2f));
                    g.drawOval(px-half-6, py-half-6, SPRITE_SIZE+12, SPRITE_SIZE+12);
                    g.setStroke(prev);
                }

                // Speed boost glow
                if (p.speedBoosted) {
                    g.setColor(new Color(0, 255, 100, 60));
                    g.fillOval(px-half-4, py-half-4, SPRITE_SIZE+8, SPRITE_SIZE+8);
                }

                // Sprite (or fallback circle)
                java.awt.image.BufferedImage img = spriteFor(p.colorIndex);
                if (img != null) {
                    g.drawImage(img, px-half, py-half, SPRITE_SIZE, SPRITE_SIZE, null);
                    if (p.frozen) {
                        // Blue tint overlay
                        g.setColor(new Color(100, 180, 255, 80));
                        g.fillRect(px-half, py-half, SPRITE_SIZE, SPRITE_SIZE);
                    }
                } else {
                    Color c = p.frozen ? new Color(100,180,255)
                            : FALLBACK_COLORS[p.colorIndex % FALLBACK_COLORS.length];
                    g.setColor(isMe ? c.brighter() : c);
                    g.fillOval(px-half, py-half, SPRITE_SIZE, SPRITE_SIZE);
                    Stroke prev = g.getStroke();
                    g.setColor(isMe ? Color.WHITE : c.darker());
                    g.setStroke(new BasicStroke(isMe ? 2.5f : 1.5f));
                    g.drawOval(px-half, py-half, SPRITE_SIZE, SPRITE_SIZE);
                    g.setStroke(prev);
                }

                // Gold ring for "YOU"
                if (isMe) {
                    Stroke prev = g.getStroke();
                    g.setColor(new Color(255, 220, 0, 200));
                    g.setStroke(new BasicStroke(2.5f));
                    g.drawOval(px-half-3, py-half-3, SPRITE_SIZE+6, SPRITE_SIZE+6);
                    g.setStroke(prev);
                }

                // Weapon dot
                if (p.hasWeapon) {
                    g.setColor(new Color(0, 220, 255));
                    g.fillOval(px+half-5, py-half-5, 10, 10);
                    g.setColor(Color.WHITE);
                    g.setFont(new Font("Monospaced", Font.BOLD, 7));
                    g.drawString("❄", px+half-4, py-half+3);
                }
                // Score steal indicator: small purple dot below freeze dot
                if (p.hasScoreSteal) {
                    g.setColor(new Color(180, 60, 220));
                    g.fillOval(px + 4, py - 6, 10, 10);
                    g.setColor(Color.WHITE);
                    g.setFont(new Font("Monospaced", Font.BOLD, 7));
                    g.drawString("\u2620", px + 4, py + 2);
                }

                // FROZEN label
                if (p.frozen) {
                    g.setFont(new Font("Monospaced", Font.BOLD, 10));
                    g.setColor(new Color(100, 200, 255));
                    FontMetrics fm = g.getFontMetrics();
                    String fl = "FROZEN";
                    g.drawString(fl, px - fm.stringWidth(fl)/2, py-half-10);
                }

                // Name tag above sprite
                g.setFont(new Font("Monospaced", Font.BOLD, 11));
                g.setColor(Color.WHITE);
                String nameLabel = isMe ? "YOU" : p.name;
                FontMetrics nfm = g.getFontMetrics();
                int nameOffset = p.frozen ? half+22 : half+12;
                g.drawString(nameLabel, px - nfm.stringWidth(nameLabel)/2, py - nameOffset);

                // Score below sprite
                g.setFont(new Font("Monospaced", Font.PLAIN, 10));
                g.setColor(new Color(200, 200, 200));
                String sc = String.valueOf(p.score);
                FontMetrics sfm = g.getFontMetrics();
                g.drawString(sc, px - sfm.stringWidth(sc)/2, py + half + 12);
            }
        }

        // ── Notifications ─────────────────────────────────────────────

        private void drawNotifications(Graphics2D g) {
            long now = System.currentTimeMillis();
            notifications.removeIf(n -> now > n.expireMs);
            int ny = getHeight() / 2 - 20;
            for (Notification n : notifications) {
                long remaining = n.expireMs - now;
                float alpha = Math.min(1f, remaining / 400f);
                Color c = new Color(n.color.getRed(), n.color.getGreen(),
                                    n.color.getBlue(), (int)(alpha * 220));
                g.setFont(new Font("Monospaced", Font.BOLD, 28));
                FontMetrics fm = g.getFontMetrics();
                int nx = (getWidth() - fm.stringWidth(n.text)) / 2;
                g.setColor(new Color(0, 0, 0, (int)(alpha * 180)));
                g.drawString(n.text, nx+2, ny+2);
                g.setColor(c);
                g.drawString(n.text, nx, ny);
                ny += 38;
            }
        }
    }
}