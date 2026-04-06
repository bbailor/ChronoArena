package gui;

import shared.Messages.*;

import java.util.List;
import java.util.Map;

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

    // ── Add your fields here ──────────────────────────────────────────── //
    //
    // Example for Swing:
    //   private JFrame frame;
    //   private GamePanel panel;          // your custom JPanel subclass
    //   private volatile GameStateSnapshot latestSnapshot;
    //   private final Set<Integer> heldKeys = new HashSet<>();
    //   private volatile ActionType pendingAction = ActionType.NONE;
    //
    // ─────────────────────────────────────────────────────────────────── //


    // ------------------------------------------------------------------ //
    //  Lifecycle
    // ------------------------------------------------------------------ //

    /**
     * Called once after a successful join.
     * Show your main game window / start your render loop here.
     */
    @Override
    public void onGameStart(String myPlayerId, String playerName) {
        // TODO: open your window / start render loop
        //
        // Swing example:
        //   frame = new JFrame("ChronoArena — " + playerName);
        //   panel = new GamePanel(myPlayerId);
        //   frame.add(panel);
        //   frame.pack();
        //   frame.setVisible(true);
        //   setupKeyBindings();          // add KeyListeners to frame
        //   startMovementTimer();        // javax.swing.Timer polling getNextAction()

        System.out.println("[CustomUI] Game started for " + playerName + " (" + myPlayerId + ")");
    }

    /**
     * Called every server tick (~20 Hz). This is your repaint trigger.
     * The snapshot contains everything: player positions, zone states, items, scores.
     *
     * IMPORTANT: if using Swing, do NOT paint directly here (wrong thread).
     * Store the snapshot and call SwingUtilities.invokeLater(() -> panel.repaint()).
     */
    @Override
    public void onStateUpdate(GameStateSnapshot snapshot) {
        // TODO: store snapshot and trigger a redraw
        //
        // Swing example:
        //   latestSnapshot = snapshot;
        //   SwingUtilities.invokeLater(() -> panel.repaint());
        //
        // The snapshot fields you'll use most in paintComponent():
        //   snapshot.players        — List<PlayerInfo>  x, y, id, name, frozen, hasWeapon, score
        //   snapshot.zones          — List<ZoneInfo>    x, y, width, height, ownerPlayerId, contested, captureProgress
        //   snapshot.items          — List<ItemInfo>    x, y, isWeapon
        //   snapshot.scores         — Map<String,Integer>  playerId → score
        //   snapshot.roundTimeRemainingMs
    }

    /**
     * Called when the round ends. Show a leaderboard / end screen.
     */
    @Override
    public void onRoundEnd(String winnerId, Map<String, Integer> scores,
                            List<PlayerInfo> players) {
        // TODO: show your end screen / leaderboard
        //
        // Swing example:
        //   SwingUtilities.invokeLater(() -> {
        //       new LeaderboardDialog(frame, winnerId, scores, players).setVisible(true);
        //       System.exit(0);
        //   });

        System.out.println("[CustomUI] Round ended. Winner: " + winnerId);
        System.exit(0);
    }

    /**
     * Called when the server admin kills this client.
     */
    @Override
    public void onKilled() {
        // TODO: show "removed by server" message and close
        System.out.println("[CustomUI] Killed by server.");
        System.exit(0);
    }

    /**
     * Called when the TCP connection drops unexpectedly.
     */
    @Override
    public void onDisconnected() {
        // TODO: show disconnect message and close
        System.out.println("[CustomUI] Disconnected.");
        System.exit(0);
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
        // TODO: return the current player input
        return ActionType.NONE;
    }

    // ------------------------------------------------------------------ //
    //  Lobby
    // ------------------------------------------------------------------ //

    /**
     * Called before connecting. Return the player name, or null to cancel.
     * You can show a splash screen/dialog here, or just return a fixed string.
     *
     * @param defaultServerIp  the IP from game.properties (show as a hint)
     */
    @Override
    public String promptPlayerName(String defaultServerIp) {
        // TODO: show your lobby/login screen and return the name entered
        //
        // Swing dialog example:
        //   JTextField nameField = new JTextField("Player");
        //   JTextField ipField   = new JTextField(defaultServerIp);
        //   ...
        //   int result = JOptionPane.showConfirmDialog(null, panel, "Join", JOptionPane.OK_CANCEL_OPTION);
        //   if (result != JOptionPane.OK_OPTION) return null;
        //   serverIpOverride = ipField.getText().trim();
        //   return nameField.getText().trim();

        return "CustomPlayer";   // replace with real input
    }

    /**
     * Return a server IP override entered in the lobby screen, or null to use game.properties.
     * Only called after promptPlayerName() has returned.
     */
    @Override
    public String getServerIpOverride() {
        // TODO: return the IP the player typed in the lobby, or null
        return null;
    }
}
