package server;

import shared.Messages.GameStateSnapshot;
import shared.Messages.PlayerInfo;
import shared.Messages.ZoneInfo;
import shared.Messages.ItemInfo;
import server.GameState;
import server.ClientManager;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.IOException;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/**
 * ServerGUI — Admin monitor for ChronoArena.
 *
 * Shows:
 *   - Live mini-map of the arena (player positions, zones, items)
 *   - Player table: ID, name, score, frozen status, weapon, position
 *   - Round timer and tick counter
 *   - Kill-switch buttons per player
 *   - Console log panel (mirrors server stdout)
 *   - Zone ownership summary
 *
 * Threading: repainted by a Swing Timer on the EDT.
 * GameLoop calls updateSnapshot() from its own thread — safe because
 * snapshot is written atomically via volatile.
 *
 * Usage in Server.java:
 *   ServerGUI gui = new ServerGUI(gameState, clientManager);
 *   gui.setVisible(true);
 *   // Then start a Swing Timer to call gui.refresh() at ~10 Hz
 */
public class ServerGUI extends JFrame {

    // ── Colours (dark admin theme) ────────────────────────────────────
    private static final Color BG          = new Color(0x12121f);
    private static final Color PANEL_BG    = new Color(0x1a1a2e);
    private static final Color ACCENT      = new Color(0x00e5ff);
    private static final Color ACCENT2     = new Color(0xff6b35);
    private static final Color TEXT        = new Color(0xe0e0ff);
    private static final Color TEXT_DIM    = new Color(0x888aaa);
    private static final Color ZONE_FREE   = new Color(0x2a2a4a);
    private static final Color CONTESTED   = new Color(0xff4444);
    private static final Color[] P_COLORS  = {
        new Color(0x4fc3f7), new Color(0xef5350), new Color(0x66bb6a),
        new Color(0xffa726), new Color(0xce93d8), new Color(0x26c6da),
        new Color(0xd4e157), new Color(0xff7043)
    };

    // ── Arena display constants ───────────────────────────────────────
    private static final int MAP_W = 400;  // mini-map width  (half of 800)
    private static final int MAP_H = 300;  // mini-map height (half of 600)
    private static final double SCALE = 0.5;

    // ── Server references ─────────────────────────────────────────────
    private final GameState     gameState;
    private final ClientManager clientManager;

    // ── Swing components ──────────────────────────────────────────────
    private MiniMapPanel       miniMap;
    private DefaultTableModel  playerTableModel;
    private JTable             playerTable;
    private JLabel             timerLabel;
    private JLabel             tickLabel;
    private JTextArea          logArea;
    private JPanel             zonePanel;
    private JPanel             killButtonPanel;

    // ── Snapshot (written by any thread, read by EDT) ─────────────────
    private volatile GameStateSnapshot latestSnap;

    // ── Color assignment ──────────────────────────────────────────────
    private final Map<String, Integer> colorMap   = new LinkedHashMap<>();
    private int                        colorIndex = 0;

    // ─────────────────────────────────────────────────────────────────
    public ServerGUI(GameState gameState, ClientManager clientManager) {
        super("ChronoArena — Server Monitor");
        this.gameState     = gameState;
        this.clientManager = clientManager;

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); // don't kill server on close
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                setVisible(false); // just hide, keep server running
            }
        });
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout(8, 8));
        getRootPane().setBorder(new EmptyBorder(8, 8, 8, 8));

        // ── Top bar ───────────────────────────────────────────────────
        // Initialize labels before buildTopBar() so it can place existing refs
        tickLabel  = label("Tick: 0", 13, Font.PLAIN, TEXT_DIM);
        timerLabel = label("03:00",   20, Font.BOLD,  TEXT);
        JPanel topBar = buildTopBar();
        add(topBar, BorderLayout.NORTH);

        // ── Centre: mini-map + player table ───────────────────────────
        JPanel centre = new JPanel(new BorderLayout(8, 0));
        centre.setBackground(BG);

        miniMap = new MiniMapPanel();
        miniMap.setPreferredSize(new Dimension(MAP_W, MAP_H));
        centre.add(miniMap, BorderLayout.WEST);

        JPanel rightCentre = new JPanel(new BorderLayout(0, 8));
        rightCentre.setBackground(BG);

        playerTableModel = buildTableModel();
        playerTable      = new JTable(playerTableModel);
        styleTable(playerTable);
        JScrollPane tableScroll = new JScrollPane(playerTable);
        tableScroll.setPreferredSize(new Dimension(460, MAP_H));
        tableScroll.getViewport().setBackground(PANEL_BG);
        tableScroll.setBorder(BorderFactory.createLineBorder(ACCENT.darker(), 1));
        rightCentre.add(tableScroll, BorderLayout.CENTER);

        killButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        killButtonPanel.setBackground(PANEL_BG);
        killButtonPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(ACCENT2.darker()),
            "Kill Switch", 0, 0,
            new Font("Monospaced", Font.BOLD, 11), ACCENT2));
        rightCentre.add(killButtonPanel, BorderLayout.SOUTH);

        centre.add(rightCentre, BorderLayout.CENTER);
        add(centre, BorderLayout.CENTER);

        // ── Bottom: zone summary + log ────────────────────────────────
        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setBackground(BG);

        zonePanel = new JPanel();
        zonePanel.setLayout(new BoxLayout(zonePanel, BoxLayout.X_AXIS));
        zonePanel.setBackground(PANEL_BG);
        zonePanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(ACCENT.darker()),
            "Zones", 0, 0,
            new Font("Monospaced", Font.BOLD, 11), ACCENT));
        zonePanel.setPreferredSize(new Dimension(MAP_W, 110));
        bottom.add(zonePanel, BorderLayout.WEST);

        logArea = new JTextArea(6, 40);
        logArea.setEditable(false);
        logArea.setBackground(new Color(0x0a0a18));
        logArea.setForeground(new Color(0x88ff88));
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        logArea.setCaretColor(Color.GREEN);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0x33aa33)),
            "Server Log", 0, 0,
            new Font("Monospaced", Font.BOLD, 11), new Color(0x88ff88)));
        bottom.add(logScroll, BorderLayout.CENTER);

        add(bottom, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);
        setResizable(true);

        // ── Refresh timer: 10 Hz is plenty for an admin view ──────────
        new javax.swing.Timer(100, e -> refresh()).start();

        // ── Redirect System.out to the log panel ──────────────────────
        redirectStdout();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────

    /** Called by GameLoop (or a Swing Timer) to push a new snapshot. */
    public void updateSnapshot(GameStateSnapshot snap) {
        latestSnap = snap; // volatile write — safe from any thread
    }

    /** Called by the refresh timer on the EDT to repaint everything. */
    public void refresh() {
        GameStateSnapshot snap = latestSnap;
        if (snap == null) snap = gameState.snapshot(); // fall back to live state

        // Assign stable colours
        if (snap.players != null) {
            for (PlayerInfo p : snap.players) {
                colorMap.computeIfAbsent(p.id, k -> colorIndex++ % P_COLORS.length);
            }
        }

        updateTimerBar(snap);
        updatePlayerTable(snap);
        updateZonePanel(snap);
        updateKillButtons();
        miniMap.setSnap(snap);
        miniMap.repaint();
    }

    /** Append a line to the server log panel. Thread-safe. */
    public void log(String line) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(line + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    // ─────────────────────────────────────────────────────────────────
    //  Build helpers
    // ─────────────────────────────────────────────────────────────────

    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(new Color(0x08081a));
        bar.setBorder(new EmptyBorder(6, 12, 6, 12));

        JLabel title = label("CHRONOARENA  SERVER MONITOR", 16, Font.BOLD, ACCENT);
        bar.add(title, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 0));
        right.setOpaque(false);

        // tickLabel and timerLabel are already initialized in the constructor
        right.add(tickLabel);
        right.add(timerLabel);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private DefaultTableModel buildTableModel() {
        String[] cols = {"ID", "Name", "Score", "Pos", "Frozen", "Weapon", "Zone"};
        return new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    private void styleTable(JTable t) {
        t.setBackground(PANEL_BG);
        t.setForeground(TEXT);
        t.setGridColor(new Color(0x2a2a4a));
        t.setFont(new Font("Monospaced", Font.PLAIN, 12));
        t.getTableHeader().setBackground(new Color(0x0d0d20));
        t.getTableHeader().setForeground(ACCENT);
        t.getTableHeader().setFont(new Font("Monospaced", Font.BOLD, 12));
        t.setRowHeight(22);
        t.setSelectionBackground(new Color(0x2a2a5a));
        t.setSelectionForeground(Color.WHITE);
        t.setShowVerticalLines(false);
    }

    // ─────────────────────────────────────────────────────────────────
    //  Refresh helpers
    // ─────────────────────────────────────────────────────────────────

    private void updateTimerBar(GameStateSnapshot snap) {
        long sec = snap.roundTimeRemainingMs / 1000;
        timerLabel.setText(String.format("%02d:%02d", sec / 60, sec % 60));
        timerLabel.setForeground(sec <= 30 ? ACCENT2 : TEXT);
        tickLabel.setText("Tick: " + snap.tickNumber);
    }

    private void updatePlayerTable(GameStateSnapshot snap) {
        playerTableModel.setRowCount(0);
        if (snap.players == null) return;

        // Find zone owner map
        Map<String, String> playerZone = new HashMap<>();
        if (snap.zones != null) {
            for (ZoneInfo z : snap.zones) {
                if (z.ownerPlayerId != null) {
                    playerZone.put(z.ownerPlayerId, "Zone " + z.id
                        + (z.contested ? " ⚔" : ""));
                }
            }
        }

        List<PlayerInfo> sorted = new ArrayList<>(snap.players);
        sorted.sort((a, b) -> b.score - a.score);

        for (PlayerInfo p : sorted) {
            playerTableModel.addRow(new Object[]{
                p.id,
                p.name,
                p.score,
                "(" + p.x + ", " + p.y + ")",
                p.frozen ? "❄ YES" : "no",
                p.hasWeapon ? "❄ yes" : "-",
                playerZone.getOrDefault(p.id, "-")
            });
        }
    }

    private void updateZonePanel(GameStateSnapshot snap) {
        zonePanel.removeAll();
        if (snap.zones == null) { zonePanel.revalidate(); return; }

        for (ZoneInfo z : snap.zones) {
            JPanel card = new JPanel();
            card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
            card.setBackground(new Color(0x20203a));
            card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(z.contested ? CONTESTED
                    : (z.ownerPlayerId != null ? playerColor(z.ownerPlayerId) : ZONE_FREE), 2),
                new EmptyBorder(6, 10, 6, 10)));
            card.setMaximumSize(new Dimension(120, 100));

            card.add(label("ZONE " + z.id, 13, Font.BOLD, TEXT));

            String statusTxt;
            Color  statusCol;
            if (z.contested) {
                statusTxt = "CONTESTED";
                statusCol = CONTESTED;
            } else if (z.ownerPlayerId != null) {
                statusTxt = z.ownerPlayerId;
                statusCol = playerColor(z.ownerPlayerId);
            } else {
                statusTxt = "free";
                statusCol = TEXT_DIM;
            }
            card.add(label(statusTxt, 11, Font.BOLD, statusCol));

            if (z.captureProgress > 0 && z.captureProgress < 1.0) {
                card.add(label(String.format("cap %.0f%%", z.captureProgress * 100),
                               10, Font.PLAIN, ACCENT));
            }
            if (z.graceExpiresMs > 0) {
                long ms = z.graceExpiresMs - System.currentTimeMillis();
                if (ms > 0) {
                    card.add(label(String.format("grace %.1fs", ms / 1000.0),
                                   10, Font.PLAIN, new Color(0xffa500)));
                }
            }

            zonePanel.add(card);
            zonePanel.add(Box.createHorizontalStrut(8));
        }
        zonePanel.revalidate();
        zonePanel.repaint();
    }

    private void updateKillButtons() {
        Set<String> connected = clientManager.getConnectedPlayerIds();

        // Remove buttons for players no longer connected
        Set<String> existing = new HashSet<>();
        for (Component c : killButtonPanel.getComponents()) {
            if (c instanceof JButton btn) {
                String pid = (String) btn.getClientProperty("playerId");
                if (pid != null) {
                    if (!connected.contains(pid)) killButtonPanel.remove(c);
                    else existing.add(pid);
                }
            }
        }

        // Add buttons for new players
        for (String pid : connected) {
            if (!existing.contains(pid)) {
                JButton btn = new JButton("KILL " + pid);
                btn.putClientProperty("playerId", pid);
                btn.setFont(new Font("Monospaced", Font.BOLD, 11));
                btn.setForeground(Color.WHITE);
                btn.setBackground(new Color(0x8b0000));
                btn.setBorderPainted(false);
                btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                btn.addActionListener(e -> {
                    int confirm = JOptionPane.showConfirmDialog(this,
                        "Kill player " + pid + "?", "Confirm Kill",
                        JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (confirm == JOptionPane.YES_OPTION) {
                        clientManager.killClient(pid);
                        log("[KillSwitch] Killed " + pid);
                    }
                });
                killButtonPanel.add(btn);
            }
        }
        killButtonPanel.revalidate();
        killButtonPanel.repaint();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Mini-map panel
    // ─────────────────────────────────────────────────────────────────

    private class MiniMapPanel extends JPanel {
        private volatile GameStateSnapshot snap;

        MiniMapPanel() {
            setBackground(new Color(0x0d0d1a));
            setBorder(BorderFactory.createLineBorder(ACCENT.darker(), 1));
        }

        void setSnap(GameStateSnapshot s) { snap = s; }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            GameStateSnapshot s = snap;
            if (s == null) {
                g2.setColor(TEXT_DIM);
                g2.setFont(new Font("Monospaced", Font.PLAIN, 12));
                g2.drawString("Waiting...", 10, 20);
                return;
            }

            // Grid
            g2.setColor(new Color(0x1a1a33));
            g2.setStroke(new BasicStroke(0.5f));
            for (int x = 0; x <= MAP_W; x += 20) g2.drawLine(x, 0, x, MAP_H);
            for (int y = 0; y <= MAP_H; y += 20) g2.drawLine(0, y, MAP_W, y);

            // Zones
            if (s.zones != null) {
                for (ZoneInfo z : s.zones) {
                    int zx = (int)(z.x * SCALE), zy = (int)(z.y * SCALE);
                    int zw = (int)(z.width * SCALE), zh = (int)(z.height * SCALE);

                    Color fill = z.contested ? new Color(CONTESTED.getRed(),
                        CONTESTED.getGreen(), CONTESTED.getBlue(), 60)
                        : (z.ownerPlayerId != null
                            ? new Color(playerColor(z.ownerPlayerId).getRed(),
                                        playerColor(z.ownerPlayerId).getGreen(),
                                        playerColor(z.ownerPlayerId).getBlue(), 50)
                            : new Color(ZONE_FREE.getRed(), ZONE_FREE.getGreen(),
                                        ZONE_FREE.getBlue(), 50));
                    g2.setColor(fill);
                    g2.fillRect(zx, zy, zw, zh);

                    g2.setColor(z.contested ? CONTESTED
                        : (z.ownerPlayerId != null ? playerColor(z.ownerPlayerId) : ZONE_FREE));
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.drawRect(zx, zy, zw, zh);

                    g2.setFont(new Font("Monospaced", Font.BOLD, 9));
                    g2.setColor(Color.WHITE);
                    g2.drawString(z.id, zx + 3, zy + 12);
                }
            }

            // Items
            if (s.items != null) {
                for (ItemInfo item : s.items) {
                    int ix = (int)(item.x * SCALE), iy = (int)(item.y * SCALE);
                    g2.setColor(item.isWeapon ? new Color(0xff6b35) : new Color(0xffd700));
                    g2.fillOval(ix - 4, iy - 4, 8, 8);
                }
            }

            // Players
            if (s.players != null) {
                for (PlayerInfo p : s.players) {
                    int px = (int)(p.x * SCALE), py = (int)(p.y * SCALE);
                    Color c = p.frozen ? new Color(0x88ccff) : playerColor(p.id);
                    g2.setColor(c);
                    g2.fillOval(px - 5, py - 5, 10, 10);
                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(1.2f));
                    g2.drawOval(px - 5, py - 5, 10, 10);

                    // Name tag
                    g2.setFont(new Font("Monospaced", Font.BOLD, 8));
                    g2.setColor(c.brighter());
                    g2.drawString(p.id, px + 6, py - 2);
                }
            }

            // Legend
            g2.setFont(new Font("Monospaced", Font.PLAIN, 9));
            g2.setColor(TEXT_DIM);
            g2.drawString("Mini-map  (1:2 scale)", 4, MAP_H - 4);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Stdout redirect
    // ─────────────────────────────────────────────────────────────────

    /**
     * Tees System.out so every println also appears in the GUI log panel.
     * Call once during construction.
     */
  private void redirectStdout() {
    PrintStream original = System.out;

    OutputStream teeStream = new OutputStream() {
        private final StringBuilder lineBuf = new StringBuilder();

        @Override
        public void write(int b) throws IOException {
            // Write to the original stream first so the terminal stays live
            original.write(b);
            
            char c = (char) b;
            if (c == '\n') {
                String line = lineBuf.toString();
                lineBuf.setLength(0);
                log(line);
            } else if (c != '\r') {
                lineBuf.append(c);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            // Avoid calling write(int) in a loop for better performance
            original.write(b, off, len);
            String str = new String(b, off, len);
            
            // Handle multiple lines in a single write buffer
            String[] lines = str.split("\\r?\\n", -1);
            for (int i = 0; i < lines.length; i++) {
                lineBuf.append(lines[i]);
                if (i < lines.length - 1) {
                    log(lineBuf.toString());
                    lineBuf.setLength(0);
                }
            }
        }

        @Override
        public void flush() throws IOException { 
            original.flush(); 
        }
    };

    // Ensure the new PrintStream is assigned correctly
    System.setOut(new PrintStream(teeStream, true));
}
    

    // ─────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────

    private Color playerColor(String id) {
        return P_COLORS[colorMap.getOrDefault(id, 0) % P_COLORS.length];
    }

    private static JLabel label(String text, int size, int style, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Monospaced", style, size));
        l.setForeground(color);
        return l;
    }
}