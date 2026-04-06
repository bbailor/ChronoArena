package gui;

import shared.Messages.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HeadlessUI — a minimal, no-GUI implementation of GameUI.
 *
 * Prints game events to stdout. Reads player name from stdin.
 * Action input is driven by a simple stdin command loop running on a background thread
 * so it doesn't block the network layer.
 *
 * Use this to:
 *   - Verify the server/client logic works before building a GUI
 *   - Run automated tests or bots
 *   - Understand exactly what data the GUI layer receives
 *
 * Commands (type in the terminal while running):
 *   w / a / s / d        — move
 *   ul / ur / dl / dr    — diagonal move
 *   f                    — fire freeze ray
 *   q                    — quit
 */
public class HeadlessUI implements GameUI {

    private final AtomicReference<ActionType> pendingAction =
            new AtomicReference<>(ActionType.NONE);

    private String myPlayerId;
    private String playerName;

    // Lobby results captured during promptPlayerName()
    private String enteredName;
    private String enteredIp;   // may be null → use game.properties value

    // ------------------------------------------------------------------ //
    //  Lifecycle
    // ------------------------------------------------------------------ //

    @Override
    public void onGameStart(String myPlayerId, String playerName) {
        this.myPlayerId = myPlayerId;
        this.playerName = playerName;
        System.out.println("\n[UI] Game started! You are " + playerName + " (" + myPlayerId + ")");
        System.out.println("[UI] Commands: w/a/s/d  ul/ur/dl/dr  f=freeze  q=quit");
        startInputThread();
    }

    @Override
    public void onStateUpdate(GameStateSnapshot snapshot) {
        // Lightweight console update — print every 20 ticks (~1 s) to avoid spam
        if (snapshot.tickNumber % 20 != 0) return;

        long sec = snapshot.roundTimeRemainingMs / 1000;
        System.out.printf("[UI] Tick %-5d  Time left: %02d:%02d%n",
                snapshot.tickNumber, sec / 60, sec % 60);

        // My score
        Integer myScore = snapshot.scores.get(myPlayerId);
        System.out.println("     My score: " + myScore);

        // Zone summary
        for (ZoneInfo z : snapshot.zones) {
            String owner = z.ownerPlayerId != null ? z.ownerPlayerId : "unclaimed";
            String status = z.contested ? "CONTESTED" : owner;
            System.out.printf("     Zone %s: %s%n", z.id, status);
        }

        // Items on map
        System.out.println("     Items on map: " + snapshot.items.size());
    }

    @Override
    public void onRoundEnd(String winnerId, Map<String, Integer> scores,
                            List<PlayerInfo> players) {
        System.out.println("\n══════════════════════════════");
        System.out.println("  ROUND OVER");
        System.out.println("══════════════════════════════");

        // Resolve winner name
        String winnerName = players.stream()
                .filter(p -> p.id.equals(winnerId))
                .map(p -> p.name)
                .findFirst().orElse(winnerId);
        System.out.println("  Winner: " + winnerName);
        System.out.println();

        // Ranked scores
        scores.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> {
                    String name = players.stream()
                            .filter(p -> p.id.equals(e.getKey()))
                            .map(p -> p.name).findFirst().orElse(e.getKey());
                    System.out.printf("  %-12s  %d pts%n", name, e.getValue());
                });

        System.out.println("══════════════════════════════\n");
        System.exit(0);
    }

    @Override
    public void onKilled() {
        System.out.println("[UI] You were removed by the server.");
        System.exit(0);
    }

    @Override
    public void onDisconnected() {
        System.out.println("[UI] Lost connection to server.");
        System.exit(0);
    }

    // ------------------------------------------------------------------ //
    //  Input
    // ------------------------------------------------------------------ //

    @Override
    public ActionType getNextAction() {
        // Return and clear the pending action atomically
        return pendingAction.getAndSet(ActionType.NONE);
    }

    /** Background thread reads stdin commands and sets pendingAction */
    private void startInputThread() {
        Thread t = new Thread(() -> {
            Scanner sc = new Scanner(System.in);
            while (sc.hasNextLine()) {
                String cmd = sc.nextLine().trim().toLowerCase();
                ActionType action = switch (cmd) {
                    case "w", "up"    -> ActionType.MOVE_UP;
                    case "s", "down"  -> ActionType.MOVE_DOWN;
                    case "a", "left"  -> ActionType.MOVE_LEFT;
                    case "d", "right" -> ActionType.MOVE_RIGHT;
                    case "ul"         -> ActionType.MOVE_UL;
                    case "ur"         -> ActionType.MOVE_UR;
                    case "dl"         -> ActionType.MOVE_DL;
                    case "dr"         -> ActionType.MOVE_DR;
                    case "f", "fire"  -> ActionType.FREEZE_RAY;
                    case "q", "quit"  -> { System.exit(0); yield ActionType.NONE; }
                    default           -> ActionType.NONE;
                };
                pendingAction.set(action);
            }
        }, "HeadlessUI-Input");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ //
    //  Lobby
    // ------------------------------------------------------------------ //

    @Override
    public String promptPlayerName(String defaultServerIp) {
        Scanner sc = new Scanner(System.in);
        System.out.print("Enter your name: ");
        enteredName = sc.nextLine().trim();
        if (enteredName.isEmpty()) enteredName = "Player";

        System.out.print("Server IP [" + defaultServerIp + "]: ");
        String ip = sc.nextLine().trim();
        enteredIp = ip.isEmpty() ? null : ip;

        return enteredName;
    }

    @Override
    public String getServerIpOverride() {
        return enteredIp;
    }
}
