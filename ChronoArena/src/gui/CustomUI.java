package gui;

import java.util.List;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import shared.Messages.*;

/**
 * CustomUI — your blank canvas for building any GUI you want.
 *
 * This class already implements GameUI so it compiles and runs immediately.
 * Every method has a TODO comment explaining what to put there.
 *
 * QUICK START
 * ───────────
 * 1. Fill in the methods below with your rendering/input code
 * 2. In Client.java, change:
 *        GameUI ui = new HeadlessUI();
 *    to:
 *        GameUI ui = new CustomUI();
 * 3. Recompile (./build.sh) and run
 *
 * COMMON PATTERNS
 * ───────────────
 * Swing:
 *   - Keep a JFrame field; call frame.setVisible(true) in onGameStart()
 *   - Call SwingUtilities.invokeLater(() -> panel.repaint()) in onStateUpdate()
 *   - Store the latest snapshot in a volatile field so paintComponent() can read it
 *   - Use a Set<Integer> of held key codes + a javax.swing.Timer for smooth movement
 *
 * JavaFX:
 *   - Launch your Application from onGameStart()
 *   - Use Platform.runLater(() -> ...) in onStateUpdate()
 *
 * Terminal (e.g. Lanterna):
 *   - Build a TextGUI; update character cells in onStateUpdate()
 *
 * Web / REST bridge:
 *   - Start an embedded HTTP server in onGameStart()
 *   - Push snapshots via WebSocket in onStateUpdate()
 */
public class CustomUI implements GameUI {

    // Example for Swing:
    //   private JFrame frame;
    //   private GamePanel panel;          // your custom JPanel subclass
    //   private volatile GameStateSnapshot latestSnapshot;
    //   private final Set<Integer> heldKeys = new HashSet<>();
    //   private volatile ActionType pendingAction = ActionType.NONE;

    // The latest snapshot from the server - volatile so the EDT reads fresh data
    private volatile GameStateSnapshot latestSnapshot;
    private String myPlayerId;

    // Key state - track which keys are held for smooth movement
    private final Set<Integer> heldKeys = Collections.synchronizedSet(new HashSet<>());

    // Pending one-shot action (freeze ray) - consumed once per press
    private final AtomicReference<ActionType> pendingAction =
            new AtomicReference<>(ActionType.NONE);

    private JFrame frame;
    private ArenaPanel arenaPanel;

    // ------------------------------------------------------------------ //
    //  Lifecycle
    // ------------------------------------------------------------------ //

    /**
     * Called once after a successful join.
     * Show your main game window / start your render loop here.
     */
    @Override
    public void onGameStart(String myPlayerId, String playerName) {
        this.myPlayerId = myPlayerId;

        SwingUtilities.invokeLater(() -> {
            frame = new JFrame("ChronoArena — " + playerName);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            arenaPanel = new ArenaPanel();
            frame.add(arenaPanel, BorderLayout.CENTER);

            // Key bindings attached to the frame
            frame.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    heldKeys.add(e.getKeyCode());

                    // One-shot actions on key press (not held)
                    if (e.getKeyCode() == KeyEvent.VK_SPACE) {
                        pendingAction.set(ActionType.FREEZE_RAY);
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

    /**
     * Called every server tick (~20 Hz). This is your repaint trigger.
     * The snapshot contains everything: player positions, zone states, items, scores.
     *
     * IMPORTANT: if using Swing, do NOT paint directly here (wrong thread).
     * Store the snapshot and call SwingUtilities.invokeLater(() -> panel.repaint()).
     */
    // @Override
    // public void onStateUpdate(GameStateSnapshot snapshot) {
    //     // TODO: store snapshot and trigger a redraw
    //     //
    //     // Swing example:
    //     //   latestSnapshot = snapshot;
    //     //   SwingUtilities.invokeLater(() -> panel.repaint());
    //     //
    //     // The snapshot fields you'll use most in paintComponent():
    //     //   snapshot.players        — List<PlayerInfo>  x, y, id, name, frozen, hasWeapon, score
    //     //   snapshot.zones          — List<ZoneInfo>    x, y, width, height, ownerPlayerId, contested, captureProgress
    //     //   snapshot.items          — List<ItemInfo>    x, y, isWeapon
    //     //   snapshot.scores         — Map<String,Integer>  playerId → score
    //     //   snapshot.roundTimeRemainingMs
    // }

        @Override
    public void onStateUpdate(GameStateSnapshot snapshot) {
        latestSnapshot = snapshot;   // store (volatile - thread safe)
        if (arenaPanel != null) {
            // Schedule repaint on the EDT - never paint from the TCP thread
            SwingUtilities.invokeLater(arenaPanel::repaint);
        }
    }

    /**
     * Called when the round ends. Show a leaderboard / end screen.
     */
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
                    sb.append(String.format("%-12s %d pts\n", name, e.getValue()));
                });
            JOptionPane.showMessageDialog(frame, sb.toString(), "Final Scores",
                    JOptionPane.INFORMATION_MESSAGE);
            System.exit(0);
        });
    }

    /**
     * Called when the server admin kills this client.
     */
    @Override
    public void onKilled() {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(frame, "Removed by server.",
                    "Disconnected", JOptionPane.WARNING_MESSAGE);
            System.exit(0);
        });
    }


    /**
     * Called when the TCP connection drops unexpectedly.
     */
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

    /**
     * Called by a background thread every ~50 ms. Return the player's current action.
     * Must be non-blocking and thread-safe.
     *
     * Swing keyboard example — store held keys in a Set, compute action here:
     *
     *   private final Set<Integer> heldKeys = Collections.synchronizedSet(new HashSet<>());
     *   // In your KeyListener:
     *   //   keyPressed  → heldKeys.add(e.getKeyCode())
     *   //   keyReleased → heldKeys.remove(e.getKeyCode())
     *
     *   @Override public ActionType getNextAction() {
     *       boolean up    = heldKeys.contains(KeyEvent.VK_W);
     *       boolean down  = heldKeys.contains(KeyEvent.VK_S);
     *       boolean left  = heldKeys.contains(KeyEvent.VK_A);
     *       boolean right = heldKeys.contains(KeyEvent.VK_D);
     *       if (up && left)  return ActionType.MOVE_UL;
     *       if (up && right) return ActionType.MOVE_UR;
     *       if (up)          return ActionType.MOVE_UP;
     *       // ... etc
     *       if (heldKeys.contains(KeyEvent.VK_SPACE)) return ActionType.FREEZE_RAY;
     *       return ActionType.NONE;
     *   }
     */
  @Override
    public ActionType getNextAction() {
        // Check for one-shot actions first (freeze ray)
        ActionType oneShot = pendingAction.getAndSet(ActionType.NONE);
        if (oneShot != ActionType.NONE) return oneShot;

        // Then check held movement keys
        boolean up    = heldKeys.contains(KeyEvent.VK_W) ||
                        heldKeys.contains(KeyEvent.VK_UP);
        boolean down  = heldKeys.contains(KeyEvent.VK_S) ||
                        heldKeys.contains(KeyEvent.VK_DOWN);
        boolean left  = heldKeys.contains(KeyEvent.VK_A) ||
                        heldKeys.contains(KeyEvent.VK_LEFT);
        boolean right = heldKeys.contains(KeyEvent.VK_D) ||
                        heldKeys.contains(KeyEvent.VK_RIGHT);

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
    private String enteredName = "Player";
    private String enteredIp   = null;

    /**
     * Called before connecting. Return the player name, or null to cancel.
     * You can show a splash screen/dialog here, or just return a fixed string.
     *
     * @param defaultServerIp  the IP from game.properties (show as a hint)
     */
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

    /**
     * Return a server IP override entered in the lobby screen, or null to use game.properties.
     * Only called after promptPlayerName() has returned.
     */
    @Override
    public String getServerIpOverride() { return enteredIp; }


    class ArenaPanel extends JPanel {

        static final int ARENA_W = 800;
        static final int ARENA_H = 600;
        static final int HUD_H   = 60;

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
                g.drawString("Waiting for server...", 20, 20);
                return;
            }

            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                RenderingHints.VALUE_ANTIALIAS_ON);

            drawHUD(g2, snap);
            drawZones(g2, snap);
            drawItems(g2, snap);
            drawPlayers(g2, snap);
        }

        private void drawHUD(Graphics2D g, GameStateSnapshot snap) {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, getWidth(), HUD_H);

            // Timer
            long sec = snap.roundTimeRemainingMs / 1000;
            g.setColor(Color.YELLOW);
            g.setFont(new Font("Monospaced", Font.BOLD, 20));
            g.drawString(String.format("TIME  %02d:%02d", sec / 60, sec % 60), 20, 35);

            // Scores — sorted highest first
            int x = 200;
            snap.scores.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> {
                    // Highlight your own score
                });
            // (fill in score rendering here)
        }

        private void drawZones(Graphics2D g, GameStateSnapshot snap) {
            for (ZoneInfo z : snap.zones) {
                // Fill color based on state
                if (z.contested) {
                    g.setColor(new Color(200, 100, 0, 80));
                } else if (z.ownerPlayerId != null) {
                    g.setColor(new Color(0, 180, 80, 80));
                } else {
                    g.setColor(new Color(100, 100, 100, 60));
                }
                g.fillRect(z.x, z.y + HUD_H, z.width, z.height);

                // Border
                g.setColor(z.contested ? Color.ORANGE :
                           z.ownerPlayerId != null ? Color.GREEN : Color.GRAY);
                g.drawRect(z.x, z.y + HUD_H, z.width, z.height);

                // Label
                g.setColor(Color.WHITE);
                g.setFont(new Font("Monospaced", Font.BOLD, 13));
                g.drawString("ZONE " + z.id, z.x + 6, z.y + HUD_H + 18);
                g.drawString(z.contested ? "CONTESTED" :
                             z.ownerPlayerId != null ? "OWNED" : "FREE",
                             z.x + 6, z.y + HUD_H + 34);

                // Capture progress bar along bottom of zone
                if (z.captureProgress > 0 && z.captureProgress < 1.0) {
                    g.setColor(Color.YELLOW);
                    g.fillRect(z.x, z.y + HUD_H + z.height - 5,
                               (int)(z.width * z.captureProgress), 5);
                }
            }
        }

        private void drawItems(Graphics2D g, GameStateSnapshot snap) {
            for (ItemInfo item : snap.items) {
                int iy = item.y + HUD_H;
                if (item.isWeapon) {
                    g.setColor(Color.CYAN);
                    g.fillOval(item.x - 10, iy - 10, 20, 20);
                    g.setColor(Color.WHITE);
                    g.setFont(new Font("Monospaced", Font.BOLD, 9));
                    g.drawString("ICE", item.x - 8, iy + 4);
                } else {
                    g.setColor(Color.YELLOW);
                    g.fillOval(item.x - 10, iy - 10, 20, 20);
                }
            }
        }

        private void drawPlayers(Graphics2D g, GameStateSnapshot snap) {
            Color[] colors = {
                new Color(0, 120, 220), new Color(220, 60, 60),
                new Color(60, 180, 60), new Color(200, 160, 0)
            };
            int colorIdx = 0;
            Map<String, Color> playerColors = new LinkedHashMap<>();
            for (PlayerInfo p : snap.players) {
                playerColors.put(p.id, colors[colorIdx++ % colors.length]);
            }

            for (PlayerInfo p : snap.players) {
                int py = p.y + HUD_H;
                Color c = playerColors.get(p.id);

                // Frozen ring
                if (p.frozen) {
                    g.setColor(new Color(100, 200, 255, 100));
                    g.fillOval(p.x - 18, py - 18, 36, 36);
                }

                // Player circle — brighter if it's you
                g.setColor(p.id.equals(myPlayerId) ? c.brighter() : c);
                g.fillOval(p.x - 12, py - 12, 24, 24);
                g.setColor(Color.WHITE);
                g.drawOval(p.x - 12, py - 12, 24, 24);

                // Name
                g.setFont(new Font("Monospaced", Font.BOLD, 11));
                g.setColor(Color.WHITE);
                g.drawString(p.id.equals(myPlayerId) ? "YOU" : p.name,
                             p.x - 10, py - 15);

                // Score above player
                g.setFont(new Font("Monospaced", Font.PLAIN, 10));
                g.drawString(String.valueOf(p.score), p.x - 6, py - 26);
            }
        }
    }
}

