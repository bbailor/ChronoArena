package gui;

import shared.Messages.*;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * GameUI — the only contract between the client networking layer and any GUI.
 *
 * The client engine calls these methods. Your GUI implements them however it likes:
 * Swing, JavaFX, a terminal renderer, a web socket bridge, a test stub — anything.
 *
 * HOW TO PLUG IN YOUR OWN GUI
 * ───────────────────────────
 * 1. Create a class that implements GameUI (e.g. MySwingScreen implements GameUI)
 * 2. Implement every method below
 * 3. In Client.java, replace:
 *        GameUI ui = new SwingUI();
 *    with:
 *        GameUI ui = new MySwingScreen(...);
 * 4. Compile and run — the network layer is unchanged.
 *
 * THREADING NOTES
 * ───────────────
 * - onLobbyJoined() / onLobbyUpdate() are called from the main client thread.
 * - onStateUpdate() is called from the TCP listener thread at ~20 Hz.
 *   If you are using Swing, wrap repaints inside SwingUtilities.invokeLater().
 * - getNextAction() is called from a background polling thread every 50 ms.
 *   Make it thread-safe (e.g. use a volatile field or a concurrent queue).
 */
public interface GameUI {

    // ------------------------------------------------------------------ //
    //  Lobby lifecycle
    // ------------------------------------------------------------------ //

    /**
     * Called after a successful JOIN_ACK, while waiting in the lobby.
     * Show a lobby screen: connected player list, game config (host only),
     * and a "Start Game" button (host only) or "Waiting…" label.
     *
     * Use {@code messageSender} to send TcpMessages back to the server:
     *   - {@code new TcpMessage(MsgType.LOBBY_START, null)}         — host starts game
     *   - {@code new TcpMessage(MsgType.LOBBY_CONFIG_UPDATE, cfg)}  — host changes config
     *
     * @param myPlayerId    server-assigned ID for this client (e.g. "P1")
     * @param playerName    the name the player entered at login
     * @param isHost        true if this client is the lobby host
     * @param initialState  current lobby state at the moment of joining
     * @param messageSender consumer that queues a TcpMessage to be sent to the server
     */
    void onLobbyJoined(String myPlayerId, String playerName, boolean isHost,
                       LobbyState initialState, Consumer<TcpMessage> messageSender);

    /**
     * Called whenever the lobby state changes (player joins/leaves, host updates config).
     * Refresh the player list and displayed settings.
     */
    void onLobbyUpdate(LobbyState state);

    // ------------------------------------------------------------------ //
    //  Game lifecycle
    // ------------------------------------------------------------------ //

    /**
     * Called once after the lobby transitions to game phase.
     * Show your main game window / start your render loop here.
     *
     * @param myPlayerId  the server-assigned ID for this client (e.g. "P1")
     * @param playerName  the human-readable name the player entered at login
     */
    void onGameStart(String myPlayerId, String playerName);

    /**
     * Called every time the server pushes a new authoritative state snapshot.
     * This is your repaint / redraw trigger (~20 Hz).
     */
    void onStateUpdate(GameStateSnapshot snapshot);

    /**
     * Called when the round timer reaches zero and final scores are available.
     * Show a leaderboard / end screen here.
     *
     * @param winnerId   player ID of the winner
     * @param scores     map of playerId → final score
     * @param players    full player list (use to resolve IDs → names)
     */
    void onRoundEnd(String winnerId, Map<String, Integer> scores, List<PlayerInfo> players);

    /**
     * Called when the server admin uses the kill-switch on this client.
     * Show an appropriate message and close/disable the UI.
     */
    void onKilled();

    /**
     * Called when the TCP connection to the server drops unexpectedly.
     * Show a disconnect message and close/disable the UI.
     */
    void onDisconnected();

    // ------------------------------------------------------------------ //
    //  Input
    // ------------------------------------------------------------------ //

    /**
     * Called by the client input-polling thread every ~50 ms.
     * Return the action the local player wants to perform this tick,
     * or ActionType.NONE if no input is pending.
     *
     * YOUR IMPLEMENTATION: read from a key-state map, a queue, a joystick, etc.
     * This method MUST be non-blocking and thread-safe.
     */
    ActionType getNextAction();

    // ------------------------------------------------------------------ //
    //  Pre-lobby: name / server entry
    // ------------------------------------------------------------------ //

    /**
     * Called before connecting to the server.
     * Return the player name the user entered, or null to cancel/exit.
     *
     * @param defaultServerIp  the IP from game.properties (display as a hint)
     */
    String promptPlayerName(String defaultServerIp);

    /**
     * If your lobby UI also lets the player override the server IP,
     * return it here after promptPlayerName() has been called.
     * Return null to use the value from game.properties.
     */
    String getServerIpOverride();
}
