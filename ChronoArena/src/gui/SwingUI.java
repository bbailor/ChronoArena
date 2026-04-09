package gui;

import shared.Messages.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
 *
 * Arrow keys + WASD both work. Diagonal detection happens when two keys
 * are held simultaneously.
 */
public class SwingUI implements GameUI {

    // ── Arena constants (must match GameLoop / GameState) ─────────────
    private static final int ARENA_W  = 800;
    private static final int ARENA_H  = 600;
    private static final int PLAYER_R = 14;   // player circle radius
    private static final int ITEM_R   = 9;

    // ── Colours ────────────────────────────────────────────────────────
    private static final Color BG_DARK      = new Color(0x0d0d1a);
    private static final Color GRID_COL     = new Color(0x1a1a33);
    private static final Color HUD_BG       = new Color(0x10102a, true);
    private static final Color ACCENT       = new Color(0x00e5ff);
    private static final Color ACCENT2      = new Color(0xff6b35);
    private static final Color ZONE_FREE    = new Color(0x2a2a4a);
    private static final Color ZONE_BORDER  = new Color(0x44448a);
    private static final Color CONTESTED    = new Color(0xff4444, true);
    private static final Color CAPTURE_CLR  = new Color(0x00e5ff, true);
    private static final Color WEAPON_CLR   = new Color(0xff6b35);
    private static final Color ENERGY_CLR   = new Color(0xffd700);
    private static final Color FROZEN_CLR   = new Color(0x88ccff);
    private static final Color TEXT_MAIN    = new Color(0xe8e8ff);
    private static final Color TEXT_DIM     = new Color(0x888aaa);

    // Player palette (up to 8 players)
    private static final Color[] PLAYER_COLORS = {
        new Color(0x4fc3f7), new Color(0xef5350), new Color(0x66bb6a),
        new Color(0xffa726), new Color(0xce93d8), new Color(0x26c6da),
        new Color(0xd4e157), new Color(0xff7043)
    };

    // ── Swing components ───────────────────────────────────────────────
    private JFrame        frame;
    private ArenaPanel    arena;
    private JLabel        timerLabel, titleLabel;
    private JPanel        scorePanel;

    // ── State (volatile — written by TCP thread, read by EDT) ─────────
    private volatile GameStateSnapshot currentSnap;
    private volatile String myPlayerId;
    private volatile String myPlayerName;

    // Map of playerId → stable colour index
    private final Map<String, Integer> colorMap = new LinkedHashMap<>();
    private int colorIndex = 0;

    // ── Input ──────────────────────────────────────────────────────────
    private final Set<Integer> keysDown = Collections.synchronizedSet(new HashSet<>());
    private final AtomicReference<ActionType> pendingAction =
            new AtomicReference<>(ActionType.NONE);

    // ── Lobby results ─────────────────────────────────────────────────
    private String enteredName;
    private String enteredIp;

    // ── Notification overlay (freeze hit, tagged, etc.) ───────────────
    private volatile String  notification     = null;
    private volatile long notifyExpiresMs = 0;
    private volatile boolean wasFrozen = false;
    
    // Sprites
    private BufferedImage blueSprite;
    private BufferedImage redSprite;

    // ═══════════════════════════════════════════════════════════════════
    //  GameUI — Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void onGameStart(String myPlayerId, String playerName) {
        this.myPlayerId   = myPlayerId;
        this.myPlayerName = playerName;

        SwingUtilities.invokeLater(() -> {
            buildFrame();
            frame.setVisible(true);
            startRenderLoop();
        });
    }

    @Override
    public void onStateUpdate(GameStateSnapshot snapshot) {
        // Assign stable colours to new players
        if (snapshot.players != null) {
            for (PlayerInfo p : snapshot.players) {
                colorMap.computeIfAbsent(p.id, k -> colorIndex++ % PLAYER_COLORS.length);
            }
        }
        currentSnap = snapshot;

        // Detect the exact moment we become frozen and show notification once
        if (snapshot.players != null) {
            for (PlayerInfo p : snapshot.players) {
                if (p.id.equals(myPlayerId)) {
                    if (p.frozen && !wasFrozen) {
                        showNotification("TAGGED!  −10 PTS", 2500);
                    }
                    wasFrozen = p.frozen;
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
        // Compute action from currently held keys
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

        // Freeze-ray takes priority when fire key is pressed
        ActionType fire = pendingAction.getAndSet(ActionType.NONE);
        if (fire == ActionType.FREEZE_RAY) return ActionType.FREEZE_RAY;

        return move;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  GameUI — Lobby
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public String promptPlayerName(String defaultServerIp) {
        // Build a simple lobby dialog (modal, blocks until user clicks OK)
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

        UIManager.put("OptionPane.background", BG_DARK);
        UIManager.put("Panel.background",      BG_DARK);
        UIManager.put("OptionPane.messageForeground", TEXT_MAIN);

        int result = JOptionPane.showConfirmDialog(
            null, panel, "Join ChronoArena",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) {
            System.exit(0);
        }

        enteredName = nameField.getText().trim();
        if (enteredName.isEmpty()) enteredName = "Player";

        String ip = ipField.getText().trim();
        enteredIp = (ip.isEmpty() || ip.equals(defaultServerIp)) ? null : ip;

        return enteredName;
    }

    @Override
    public String getServerIpOverride() { return enteredIp; }

    // ═══════════════════════════════════════════════════════════════════
    //  Frame construction
    // ═══════════════════════════════════════════════════════════════════

    private void buildFrame() {
        loadSprites();

        frame = new JFrame("ChronoArena");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(0, 0));
        frame.getContentPane().setBackground(BG_DARK);

        // ── Top HUD ──────────────────────────────────────────────────
        JPanel hud = new JPanel(new BorderLayout());
        hud.setBackground(new Color(0x08081a));
        hud.setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 16));

        titleLabel = styled("CHRONOARENA", 20, Font.BOLD, ACCENT);
        hud.add(titleLabel, BorderLayout.WEST);

        timerLabel = styled("03:00", 22, Font.BOLD, TEXT_MAIN);
        timerLabel.setHorizontalAlignment(SwingConstants.CENTER);
        hud.add(timerLabel, BorderLayout.CENTER);

        scorePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        scorePanel.setOpaque(false);
        hud.add(scorePanel, BorderLayout.EAST);

        frame.add(hud, BorderLayout.NORTH);

        // ── Arena ─────────────────────────────────────────────────────
        arena = new ArenaPanel();
        arena.setPreferredSize(new Dimension(ARENA_W, ARENA_H));
        frame.add(arena, BorderLayout.CENTER);

        // ── Bottom status bar ─────────────────────────────────────────
        JLabel status = styled("  WASD / Arrows = Move   |   SPACE / F = Freeze-Ray   |   ESC = Quit", 11, Font.PLAIN, TEXT_DIM);
        status.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        status.setBackground(new Color(0x08081a));
        status.setOpaque(true);
        frame.add(status, BorderLayout.SOUTH);

        // ── Key bindings ──────────────────────────────────────────────
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

    // ── Render loop (~60 fps, driven by Swing Timer) ──────────────────
    private void startRenderLoop() {
        new javax.swing.Timer(16, e -> {
            updateHUD();
            arena.repaint();
        }).start();
    }

    private void updateHUD() {
        GameStateSnapshot snap = currentSnap;
        if (snap == null)
            return;

        long sec = snap.roundTimeRemainingMs / 1000;
        String timeStr = String.format("%02d:%02d", sec / 60, sec % 60);
        timerLabel.setText(timeStr);
        if (sec <= 30)
            timerLabel.setForeground(ACCENT2);
        else
            timerLabel.setForeground(TEXT_MAIN);

        // Rebuild score badges
        scorePanel.removeAll();
        if (snap.players != null) {
            List<PlayerInfo> sorted = new ArrayList<>(snap.players);
            sorted.sort((a, b) -> b.score - a.score);
            for (PlayerInfo p : sorted) {
                Color c = playerColor(p.id);
                JLabel badge = styled(p.name + "  " + p.score, 13, Font.BOLD, c);
                badge.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(c.darker(), 1),
                        BorderFactory.createEmptyBorder(2, 8, 2, 8)));
                scorePanel.add(badge);
            }
        }
        scorePanel.revalidate();
        scorePanel.repaint();
    }
    
    private void loadSprites() {
    try {
        blueSprite = ImageIO.read(
            getClass().getResource("/BlueGuy.png")
        );

        redSprite = ImageIO.read(
            getClass().getResource("/RedGuy.png")
        );

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
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            drawGrid(g2);

            GameStateSnapshot snap = currentSnap;
            if (snap == null) {
                drawWaiting(g2);
                return;
            }

            drawZones(g2, snap.zones);
            drawItems(g2, snap.items);
            drawPlayers(g2, snap.players, snap);
            drawNotification(g2);
        }

        // ── Grid ──────────────────────────────────────────────────────
        private void drawGrid(Graphics2D g2) {
            g2.setColor(GRID_COL);
            g2.setStroke(new BasicStroke(0.5f));
            int step = 40;
            for (int x = 0; x <= ARENA_W; x += step) g2.drawLine(x, 0, x, ARENA_H);
            for (int y = 0; y <= ARENA_H; y += step) g2.drawLine(0, y, ARENA_W, y);
        }

        // ── Waiting screen ────────────────────────────────────────────
        private void drawWaiting(Graphics2D g2) {
            g2.setColor(TEXT_DIM);
            g2.setFont(new Font("Monospaced", Font.PLAIN, 16));
            String msg = "Waiting for game state...";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(msg,
                (ARENA_W - fm.stringWidth(msg)) / 2,
                ARENA_H / 2);
        }

        // ── Zones ─────────────────────────────────────────────────────
        private void drawZones(Graphics2D g2, List<ZoneInfo> zones) {
            if (zones == null) return;
            for (ZoneInfo z : zones) {
                Color fillBase = (z.ownerPlayerId != null)
                    ? playerColor(z.ownerPlayerId)
                    : ZONE_FREE;

                // Background fill (semi-transparent)
                Color fill = z.contested
                    ? new Color(CONTESTED.getRed(), CONTESTED.getGreen(), CONTESTED.getBlue(), 55)
                    : new Color(fillBase.getRed(), fillBase.getGreen(), fillBase.getBlue(), 40);
                g2.setColor(fill);
                g2.fillRect(z.x, z.y, z.width, z.height);

                // Capture-progress bar at bottom of zone
                if (z.captureProgress > 0 && z.captureProgress < 1.0) {
                    int barW = (int)(z.width * z.captureProgress);
                    g2.setColor(new Color(CAPTURE_CLR.getRed(), CAPTURE_CLR.getGreen(), CAPTURE_CLR.getBlue(), 180));
                    g2.fillRect(z.x, z.y + z.height - 6, barW, 6);
                }

                // Grace timer bar
                if (z.graceExpiresMs > 0) {
                    long remaining = z.graceExpiresMs - System.currentTimeMillis();
                    double pct = Math.max(0, Math.min(1.0, remaining / 5000.0));
                    int barW = (int)(z.width * pct);
                    g2.setColor(new Color(255, 165, 0, 160));
                    g2.fillRect(z.x, z.y, barW, 4);
                }

                // Border
                Color border = z.contested ? CONTESTED
                    : (z.ownerPlayerId != null ? playerColor(z.ownerPlayerId) : ZONE_BORDER);
                g2.setColor(border);
                g2.setStroke(new BasicStroke(z.contested ? 2.5f : 1.5f));
                g2.drawRect(z.x, z.y, z.width, z.height);

                // Zone label
                String label = "ZONE " + z.id;
                String sublabel = z.contested ? "CONTESTED"
                    : (z.ownerPlayerId != null ? z.ownerPlayerId : "UNCLAIMED");

                g2.setFont(new Font("Monospaced", Font.BOLD, 13));
                FontMetrics fm = g2.getFontMetrics();
                int lx = z.x + (z.width - fm.stringWidth(label)) / 2;
                g2.setColor(TEXT_MAIN);
                g2.drawString(label, lx, z.y + 22);

                g2.setFont(new Font("Monospaced", Font.PLAIN, 11));
                fm = g2.getFontMetrics();
                lx = z.x + (z.width - fm.stringWidth(sublabel)) / 2;
                g2.setColor(z.contested ? CONTESTED : TEXT_DIM);
                g2.drawString(sublabel, lx, z.y + 38);
            }
        }

        // ── Items ─────────────────────────────────────────────────────
        private void drawItems(Graphics2D g2, List<ItemInfo> items) {
            if (items == null) return;
            long now = System.currentTimeMillis();
            for (ItemInfo item : items) {
                // Pulsing glow effect
                float pulse = 0.5f + 0.5f * (float) Math.sin(now / 300.0 + item.x);
                Color base = item.isWeapon ? WEAPON_CLR : ENERGY_CLR;

                // Glow
                RadialGradientPaint glow = new RadialGradientPaint(
                    new Point2D.Float(item.x, item.y),
                    ITEM_R * 2.5f,
                    new float[]{0f, 1f},
                    new Color[]{new Color(base.getRed(), base.getGreen(), base.getBlue(), (int)(120 * pulse)),
                                new Color(base.getRed(), base.getGreen(), base.getBlue(), 0)});
                g2.setPaint(glow);
                g2.fillOval(item.x - ITEM_R * 2, item.y - ITEM_R * 2, ITEM_R * 4, ITEM_R * 4);

                // Core circle
                g2.setColor(base);
                g2.fillOval(item.x - ITEM_R, item.y - ITEM_R, ITEM_R * 2, ITEM_R * 2);

                // Icon text
                g2.setColor(BG_DARK);
                g2.setFont(new Font("Dialog", Font.BOLD, 10));
                FontMetrics fm = g2.getFontMetrics();
                String icon = item.isWeapon ? "❄" : "⚡";
                g2.drawString(icon, item.x - fm.stringWidth(icon)/2, item.y + fm.getAscent()/2 - 1);
            }
        }

        // ── Players ───────────────────────────────────────────────────
        private void drawPlayers(Graphics2D g2, List<PlayerInfo> players,
                                  GameStateSnapshot snap) {
            if (players == null) return;

            // Draw shadow / freeze ring first
            for (PlayerInfo p : players) {
                Color c = playerColor(p.id);
                if (p.frozen) {
                    // Icy ring
                    g2.setColor(new Color(FROZEN_CLR.getRed(), FROZEN_CLR.getGreen(), FROZEN_CLR.getBlue(), 80));
                    g2.fillOval(p.x - PLAYER_R - 6, p.y - PLAYER_R - 6,
                                (PLAYER_R + 6) * 2, (PLAYER_R + 6) * 2);
                    g2.setColor(FROZEN_CLR);
                    g2.setStroke(new BasicStroke(2.5f));
                    g2.drawOval(p.x - PLAYER_R - 6, p.y - PLAYER_R - 6,
                                (PLAYER_R + 6) * 2, (PLAYER_R + 6) * 2);
                } else {
                    // Soft glow
                    RadialGradientPaint glow = new RadialGradientPaint(
                        new Point2D.Float(p.x, p.y), PLAYER_R + 8,
                        new float[]{0f, 1f},
                        new Color[]{new Color(c.getRed(), c.getGreen(), c.getBlue(), 60),
                                    new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)});
                    g2.setPaint(glow);
                    g2.fillOval(p.x - PLAYER_R - 8, p.y - PLAYER_R - 8,
                                (PLAYER_R + 8) * 2, (PLAYER_R + 8) * 2);
                }
            }

            // Draw bodies
            for (PlayerInfo p : players) {
                Color c = p.frozen ? FROZEN_CLR : playerColor(p.id);
                boolean isMe = p.id.equals(myPlayerId);

                // Body fill
                g2.setColor(c);

                // g2.fillOval(p.x - PLAYER_R, p.y - PLAYER_R, PLAYER_R * 2, PLAYER_R * 2);

                BufferedImage sprite;

                if (p.id.equals(myPlayerId)) {
                    sprite = blueSprite;   // you are blue
                } else {
                    sprite = redSprite;    // others are red
                }

                g2.drawImage(
                    sprite,
                    p.x - PLAYER_R,
                    p.y - PLAYER_R,
                    PLAYER_R * 2,
                    PLAYER_R * 2,
                    null
                );

                // Outline — thicker for self
                g2.setColor(isMe ? Color.WHITE : c.brighter());
                g2.setStroke(new BasicStroke(isMe ? 2.5f : 1.5f));
                g2.drawOval(p.x - PLAYER_R, p.y - PLAYER_R, PLAYER_R * 2, PLAYER_R * 2);

                // Weapon indicator (small orange dot)
                if (p.hasWeapon) {
                    g2.setColor(WEAPON_CLR);
                    g2.fillOval(p.x + PLAYER_R - 5, p.y - PLAYER_R - 1, 8, 8);
                    g2.setColor(BG_DARK);
                    g2.setStroke(new BasicStroke(1));
                    g2.drawOval(p.x + PLAYER_R - 5, p.y - PLAYER_R - 1, 8, 8);
                }

                // Name tag
                g2.setFont(new Font("Monospaced", Font.BOLD, 11));
                FontMetrics fm = g2.getFontMetrics();
                String nameTag = p.name;
                int nx = p.x - fm.stringWidth(nameTag) / 2;
                int ny = p.y - PLAYER_R - 5;
                // Shadow
                g2.setColor(new Color(0, 0, 0, 160));
                g2.drawString(nameTag, nx + 1, ny + 1);
                g2.setColor(isMe ? Color.WHITE : c.brighter());
                g2.drawString(nameTag, nx, ny);

                // Score below
                g2.setFont(new Font("Monospaced", Font.PLAIN, 10));
                fm = g2.getFontMetrics();
                String scoreStr = String.valueOf(p.score);
                g2.setColor(TEXT_DIM);
                g2.drawString(scoreStr, p.x - fm.stringWidth(scoreStr) / 2,
                              p.y + PLAYER_R + 12);

                // Frozen countdown
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
            }
        }

        // ── Notification overlay ──────────────────────────────────────
        private void drawNotification(Graphics2D g2) {
            if (notification == null) return;
            if (System.currentTimeMillis() > notifyExpiresMs) { notification = null; return; }

            long remaining = notifyExpiresMs - System.currentTimeMillis();
            float alpha = Math.min(1f, remaining / 500f);

            g2.setFont(new Font("Monospaced", Font.BOLD, 28));
            FontMetrics fm = g2.getFontMetrics();
            int tx = (ARENA_W - fm.stringWidth(notification)) / 2;
            int ty = ARENA_H / 2 - 30;

            // Semi-transparent backing
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

        // Title
        JLabel roundOver = styled("ROUND OVER", 30, Font.BOLD, ACCENT);
        roundOver.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(roundOver);
        content.add(Box.createVerticalStrut(10));

        // Winner
        String winnerName = players.stream()
            .filter(p -> p.id.equals(winnerId)).map(p -> p.name)
            .findFirst().orElse(winnerId);
        JLabel winnerLbl = styled("Winner: " + winnerName, 18, Font.BOLD, ENERGY_CLR);
        winnerLbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(winnerLbl);
        content.add(Box.createVerticalStrut(20));

        // Leaderboard
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
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    private Color playerColor(String playerId) {
        int idx = colorMap.getOrDefault(playerId, 0);
        return PLAYER_COLORS[idx % PLAYER_COLORS.length];
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
}
