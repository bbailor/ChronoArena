package gui;

import shared.Messages.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * SwingUI — Full 2-D Swing implementation of GameUI for ChronoArena.
 *
 * DROP-IN REPLACEMENT:  In Client.java change one line:
 *     GameUI ui = new SwingUI();
 *
 * Controls (keyboard):
 *   W / ↑           move up
 *   S / ↓           move down
 *   A / ←           move left
 *   D / →           move right
 *   Q/E/Z/C         diagonal (UL/UR/DL/DR)
 *   SPACE / F       fire freeze-ray
 *   ESC             quit
 */
public class SwingUI implements GameUI {

    // ── Arena constants (must match GameLoop / GameState) ─────────────
    private static final int ARENA_W  = 800;
    private static final int ARENA_H  = 600;
    private static final int PLAYER_R = 14;
    private static final int ITEM_R   = 9;

    // ── Colours ────────────────────────────────────────────────────────
    private static final Color BG_DARK      = new Color(0x0d0d1a);
    private static final Color BG_MID       = new Color(0x13132a);
    private static final Color BG_PANEL     = new Color(0x1a1a33);
    private static final Color GRID_COL     = new Color(0x1a1a33);
    private static final Color ACCENT       = new Color(0x00e5ff);
    private static final Color ACCENT2      = new Color(0xff6b35);
    private static final Color ZONE_FREE    = new Color(0x2a2a4a);
    private static final Color ZONE_BORDER  = new Color(0x44448a);
    private static final Color CONTESTED    = new Color(0xff4444, true);
    private static final Color CAPTURE_CLR  = new Color(0x00e5ff, true);
    private static final Color WEAPON_CLR   = new Color(0xff6b35);
    private static final Color SCORE_STEAL_CLR = new Color(0xce93d8);
    private static final Color ENERGY_CLR   = new Color(0xffd700);
    private static final Color SPEED_CLR    = new Color(0x00ff88);
    private static final Color FROZEN_CLR   = new Color(0x88ccff);
    private static final Color TEXT_MAIN    = new Color(0xe8e8ff);
    private static final Color TEXT_DIM     = new Color(0x888aaa);
    private static final Color BORDER_DIM   = new Color(0x33335a);
    private static final Color HOST_BADGE   = new Color(0xffd700);

    // Player palette (up to 8 players)
    private static final Color[] PLAYER_COLORS = {
        new Color(0x4fc3f7), new Color(0xef5350), new Color(0x66bb6a),
        new Color(0xffa726), new Color(0xce93d8), new Color(0x26c6da),
        new Color(0xd4e157), new Color(0xff7043)
    };

    // ── Swing components (shared across lobby and game) ────────────────
    private JFrame     frame;
    private CardLayout cardLayout;
    private JPanel     cardPanel;

    // ── Lobby components ───────────────────────────────────────────────
    private JPanel     playerListPanel;
    private JLabel     playerCountLabel;
    private JLabel     lobbyStatusLabel;
    private JButton    startButton;
    private JPanel     configPanel;
    // Config spinners (indexed to match CONFIG_FIELDS order)
    private JSpinner[] configSpinners;

    // ── Lobby state ────────────────────────────────────────────────────
    private volatile boolean          isHost;
    private volatile LobbyState       currentLobbyState;
    private Consumer<TcpMessage>      lobbySender;
    // Debounce: only send config update after spinner settles
    private javax.swing.Timer         configDebounce;

    // ── Game-phase components ──────────────────────────────────────────
    private ArenaPanel arenaPanel;
    private JLabel     timerLabel, titleLabel;
    private JPanel     scoreRow1, scoreRow2; // two-row scoreboard (row2 appears with 5+ players)

    // ── Game-phase state ───────────────────────────────────────────────
    private volatile GameStateSnapshot currentSnap;
    private volatile String            myPlayerId;
    private volatile String            myPlayerName;
    private final Map<String, Integer> colorMap = new LinkedHashMap<>();
    private int colorIndex = 0;

    // ── Input ──────────────────────────────────────────────────────────
    private final Set<Integer> keysDown = Collections.synchronizedSet(new HashSet<>());
    private final AtomicReference<ActionType> pendingAction =
            new AtomicReference<>(ActionType.NONE);

    // ── Pre-lobby results ─────────────────────────────────────────────
    private String enteredName;
    private String enteredIp;

    // ── Notifications ─────────────────────────────────────────────────
    private volatile String notification     = null;
    private volatile long   notifyExpiresMs  = 0;
    private volatile boolean wasFrozen       = false;
    private volatile boolean wasSpeedBoosted = false;

    // ── Sprites ───────────────────────────────────────────────────────
    private BufferedImage blueSprite;
    private BufferedImage redSprite;

    // ── Config field descriptors ───────────────────────────────────────
    private static final Object[][] CONFIG_FIELDS = {
        // { label,              min,  max,  step,  getter/setter index }
        { "Game Duration (s)",   30,   600,   30  },  // 0 roundDurationSeconds
        { "Max Players",          2,     8,    1  },  // 1 maxPlayers
        { "Zone Pts / Tick",      1,    10,    1  },  // 2 pointsPerZoneTick
        { "Freeze Duration (s)",  1,    10,    1  },  // 3 freezeDurationSeconds
        { "Capture Time (s)",     1,    10,    1  },  // 4 zoneCaptureTimeSeconds
        { "Item Spawn (s)",       3,    30,    1  },  // 5 itemSpawnIntervalSeconds
        { "Tag Penalty",          0,    50,    5  },  // 6 tagPenaltyPoints
        { "Speed Boost (s)",      1,    15,    1  },  // 7 speedBoostDurationSeconds
    };

    // ═══════════════════════════════════════════════════════════════════
    //  GameUI — Pre-lobby
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public String promptPlayerName(String defaultServerIp) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(BG_DARK);
        panel.setBorder(BorderFactory.createEmptyBorder(20, 30, 20, 30));

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 6, 6, 6);
        gc.fill   = GridBagConstraints.HORIZONTAL;

        JLabel titleLbl = styled("CHRONOARENA", 24, Font.BOLD, ACCENT);
        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2;
        panel.add(titleLbl, gc);

        gc.gridwidth = 1; gc.gridy = 1;
        gc.gridx = 0; panel.add(styled("Your name:", 13, Font.PLAIN, TEXT_MAIN), gc);
        JTextField nameField = darkField(16);
        nameField.setText("Player");
        gc.gridx = 1; panel.add(nameField, gc);

        gc.gridy = 2;
        gc.gridx = 0; panel.add(styled("Server IP:", 13, Font.PLAIN, TEXT_MAIN), gc);
        JTextField ipField = darkField(16);
        ipField.setText(defaultServerIp != null ? defaultServerIp : "127.0.0.1");
        gc.gridx = 1; panel.add(ipField, gc);

        gc.gridy = 3; gc.gridx = 0; gc.gridwidth = 2;
        panel.add(styled("Controls:  W/A/S/D or Arrows  |  SPACE / F = Freeze", 11, Font.ITALIC, TEXT_DIM), gc);

        UIManager.put("OptionPane.background",        BG_DARK);
        UIManager.put("Panel.background",             BG_DARK);
        UIManager.put("OptionPane.messageForeground", TEXT_MAIN);

        int result = JOptionPane.showConfirmDialog(
            null, panel, "Join ChronoArena",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) System.exit(0);

        enteredName = nameField.getText().trim();
        if (enteredName.isEmpty()) enteredName = "Player";

        String ip = ipField.getText().trim();
        enteredIp = (ip.isEmpty() || ip.equals(defaultServerIp)) ? null : ip;

        return enteredName;
    }

    @Override
    public String getServerIpOverride() { return enteredIp; }

    // ═══════════════════════════════════════════════════════════════════
    //  GameUI — Lobby
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void onLobbyJoined(String myPlayerId, String playerName,
                               boolean isHost, LobbyState initialState,
                               Consumer<TcpMessage> messageSender) {
        this.myPlayerId       = myPlayerId;
        this.myPlayerName     = playerName;
        this.isHost           = isHost;
        this.currentLobbyState = initialState;
        this.lobbySender      = messageSender;

        SwingUtilities.invokeLater(() -> {
            loadSprites();
            buildMainFrame();
            refreshLobbyDisplay(initialState);
            frame.setVisible(true);
        });
    }

    @Override
    public void onLobbyUpdate(LobbyState state) {
        currentLobbyState = state;
        SwingUtilities.invokeLater(() -> refreshLobbyDisplay(state));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  GameUI — Game lifecycle
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void onGameStart(String myPlayerId, String playerName) {
        this.myPlayerId   = myPlayerId;
        this.myPlayerName = playerName;

        SwingUtilities.invokeLater(() -> {
            cardLayout.show(cardPanel, "GAME");
            frame.setTitle("ChronoArena");
            startRenderLoop();
        });
    }

    @Override
    public void onStateUpdate(GameStateSnapshot snapshot) {
        if (snapshot.players != null) {
            for (PlayerInfo p : snapshot.players) {
                // Use server-authoritative color; fall back to auto-assign only if missing
                if (p.colorIndex >= 0 && p.colorIndex < PLAYER_COLORS.length)
                    colorMap.put(p.id, p.colorIndex);
                else
                    colorMap.computeIfAbsent(p.id, k -> colorIndex++ % PLAYER_COLORS.length);
            }
        }
        currentSnap = snapshot;

        if (snapshot.players != null) {
            for (PlayerInfo p : snapshot.players) {
                if (p.id.equals(myPlayerId)) {
                    if (p.frozen && !wasFrozen)
                        showNotification("TAGGED!  \u221210 PTS", 2500);
                    wasFrozen = p.frozen;
                    if (p.speedBoosted && !wasSpeedBoosted)
                        showNotification("SPEED BOOST!", 1800);
                    wasSpeedBoosted = p.speedBoosted;
                    break;
                }
            }
        }
    }

    @Override
    public void onRoundEnd(String winnerId, Map<String, Integer> scores,
                            List<PlayerInfo> players) {
        SwingUtilities.invokeLater(() -> showEndScreen(winnerId, scores, players));
    }

    @Override
    public void onKilled() {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(frame,
                "You were removed by the server.", "Kicked", JOptionPane.WARNING_MESSAGE);
            System.exit(0);
        });
    }

    @Override
    public void onDisconnected() {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(frame,
                "Lost connection to the server.", "Disconnected", JOptionPane.ERROR_MESSAGE);
            System.exit(0);
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    //  GameUI — Input
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public ActionType getNextAction() {
        boolean up    = keysDown.contains(KeyEvent.VK_W) || keysDown.contains(KeyEvent.VK_UP);
        boolean down  = keysDown.contains(KeyEvent.VK_S) || keysDown.contains(KeyEvent.VK_DOWN);
        boolean left  = keysDown.contains(KeyEvent.VK_A) || keysDown.contains(KeyEvent.VK_LEFT);
        boolean right = keysDown.contains(KeyEvent.VK_D) || keysDown.contains(KeyEvent.VK_RIGHT);

        ActionType move = ActionType.NONE;
        if      (up && left)    move = ActionType.MOVE_UL;
        else if (up && right)   move = ActionType.MOVE_UR;
        else if (down && left)  move = ActionType.MOVE_DL;
        else if (down && right) move = ActionType.MOVE_DR;
        else if (up)            move = ActionType.MOVE_UP;
        else if (down)          move = ActionType.MOVE_DOWN;
        else if (left)          move = ActionType.MOVE_LEFT;
        else if (right)         move = ActionType.MOVE_RIGHT;

        ActionType fire = pendingAction.getAndSet(ActionType.NONE);
        if (fire == ActionType.FREEZE_RAY) return ActionType.FREEZE_RAY;
        return move;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Frame construction (shared shell used by both lobby and game)
    // ═══════════════════════════════════════════════════════════════════

    private void buildMainFrame() {
        frame = new JFrame("ChronoArena — Lobby");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().setBackground(BG_DARK);

        cardLayout = new CardLayout();
        cardPanel  = new JPanel(cardLayout);
        cardPanel.setBackground(BG_DARK);

        cardPanel.add(buildLobbyPanel(), "LOBBY");
        cardPanel.add(buildGamePanel(),  "GAME");

        cardLayout.show(cardPanel, "LOBBY");
        frame.add(cardPanel);

        // Global key bindings (active for both lobby and game)
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(e -> {
                if (e.getID() == KeyEvent.KEY_PRESSED) {
                    keysDown.add(e.getKeyCode());
                    if (e.getKeyCode() == KeyEvent.VK_SPACE ||
                        e.getKeyCode() == KeyEvent.VK_F) {
                        pendingAction.set(ActionType.FREEZE_RAY);
                    }
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) System.exit(0);
                } else if (e.getID() == KeyEvent.KEY_RELEASED) {
                    keysDown.remove(e.getKeyCode());
                }
                return false;
            });

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setResizable(false);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Lobby panel
    // ═══════════════════════════════════════════════════════════════════

    private JPanel buildLobbyPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(BG_DARK);

        // ── Header ───────────────────────────────────────────────────
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(0x08081a));
        header.setBorder(BorderFactory.createEmptyBorder(14, 24, 14, 24));

        JLabel title = styled("CHRONOARENA", 22, Font.BOLD, ACCENT);
        header.add(title, BorderLayout.WEST);

        JLabel lobbyTag = styled("LOBBY", 16, Font.BOLD, TEXT_DIM);
        header.add(lobbyTag, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);

        // ── Content area (player list + config side by side) ──────────
        JPanel content = new JPanel(new GridBagLayout());
        content.setBackground(BG_DARK);
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        GridBagConstraints gc = new GridBagConstraints();
        gc.fill   = GridBagConstraints.BOTH;
        gc.insets = new Insets(0, 0, 0, 12);
        gc.gridy  = 0;

        // Left: player list
        gc.gridx = 0; gc.weightx = 0.35; gc.weighty = 1.0;
        content.add(buildPlayerListPanel(), gc);

        // Right: config + start button
        gc.gridx = 1; gc.weightx = 0.65; gc.insets = new Insets(0, 0, 0, 0);
        content.add(buildConfigAndStartPanel(), gc);

        root.add(content, BorderLayout.CENTER);

        // ── Footer ────────────────────────────────────────────────────
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(new Color(0x08081a));
        footer.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));

        JLabel hint = styled(
            "Waiting for host to configure settings and start the game  |  ESC = Quit",
            11, Font.ITALIC, TEXT_DIM);
        footer.add(hint, BorderLayout.CENTER);

        JButton helpBtn = new JButton("? HOW TO PLAY");
        helpBtn.setFont(new Font("Monospaced", Font.BOLD, 11));
        helpBtn.setForeground(ACCENT);
        helpBtn.setBackground(new Color(0x08081a));
        helpBtn.setBorder(BorderFactory.createLineBorder(ACCENT, 1));
        helpBtn.setFocusPainted(false);
        helpBtn.setFocusable(false); // prevent spacebar from triggering this button
        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.addActionListener(e -> showHelpDialog());
        footer.add(helpBtn, BorderLayout.EAST);

        root.add(footer, BorderLayout.SOUTH);

        // Minimum frame size covers both lobby and game
        root.setPreferredSize(new Dimension(ARENA_W, ARENA_H + 80));
        return root;
    }

    private JPanel buildPlayerListPanel() {
        JPanel outer = new JPanel(new BorderLayout(0, 8));
        outer.setBackground(BG_PANEL);
        outer.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_DIM, 1),
            BorderFactory.createEmptyBorder(12, 12, 12, 12)));

        // Header row
        JPanel hdr = new JPanel(new BorderLayout());
        hdr.setOpaque(false);
        hdr.add(styled("PLAYERS", 13, Font.BOLD, TEXT_MAIN), BorderLayout.WEST);
        playerCountLabel = styled("0 / 8", 12, Font.PLAIN, TEXT_DIM);
        hdr.add(playerCountLabel, BorderLayout.EAST);
        outer.add(hdr, BorderLayout.NORTH);

        // Scrollable list
        playerListPanel = new JPanel();
        playerListPanel.setLayout(new BoxLayout(playerListPanel, BoxLayout.Y_AXIS));
        playerListPanel.setBackground(BG_PANEL);

        JScrollPane scroll = new JScrollPane(playerListPanel);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(BG_PANEL);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        outer.add(scroll, BorderLayout.CENTER);

        return outer;
    }

    private JPanel buildConfigAndStartPanel() {
        JPanel outer = new JPanel(new BorderLayout(0, 12));
        outer.setBackground(BG_DARK);

        // Config settings panel
        configPanel = new JPanel(new GridBagLayout());
        configPanel.setBackground(BG_PANEL);
        configPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_DIM, 1),
            BorderFactory.createEmptyBorder(12, 14, 12, 14)));

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 8);
        gc.anchor = GridBagConstraints.WEST;

        // Section title
        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2;
        configPanel.add(styled("GAME SETTINGS", 13, Font.BOLD, TEXT_MAIN), gc);

        gc.gridy = 1; gc.gridwidth = 2;
        configPanel.add(styledSeparator(), gc);

        gc.gridwidth = 1;
        configSpinners = new JSpinner[CONFIG_FIELDS.length];
        LobbyConfig defaults = new LobbyConfig();

        for (int i = 0; i < CONFIG_FIELDS.length; i++) {
            String label = (String) CONFIG_FIELDS[i][0];
            int    min   = (int)    CONFIG_FIELDS[i][1];
            int    max   = (int)    CONFIG_FIELDS[i][2];
            int    step  = (int)    CONFIG_FIELDS[i][3];
            int    init  = getConfigValue(defaults, i);

            gc.gridx = 0; gc.gridy = i + 2;
            gc.fill  = GridBagConstraints.NONE;
            configPanel.add(styled(label, 12, Font.PLAIN, TEXT_DIM), gc);

            JSpinner spinner = darkSpinner(min, max, init, step);
            final int idx = i;
            spinner.addChangeListener(e -> onConfigSpinnerChanged());
            configSpinners[i] = spinner;

            gc.gridx = 1;
            gc.fill  = GridBagConstraints.HORIZONTAL;
            gc.weightx = 1.0;
            configPanel.add(spinner, gc);
        }

        outer.add(configPanel, BorderLayout.CENTER);

        // Bottom: status label + start button
        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setBackground(BG_DARK);
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        lobbyStatusLabel = styled("Waiting for host to start...", 13, Font.ITALIC, TEXT_DIM);
        lobbyStatusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        bottom.add(lobbyStatusLabel, BorderLayout.NORTH);

        startButton = new JButton("START GAME");
        startButton.setFont(new Font("Monospaced", Font.BOLD, 15));
        startButton.setForeground(BG_DARK);
        startButton.setBackground(ACCENT);
        startButton.setBorderPainted(false);
        startButton.setFocusPainted(false);
        startButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        startButton.setPreferredSize(new Dimension(200, 42));
        startButton.setVisible(false); // only shown to host
        startButton.addActionListener(e -> {
            startButton.setEnabled(false);
            startButton.setText("Starting...");
            if (lobbySender != null)
                lobbySender.accept(new TcpMessage(MsgType.LOBBY_START, null));
        });
        bottom.add(startButton, BorderLayout.CENTER);

        outer.add(bottom, BorderLayout.SOUTH);
        return outer;
    }

    // ── Refresh lobby display ─────────────────────────────────────────

    private void refreshLobbyDisplay(LobbyState state) {
        if (state == null) return;
        currentLobbyState = state;

        // Update player count
        int maxP = state.config != null ? state.config.maxPlayers : 8;
        playerCountLabel.setText(state.players.size() + " / " + maxP);

        // Seed colorMap from server-provided color indices
        for (LobbyPlayerInfo p : state.players) {
            colorMap.put(p.id, p.colorIndex);
        }

        // Rebuild player list
        playerListPanel.removeAll();
        for (LobbyPlayerInfo p : state.players) {
            playerListPanel.add(buildPlayerRow(p));
            playerListPanel.add(Box.createVerticalStrut(4));
        }
        playerListPanel.revalidate();
        playerListPanel.repaint();

        // Show/hide config and start button based on host status
        boolean amHost = myPlayerId != null && myPlayerId.equals(state.hostPlayerId);
        isHost = amHost;

        setConfigEditable(amHost);
        startButton.setVisible(amHost);
        if (amHost) {
            lobbyStatusLabel.setText("You are the host. Configure and press Start.");
            lobbyStatusLabel.setForeground(ACCENT);
        } else {
            lobbyStatusLabel.setText("Waiting for host to start...");
            lobbyStatusLabel.setForeground(TEXT_DIM);
        }

        // Sync spinner values from server (without triggering a config update)
        if (state.config != null && configSpinners != null) {
            syncSpinnersFromConfig(state.config);
        }
    }

    private JPanel buildPlayerRow(LobbyPlayerInfo p) {
        boolean isMe = p.id.equals(myPlayerId);
        int colorIdx = colorMap.getOrDefault(p.id, 0);
        Color c = PLAYER_COLORS[colorIdx % PLAYER_COLORS.length];

        // Name row (same for everyone)
        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        nameRow.setOpaque(false);
        nameRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        // Color dot
        JPanel dot = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(c);
                g.fillOval(2, 2, 12, 12);
            }
        };
        dot.setOpaque(false);
        dot.setPreferredSize(new Dimension(16, 16));
        nameRow.add(dot);

        String displayName = p.name + (isMe ? " (You)" : "");
        JLabel nameLbl = styled(displayName, 13, Font.PLAIN, isMe ? Color.WHITE : TEXT_MAIN);
        nameRow.add(nameLbl);

        if (p.isHost) {
            JLabel badge = styled("[HOST]", 11, Font.BOLD, HOST_BADGE);
            nameRow.add(badge);
        }

        if (!isMe) return nameRow;

        // For own player: add a color picker row below the name row
        JPanel colorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        colorRow.setOpaque(false);
        colorRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        colorRow.add(styled("Color:", 11, Font.PLAIN, TEXT_DIM));

        for (int i = 0; i < PLAYER_COLORS.length; i++) {
            final int chosenIdx = i;
            final Color swatch  = PLAYER_COLORS[i];
            JPanel swatchBtn = new JPanel() {
                @Override protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g;
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int cur   = colorMap.getOrDefault(myPlayerId, 0);
                    boolean taken = isColorTaken(chosenIdx);

                    if (cur == chosenIdx) {
                        // Selected — bright white ring around the swatch
                        g2.setColor(Color.WHITE);
                        g2.fillOval(0, 0, 18, 18);
                        g2.setColor(swatch);
                        g2.fillOval(2, 2, 14, 14);
                    } else if (taken) {
                        // Taken by another player — dim + dark overlay + X
                        g2.setColor(new Color(swatch.getRed(), swatch.getGreen(), swatch.getBlue(), 55));
                        g2.fillOval(2, 2, 14, 14);
                        g2.setColor(new Color(0, 0, 0, 140));
                        g2.fillOval(2, 2, 14, 14);
                        g2.setColor(new Color(255, 255, 255, 180));
                        g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g2.drawLine(5, 5, 13, 13);
                        g2.drawLine(13, 5, 5, 13);
                    } else {
                        // Available — normal swatch
                        g2.setColor(swatch);
                        g2.fillOval(2, 2, 14, 14);
                    }
                }
            };
            swatchBtn.setOpaque(false);
            swatchBtn.setPreferredSize(new Dimension(18, 18));
            swatchBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            swatchBtn.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (isColorTaken(chosenIdx)) return; // blocked — color taken
                    colorMap.put(myPlayerId, chosenIdx);
                    if (lobbySender != null)
                        lobbySender.accept(new TcpMessage(MsgType.LOBBY_COLOR_CHANGE, chosenIdx));
                    playerListPanel.repaint();
                }
            });
            colorRow.add(swatchBtn);
        }

        JPanel compound = new JPanel();
        compound.setLayout(new BoxLayout(compound, BoxLayout.Y_AXIS));
        compound.setOpaque(false);
        compound.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
        compound.add(nameRow);
        compound.add(colorRow);
        return compound;
    }

    // ── Config helpers ────────────────────────────────────────────────

    private void setConfigEditable(boolean editable) {
        if (configSpinners == null) return;
        for (JSpinner s : configSpinners) s.setEnabled(editable);
    }

    /** Sync spinner display values from a LobbyConfig without firing change events. */
    private void syncSpinnersFromConfig(LobbyConfig cfg) {
        if (configSpinners == null) return;
        // Temporarily detach listeners to prevent feedback loop
        ChangeListener[] listeners = new ChangeListener[configSpinners.length];
        for (int i = 0; i < configSpinners.length; i++) {
            if (configSpinners[i].getChangeListeners().length > 0) {
                listeners[i] = configSpinners[i].getChangeListeners()[0];
                configSpinners[i].removeChangeListener(listeners[i]);
            }
            configSpinners[i].setValue(getConfigValue(cfg, i));
        }
        for (int i = 0; i < configSpinners.length; i++) {
            if (listeners[i] != null)
                configSpinners[i].addChangeListener(listeners[i]);
        }
    }

    private int getConfigValue(LobbyConfig cfg, int index) {
        return switch (index) {
            case 0 -> cfg.roundDurationSeconds;
            case 1 -> cfg.maxPlayers;
            case 2 -> cfg.pointsPerZoneTick;
            case 3 -> cfg.freezeDurationSeconds;
            case 4 -> cfg.zoneCaptureTimeSeconds;
            case 5 -> cfg.itemSpawnIntervalSeconds;
            case 6 -> cfg.tagPenaltyPoints;
            case 7 -> cfg.speedBoostDurationSeconds;
            default -> 0;
        };
    }

    private LobbyConfig buildConfigFromSpinners() {
        LobbyConfig cfg = new LobbyConfig();
        if (configSpinners == null) return cfg;
        cfg.roundDurationSeconds      = (int) configSpinners[0].getValue();
        cfg.maxPlayers                = (int) configSpinners[1].getValue();
        cfg.pointsPerZoneTick         = (int) configSpinners[2].getValue();
        cfg.freezeDurationSeconds     = (int) configSpinners[3].getValue();
        cfg.zoneCaptureTimeSeconds    = (int) configSpinners[4].getValue();
        cfg.itemSpawnIntervalSeconds  = (int) configSpinners[5].getValue();
        cfg.tagPenaltyPoints          = (int) configSpinners[6].getValue();
        cfg.speedBoostDurationSeconds = (int) configSpinners[7].getValue();
        return cfg;
    }

    /** Debounced: waits 400 ms after last change before sending config update. */
    private void onConfigSpinnerChanged() {
        if (!isHost || lobbySender == null) return;
        if (configDebounce != null) configDebounce.stop();
        configDebounce = new javax.swing.Timer(400, e -> {
            LobbyConfig cfg = buildConfigFromSpinners();
            lobbySender.accept(new TcpMessage(MsgType.LOBBY_CONFIG_UPDATE, cfg));
        });
        configDebounce.setRepeats(false);
        configDebounce.start();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Game panel
    // ═══════════════════════════════════════════════════════════════════

    private JPanel buildGamePanel() {
        JPanel gameRoot = new JPanel(new BorderLayout(0, 0));
        gameRoot.setBackground(BG_DARK);

        // ── Top section: title/timer row + two-row scoreboard ────────
        JPanel topSection = new JPanel();
        topSection.setLayout(new BoxLayout(topSection, BoxLayout.Y_AXIS));
        topSection.setBackground(new Color(0x08081a));
        topSection.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_DIM));

        // Row 1: title (left) + timer (centre)
        JPanel hud = new JPanel(new BorderLayout());
        hud.setOpaque(false);
        hud.setBorder(BorderFactory.createEmptyBorder(6, 16, 2, 16));

        titleLabel = styled("CHRONOARENA", 20, Font.BOLD, ACCENT);
        hud.add(titleLabel, BorderLayout.WEST);

        timerLabel = styled("03:00", 22, Font.BOLD, TEXT_MAIN);
        timerLabel.setHorizontalAlignment(SwingConstants.CENTER);
        hud.add(timerLabel, BorderLayout.CENTER);
        topSection.add(hud);

        // Score bar: up to 2 rows, each a centred FlowLayout
        JPanel scoreBar = new JPanel();
        scoreBar.setLayout(new BoxLayout(scoreBar, BoxLayout.Y_AXIS));
        scoreBar.setOpaque(false);
        scoreBar.setBorder(BorderFactory.createEmptyBorder(2, 8, 6, 8));

        scoreRow1 = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 2));
        scoreRow1.setOpaque(false);
        scoreRow2 = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 2));
        scoreRow2.setOpaque(false);
        scoreRow2.setVisible(false); // only shown with 5+ players
        scoreBar.add(scoreRow1);
        scoreBar.add(scoreRow2);
        topSection.add(scoreBar);

        gameRoot.add(topSection, BorderLayout.NORTH);

        // ── Arena ─────────────────────────────────────────────────────
        arenaPanel = new ArenaPanel();
        arenaPanel.setPreferredSize(new Dimension(ARENA_W, ARENA_H));
        gameRoot.add(arenaPanel, BorderLayout.CENTER);

        // ── Bottom status bar ─────────────────────────────────────────
        JPanel gameFooter = new JPanel(new BorderLayout());
        gameFooter.setBackground(new Color(0x08081a));
        gameFooter.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));

        JLabel status = styled(
            "WASD / Arrows = Move   |   SPACE / F = Freeze-Ray   |   ESC = Quit",
            11, Font.PLAIN, TEXT_DIM);
        gameFooter.add(status, BorderLayout.CENTER);

        JButton gameHelpBtn = new JButton("? HOW TO PLAY");
        gameHelpBtn.setFont(new Font("Monospaced", Font.BOLD, 11));
        gameHelpBtn.setForeground(ACCENT);
        gameHelpBtn.setBackground(new Color(0x08081a));
        gameHelpBtn.setBorder(BorderFactory.createLineBorder(ACCENT, 1));
        gameHelpBtn.setFocusPainted(false);
        gameHelpBtn.setFocusable(false); // prevent spacebar from triggering this button
        gameHelpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        gameHelpBtn.addActionListener(e -> showHelpDialog());
        gameFooter.add(gameHelpBtn, BorderLayout.EAST);

        gameRoot.add(gameFooter, BorderLayout.SOUTH);

        return gameRoot;
    }

    // ── Render loop (~60 fps, driven by Swing Timer) ──────────────────
    private void startRenderLoop() {
        new javax.swing.Timer(16, e -> {
            updateHUD();
            if (arenaPanel != null) arenaPanel.repaint();
        }).start();
    }

    private void updateHUD() {
        GameStateSnapshot snap = currentSnap;
        if (snap == null) return;

        long sec = snap.roundTimeRemainingMs / 1000;
        timerLabel.setText(String.format("%02d:%02d", sec / 60, sec % 60));
        timerLabel.setForeground(sec <= 30 ? ACCENT2 : TEXT_MAIN);

        scoreRow1.removeAll();
        scoreRow2.removeAll();
        if (snap.players != null) {
            List<PlayerInfo> sorted = new ArrayList<>(snap.players);
            sorted.sort((a, b) -> b.score - a.score);
            for (int i = 0; i < sorted.size(); i++) {
                PlayerInfo p = sorted.get(i);
                Color c = playerColor(p.id);
                JLabel badge = styled(p.name + "  " + p.score, 13, Font.BOLD, c);
                badge.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(c.darker(), 1),
                    BorderFactory.createEmptyBorder(2, 8, 2, 8)));
                if (i < 4) scoreRow1.add(badge);
                else       scoreRow2.add(badge);
            }
            scoreRow2.setVisible(sorted.size() > 4);
        }
        scoreRow1.revalidate(); scoreRow1.repaint();
        scoreRow2.revalidate(); scoreRow2.repaint();
    }

    private void loadSprites() {
        try {
            blueSprite = ImageIO.read(getClass().getResource("/BlueGuy.png"));
            redSprite  = ImageIO.read(getClass().getResource("/RedGuy.png"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Arena panel — all 2-D drawing
    // ═══════════════════════════════════════════════════════════════════

    private class ArenaPanel extends JPanel {
        ArenaPanel() { setBackground(BG_DARK); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            drawGrid(g2);

            GameStateSnapshot snap = currentSnap;
            if (snap == null) { drawWaiting(g2); return; }

            drawZones(g2, snap.zones);
            drawItems(g2, snap.items);
            drawPlayers(g2, snap.players, snap);
            drawNotification(g2);
        }

        private void drawGrid(Graphics2D g2) {
            g2.setColor(GRID_COL);
            g2.setStroke(new BasicStroke(0.5f));
            int step = 40;
            for (int x = 0; x <= ARENA_W; x += step) g2.drawLine(x, 0, x, ARENA_H);
            for (int y = 0; y <= ARENA_H; y += step) g2.drawLine(0, y, ARENA_W, y);
        }

        private void drawWaiting(Graphics2D g2) {
            g2.setColor(TEXT_DIM);
            g2.setFont(new Font("Monospaced", Font.PLAIN, 16));
            String msg = "Waiting for game state...";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(msg, (ARENA_W - fm.stringWidth(msg)) / 2, ARENA_H / 2);
        }

        private void drawZones(Graphics2D g2, List<ZoneInfo> zones) {
            if (zones == null) return;
            for (ZoneInfo z : zones) {
                Color fillBase = (z.ownerPlayerId != null) ? playerColor(z.ownerPlayerId) : ZONE_FREE;

                Color fill = z.contested
                    ? new Color(CONTESTED.getRed(), CONTESTED.getGreen(), CONTESTED.getBlue(), 55)
                    : new Color(fillBase.getRed(), fillBase.getGreen(), fillBase.getBlue(), 40);
                g2.setColor(fill);
                g2.fillRect(z.x, z.y, z.width, z.height);

                if (z.captureProgress > 0 && z.captureProgress < 1.0) {
                    int barW = (int)(z.width * z.captureProgress);
                    g2.setColor(new Color(CAPTURE_CLR.getRed(), CAPTURE_CLR.getGreen(), CAPTURE_CLR.getBlue(), 180));
                    g2.fillRect(z.x, z.y + z.height - 6, barW, 6);
                }

                if (z.graceExpiresMs > 0) {
                    long remaining = z.graceExpiresMs - System.currentTimeMillis();
                    double pct = Math.max(0, Math.min(1.0, remaining / 5000.0));
                    g2.setColor(new Color(255, 165, 0, 160));
                    g2.fillRect(z.x, z.y, (int)(z.width * pct), 4);
                }

                Color border = z.contested ? CONTESTED
                    : (z.ownerPlayerId != null ? playerColor(z.ownerPlayerId) : ZONE_BORDER);
                g2.setColor(border);
                g2.setStroke(new BasicStroke(z.contested ? 2.5f : 1.5f));
                g2.drawRect(z.x, z.y, z.width, z.height);

                g2.setFont(new Font("Monospaced", Font.BOLD, 13));
                FontMetrics fm = g2.getFontMetrics();
                String label = "ZONE " + z.id;
                g2.setColor(TEXT_MAIN);
                g2.drawString(label, z.x + (z.width - fm.stringWidth(label)) / 2, z.y + 22);

                g2.setFont(new Font("Monospaced", Font.PLAIN, 11));
                fm = g2.getFontMetrics();
                String sublabel = z.contested ? "CONTESTED"
                    : (z.ownerPlayerId != null ? z.ownerPlayerId : "UNCLAIMED");
                g2.setColor(z.contested ? CONTESTED : TEXT_DIM);
                g2.drawString(sublabel, z.x + (z.width - fm.stringWidth(sublabel)) / 2, z.y + 38);
            }
        }

        private void drawItems(Graphics2D g2, List<ItemInfo> items) {
            if (items == null) return;
            long now = System.currentTimeMillis();
            for (ItemInfo item : items) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(now / 300.0 + item.x);
                Color base  = item.isWeapon ? WEAPON_CLR
                           : item.isSpeedBoost ? SPEED_CLR
                           : item.isScoreSteal ? SCORE_STEAL_CLR
                           : ENERGY_CLR;

                RadialGradientPaint glow = new RadialGradientPaint(
                    new Point2D.Float(item.x, item.y), ITEM_R * 2.5f,
                    new float[]{0f, 1f},
                    new Color[]{new Color(base.getRed(), base.getGreen(), base.getBlue(), (int)(120 * pulse)),
                                new Color(base.getRed(), base.getGreen(), base.getBlue(), 0)});
                g2.setPaint(glow);
                g2.fillOval(item.x - ITEM_R * 2, item.y - ITEM_R * 2, ITEM_R * 4, ITEM_R * 4);

                g2.setColor(base);
                g2.fillOval(item.x - ITEM_R, item.y - ITEM_R, ITEM_R * 2, ITEM_R * 2);

                g2.setColor(BG_DARK);
                g2.setFont(new Font("Dialog", Font.BOLD, 10));
                FontMetrics fm = g2.getFontMetrics();
                String icon = item.isWeapon ? "\u2744"
                           : item.isSpeedBoost ? "\u25b6"
                           : item.isScoreSteal ? "\u2620"
                           : "\u26a1";
                g2.drawString(icon, item.x - fm.stringWidth(icon)/2, item.y + fm.getAscent()/2 - 1);
            }
        }

        private void drawPlayers(Graphics2D g2, List<PlayerInfo> players,
                                  GameStateSnapshot snap) {
            if (players == null) return;

            for (PlayerInfo p : players) {
                Color c = playerColor(p.id);
                if (p.frozen) {
                    g2.setColor(new Color(FROZEN_CLR.getRed(), FROZEN_CLR.getGreen(), FROZEN_CLR.getBlue(), 80));
                    g2.fillOval(p.x - PLAYER_R - 6, p.y - PLAYER_R - 6, (PLAYER_R + 6) * 2, (PLAYER_R + 6) * 2);
                    g2.setColor(FROZEN_CLR);
                    g2.setStroke(new BasicStroke(2.5f));
                    g2.drawOval(p.x - PLAYER_R - 6, p.y - PLAYER_R - 6, (PLAYER_R + 6) * 2, (PLAYER_R + 6) * 2);
                } else if (p.speedBoosted) {
                    long now2 = System.currentTimeMillis();
                    float phase = (now2 % 800) / 800f;
                    g2.setColor(new Color(SPEED_CLR.getRed(), SPEED_CLR.getGreen(), SPEED_CLR.getBlue(), 70));
                    g2.fillOval(p.x - PLAYER_R - 6, p.y - PLAYER_R - 6, (PLAYER_R + 6) * 2, (PLAYER_R + 6) * 2);
                    g2.setColor(SPEED_CLR);
                    g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                                                  1f, new float[]{6f, 4f}, phase * 10f));
                    g2.drawOval(p.x - PLAYER_R - 6, p.y - PLAYER_R - 6, (PLAYER_R + 6) * 2, (PLAYER_R + 6) * 2);
                    g2.setStroke(new BasicStroke(1.5f));
                } else {
                    RadialGradientPaint glow = new RadialGradientPaint(
                        new Point2D.Float(p.x, p.y), PLAYER_R + 8,
                        new float[]{0f, 1f},
                        new Color[]{new Color(c.getRed(), c.getGreen(), c.getBlue(), 60),
                                    new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)});
                    g2.setPaint(glow);
                    g2.fillOval(p.x - PLAYER_R - 8, p.y - PLAYER_R - 8, (PLAYER_R + 8) * 2, (PLAYER_R + 8) * 2);
                }
            }

            for (PlayerInfo p : players) {
                Color c = p.frozen ? FROZEN_CLR : playerColor(p.id);
                boolean isMe = p.id.equals(myPlayerId);

                BufferedImage sprite = isMe ? blueSprite : redSprite;
                g2.drawImage(sprite, p.x - PLAYER_R, p.y - PLAYER_R, PLAYER_R * 2, PLAYER_R * 2, null);

                g2.setColor(isMe ? Color.WHITE : c.brighter());
                g2.setStroke(new BasicStroke(isMe ? 2.5f : 1.5f));
                g2.drawOval(p.x - PLAYER_R, p.y - PLAYER_R, PLAYER_R * 2, PLAYER_R * 2);

                if (p.hasWeapon) {
                    g2.setColor(WEAPON_CLR);
                    g2.fillOval(p.x + PLAYER_R - 5, p.y - PLAYER_R - 1, 8, 8);
                    g2.setColor(BG_DARK);
                    g2.setStroke(new BasicStroke(1));
                    g2.drawOval(p.x + PLAYER_R - 5, p.y - PLAYER_R - 1, 8, 8);
                }
                if (p.hasScoreSteal) {
                    g2.setColor(SCORE_STEAL_CLR);
                    g2.fillOval(p.x + PLAYER_R - 5, p.y - PLAYER_R + 9, 8, 8);
                    g2.setColor(BG_DARK);
                    g2.setStroke(new BasicStroke(1));
                    g2.drawOval(p.x + PLAYER_R - 5, p.y - PLAYER_R + 9, 8, 8);
                }

                g2.setFont(new Font("Monospaced", Font.BOLD, 11));
                FontMetrics fm = g2.getFontMetrics();
                String nameTag = p.name;
                int nx = p.x - fm.stringWidth(nameTag) / 2;
                int ny = p.y - PLAYER_R - 5;
                g2.setColor(new Color(0, 0, 0, 160));
                g2.drawString(nameTag, nx + 1, ny + 1);
                g2.setColor(isMe ? Color.WHITE : c.brighter());
                g2.drawString(nameTag, nx, ny);

                g2.setFont(new Font("Monospaced", Font.PLAIN, 10));
                fm = g2.getFontMetrics();
                String scoreStr = String.valueOf(p.score);
                g2.setColor(TEXT_DIM);
                g2.drawString(scoreStr, p.x - fm.stringWidth(scoreStr) / 2, p.y + PLAYER_R + 12);

                if (p.frozen) {
                    long ms = p.frozenUntilMs - System.currentTimeMillis();
                    if (ms > 0) {
                        String ft = String.format("%.1fs", ms / 1000.0);
                        g2.setFont(new Font("Monospaced", Font.BOLD, 12));
                        fm = g2.getFontMetrics();
                        g2.setColor(FROZEN_CLR);
                        g2.drawString(ft, p.x - fm.stringWidth(ft) / 2, p.y + 4);
                    }
                }

                if (p.speedBoosted) {
                    long ms = p.speedBoostUntilMs - System.currentTimeMillis();
                    if (ms > 0) {
                        String bt = String.format("\u25b6%.1fs", ms / 1000.0);
                        g2.setFont(new Font("Monospaced", Font.BOLD, 11));
                        fm = g2.getFontMetrics();
                        g2.setColor(SPEED_CLR);
                        g2.drawString(bt, p.x - fm.stringWidth(bt) / 2, p.y + PLAYER_R + 24);
                    }
                }
            }
        }

        private void drawNotification(Graphics2D g2) {
            if (notification == null) return;
            if (System.currentTimeMillis() > notifyExpiresMs) { notification = null; return; }

            long  remaining = notifyExpiresMs - System.currentTimeMillis();
            float alpha     = Math.min(1f, remaining / 500f);

            g2.setFont(new Font("Monospaced", Font.BOLD, 28));
            FontMetrics fm = g2.getFontMetrics();
            int tx = (ARENA_W - fm.stringWidth(notification)) / 2;
            int ty = ARENA_H / 2 - 30;

            g2.setColor(new Color(0, 0, 0, (int)(180 * alpha)));
            g2.fillRoundRect(tx - 16, ty - fm.getAscent() - 4,
                             fm.stringWidth(notification) + 32, fm.getHeight() + 12, 12, 12);
            g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), (int)(255 * alpha)));
            g2.drawString(notification, tx, ty);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  End screen
    // ═══════════════════════════════════════════════════════════════════

    private void showEndScreen(String winnerId, Map<String, Integer> scores,
                                List<PlayerInfo> players) {
        JDialog dialog = new JDialog(frame, "Round Over", true);
        dialog.setLayout(new BorderLayout());
        dialog.getContentPane().setBackground(BG_DARK);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(BG_DARK);
        content.setBorder(BorderFactory.createEmptyBorder(30, 50, 30, 50));

        JLabel roundOver = styled("ROUND OVER", 30, Font.BOLD, ACCENT);
        roundOver.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(roundOver);
        content.add(Box.createVerticalStrut(10));

        String winnerName = players.stream()
            .filter(p -> p.id.equals(winnerId)).map(p -> p.name)
            .findFirst().orElse(winnerId);
        JLabel winnerLbl = styled("Winner: " + winnerName, 18, Font.BOLD, ENERGY_CLR);
        winnerLbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(winnerLbl);
        content.add(Box.createVerticalStrut(20));

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        int rank = 1;
        for (Map.Entry<String, Integer> e : sorted) {
            String pName = players.stream()
                .filter(p -> p.id.equals(e.getKey())).map(p -> p.name)
                .findFirst().orElse(e.getKey());
            Color c = playerColor(e.getKey());
            JLabel row = styled(String.format("#%d  %-12s  %d pts", rank++, pName, e.getValue()),
                                14, Font.BOLD, c);
            row.setAlignmentX(Component.CENTER_ALIGNMENT);
            content.add(row);
            content.add(Box.createVerticalStrut(4));
        }

        content.add(Box.createVerticalStrut(20));
        JButton quit = new JButton("Exit");
        quit.setFont(new Font("Monospaced", Font.BOLD, 14));
        quit.setForeground(BG_DARK);
        quit.setBackground(ACCENT);
        quit.setBorderPainted(false);
        quit.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        quit.setAlignmentX(Component.CENTER_ALIGNMENT);
        quit.addActionListener(e -> System.exit(0));
        content.add(quit);

        dialog.add(content, BorderLayout.CENTER);
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Help / How-to-Play dialog
    // ═══════════════════════════════════════════════════════════════════

    private void showHelpDialog() {
        JDialog dlg = new JDialog(frame, "How to Play — ChronoArena", true);
        dlg.getContentPane().setBackground(BG_DARK);
        dlg.setLayout(new BorderLayout());

        // ── Title bar ────────────────────────────────────────────────
        JLabel title = styled("CHRONOARENA  —  HOW TO PLAY", 18, Font.BOLD, ACCENT);
        title.setBorder(BorderFactory.createEmptyBorder(18, 24, 10, 24));
        title.setBackground(new Color(0x08081a));
        title.setOpaque(true);
        dlg.add(title, BorderLayout.NORTH);

        // ── Scrollable content ────────────────────────────────────────
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(BG_DARK);
        content.setBorder(BorderFactory.createEmptyBorder(8, 24, 16, 24));

        // Helper lambdas for adding styled rows
        Runnable addSep = () -> {
            JSeparator sep = styledSeparator();
            sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
            content.add(Box.createVerticalStrut(6));
            content.add(sep);
            content.add(Box.createVerticalStrut(8));
        };

        // ── CONTROLS ─────────────────────────────────────────────────
        content.add(helpSection("CONTROLS"));
        addSep.run();

        String[][] controls = {
            { "W / \u2191",       "Move Up" },
            { "S / \u2193",       "Move Down" },
            { "A / \u2190",       "Move Left" },
            { "D / \u2192",       "Move Right" },
            { "Q",                "Move Up-Left (diagonal)" },
            { "E",                "Move Up-Right (diagonal)" },
            { "Z",                "Move Down-Left (diagonal)" },
            { "C",                "Move Down-Right (diagonal)" },
            { "SPACE / F",        "Fire Freeze-Ray (if armed)" },
            { "ESC",              "Quit the game" },
        };
        for (String[] row : controls) content.add(helpRow(row[0], row[1]));

        content.add(Box.createVerticalStrut(14));

        // ── POWERUPS ─────────────────────────────────────────────────
        content.add(helpSection("POWERUPS"));
        addSep.run();

        content.add(helpPowerupRow("\u2744", WEAPON_CLR,
            "Freeze Ray (Orange)",
            "Walk over to pick up. Press SPACE or F to fire — freezes the nearest player within range for a few seconds. The frozen player also loses points!"));
        content.add(Box.createVerticalStrut(8));
        content.add(helpPowerupRow("\u25b6", SPEED_CLR,
            "Speed Boost (Green)",
            "Walk over to instantly double your movement speed for a short time."));
        content.add(Box.createVerticalStrut(8));
        content.add(helpPowerupRow("\u26a1", ENERGY_CLR,
            "Energy (Gold)",
            "Walk over to collect. Awards bonus points immediately — no skill required!"));

        content.add(Box.createVerticalStrut(14));

        // ── ZONE RULES ───────────────────────────────────────────────
        content.add(helpSection("ZONE CONTROL RULES"));
        addSep.run();

        String[] zoneRules = {
            "Three zones (A, B, C) are placed around the arena.",
            "Stand in a zone uncontested — a capture bar fills as you hold it.",
            "Once captured, you earn points each tick while no enemy is in the zone.",
            "CONTESTED: two or more players in the same zone — no points and capture pauses.",
            "Grace Timer: an orange bar shows a brief window after capture during which the",
            "  zone cannot be stolen — hold your ground to protect it!",
            "Zone border color shows the current owner. Flashing red = contested.",
        };
        for (String line : zoneRules) content.add(helpBullet(line));

        content.add(Box.createVerticalStrut(14));

        // ── GENERAL RULES ────────────────────────────────────────────
        content.add(helpSection("GENERAL RULES"));
        addSep.run();

        String[] generalRules = {
            "The round lasts for the configured duration (default: 3 minutes).",
            "The player with the most points when the timer hits zero wins.",
            "Points are EARNED by: holding zones each tick, collecting energy items.",
            "Points are LOST by: being frozen by another player's Freeze-Ray.",
            "Collision with a Freeze-Ray deals a tag penalty and freezes you briefly.",
            "Multiple players can hold items at once — only one weapon fires at a time.",
            "Choose a unique color in the lobby so teammates can tell you apart!",
        };
        for (String line : generalRules) content.add(helpBullet(line));

        JScrollPane scroll = new JScrollPane(content,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(BG_DARK);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        dlg.add(scroll, BorderLayout.CENTER);

        // ── Close button ─────────────────────────────────────────────
        JButton close = new JButton("CLOSE");
        close.setFont(new Font("Monospaced", Font.BOLD, 13));
        close.setForeground(BG_DARK);
        close.setBackground(ACCENT);
        close.setBorderPainted(false);
        close.setFocusPainted(false);
        close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        close.addActionListener(e -> dlg.dispose());

        JPanel btnRow = new JPanel();
        btnRow.setBackground(BG_DARK);
        btnRow.setBorder(BorderFactory.createEmptyBorder(8, 0, 16, 0));
        btnRow.add(close);
        dlg.add(btnRow, BorderLayout.SOUTH);

        dlg.setSize(560, 580);
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);
    }

    private JLabel helpSection(String text) {
        JLabel lbl = styled(text, 13, Font.BOLD, TEXT_MAIN);
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        lbl.setBorder(BorderFactory.createEmptyBorder(4, 0, 2, 0));
        return lbl;
    }

    private JPanel helpRow(String key, String desc) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel keyLbl  = styled(String.format("%-14s", key), 12, Font.BOLD, ACCENT);
        JLabel descLbl = styled(desc, 12, Font.PLAIN, TEXT_DIM);
        row.add(keyLbl,  BorderLayout.WEST);
        row.add(descLbl, BorderLayout.CENTER);
        return row;
    }

    private JPanel helpPowerupRow(String icon, Color iconColor, String name, String desc) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        header.setOpaque(false);
        JLabel iconLbl = styled(icon, 16, Font.BOLD, iconColor);
        JLabel nameLbl = styled(name, 12, Font.BOLD, iconColor);
        header.add(iconLbl);
        header.add(nameLbl);
        row.add(header);

        JLabel descLbl = styled("   " + desc, 11, Font.PLAIN, TEXT_DIM);
        descLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(descLbl);
        return row;
    }

    private JLabel helpBullet(String text) {
        JLabel lbl = styled("  \u2022  " + text, 11, Font.PLAIN, TEXT_DIM);
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        lbl.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
        return lbl;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    private Color playerColor(String playerId) {
        int idx = colorMap.getOrDefault(playerId, 0);
        return PLAYER_COLORS[idx % PLAYER_COLORS.length];
    }

    /** Returns true if the given colorIndex is already claimed by another player in the lobby. */
    private boolean isColorTaken(int colorIdx) {
        LobbyState state = currentLobbyState;
        if (state == null || state.players == null) return false;
        for (LobbyPlayerInfo p : state.players) {
            if (!p.id.equals(myPlayerId) && p.colorIndex == colorIdx) return true;
        }
        return false;
    }

    private void showNotification(String msg, int durationMs) {
        notification    = msg;
        notifyExpiresMs = System.currentTimeMillis() + durationMs;
    }

    private static JLabel styled(String text, int size, int style, Color color) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(new Font("Monospaced", style, size));
        lbl.setForeground(color);
        return lbl;
    }

    private static JTextField darkField(int cols) {
        JTextField f = new JTextField(cols);
        f.setBackground(new Color(0x1e1e3a));
        f.setForeground(new Color(0xe0e0ff));
        f.setCaretColor(Color.WHITE);
        f.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x44448a)),
            BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        f.setFont(new Font("Monospaced", Font.PLAIN, 13));
        return f;
    }

    private static JSpinner darkSpinner(int min, int max, int init, int step) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(init, min, max, step));
        s.setFont(new Font("Monospaced", Font.PLAIN, 12));
        s.setBackground(new Color(0x1e1e3a));
        s.setForeground(new Color(0xe0e0ff));
        JComponent editor = s.getEditor();
        if (editor instanceof JSpinner.DefaultEditor de) {
            de.getTextField().setBackground(new Color(0x1e1e3a));
            de.getTextField().setForeground(new Color(0xe0e0ff));
            de.getTextField().setCaretColor(Color.WHITE);
            de.getTextField().setFont(new Font("Monospaced", Font.PLAIN, 12));
            de.getTextField().setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        }
        s.setBorder(BorderFactory.createLineBorder(new Color(0x44448a)));
        return s;
    }

    private static JSeparator styledSeparator() {
        JSeparator sep = new JSeparator();
        sep.setForeground(BORDER_DIM);
        return sep;
    }
}