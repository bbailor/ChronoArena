package server;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Manages persistent player progression stored in players.json.
 *
 * File format (pretty-printed for readability):
 * {
 *   "Alice": { "wins": 3, "unlockedColors": [0,1,2,3] },
 *   "Bob":   { "wins": 0, "unlockedColors": [0] }
 * }
 *
 * Color unlock thresholds (wins needed):
 *   0 wins  → color 0  (blue   — always unlocked)
 *   1 win   → color 1  (red)
 *   2 wins  → color 2  (green)
 *   3 wins  → color 3  (orange)
 *   5 wins  → color 4  (purple)
 *   8 wins  → color 5  (cyan)
 *  12 wins  → color 6  (yellow-green)
 *  18 wins  → color 7  (deep orange)
 *
 * Thread-safe: all public methods are synchronized.
 */
public class PlayerRegistry {

    // Parallel arrays: index = colorIndex, value = wins required
    public static final int[]    UNLOCK_THRESHOLDS = { 0, 0, 0, 0, 0, 1, 2, 4 };
    public static final String[] COLOR_NAMES       = {
        "Blue", "Red", "Yellow", "Green", "Orange", "LightBlueGuy", "Purple", "Black"
    };

    private static final String FILE_PATH = "players.json";

    // In-memory store: username → PlayerRecord
    private final Map<String, PlayerRecord> registry = new LinkedHashMap<>();

    public static class PlayerRecord {
        public int  wins;
        public List<Integer> unlockedColors;   // sorted list of color indices

        PlayerRecord() {
            this.wins           = 0;
            this.unlockedColors = new ArrayList<>(List.of(0)); // color 0 always unlocked
        }

        /** Recompute unlocked colors from win count (call after loading or awarding win) */
        void refreshUnlocks() {
            unlockedColors.clear();
            for (int i = 0; i < UNLOCK_THRESHOLDS.length; i++) {
                if (wins >= UNLOCK_THRESHOLDS[i]) {
                    unlockedColors.add(i);
                }
            }
            if (unlockedColors.isEmpty()) unlockedColors.add(0);
        }
    }

    // ── Constructor: load file on startup ───────────────────────────────
    public PlayerRegistry() {
        load();
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Look up (or auto-create) a player by name.
     * Returns a copy safe to send to the client.
     */
    public synchronized PlayerRecord getOrCreate(String name) {
        registry.computeIfAbsent(name, k -> {
            PlayerRecord r = new PlayerRecord();
            r.refreshUnlocks();
            return r;
        });
        save();
        return registry.get(name);
    }

    /**
     * Record a win for the named player.
     * Recomputes unlocks and persists immediately.
     */
    public synchronized void recordWin(String name) {
        PlayerRecord r = registry.computeIfAbsent(name, k -> new PlayerRecord());
        r.wins++;
        r.refreshUnlocks();
        System.out.println("[Registry] " + name + " now has " + r.wins + " win(s). "
                + "Unlocked colors: " + r.unlockedColors);
        save();
    }

    // ── Persistence (minimal hand-rolled JSON — no external deps) ───────

    private void load() {
        File f = new File(FILE_PATH);
        if (!f.exists()) {
            System.out.println("[Registry] No players.json found — starting fresh.");
            return;
        }
        try {
            String raw = Files.readString(f.toPath()).trim();
            // Strip outer braces
            raw = raw.substring(1, raw.lastIndexOf('}')).trim();
            if (raw.isEmpty()) return;

            // Split top-level entries by looking for  "Name": {  ...  },
            // We walk char-by-char to handle nested braces correctly.
            List<String> entries = splitTopLevelEntries(raw);
            for (String entry : entries) {
                int colon = entry.indexOf(':');
                if (colon < 0) continue;
                String nameRaw  = entry.substring(0, colon).trim();
                String valueRaw = entry.substring(colon + 1).trim();
                String name = unquote(nameRaw);

                PlayerRecord rec = new PlayerRecord();
                rec.wins = parseIntField(valueRaw, "wins");
                rec.refreshUnlocks();
                registry.put(name, rec);
            }
            System.out.println("[Registry] Loaded " + registry.size() + " player(s) from " + FILE_PATH);
        } catch (Exception e) {
            System.err.println("[Registry] Failed to parse " + FILE_PATH + ": " + e.getMessage()
                    + " — starting fresh.");
            registry.clear();
        }
    }

    private synchronized void save() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(FILE_PATH))) {
            pw.println("{");
            int i = 0;
            for (Map.Entry<String, PlayerRecord> e : registry.entrySet()) {
                PlayerRecord r = e.getValue();
                String comma = (++i < registry.size()) ? "," : "";
                pw.println("  " + quote(e.getKey()) + ": {");
                pw.println("    \"wins\": " + r.wins + ",");
                pw.print  ("    \"unlockedColors\": [");
                for (int j = 0; j < r.unlockedColors.size(); j++) {
                    pw.print(r.unlockedColors.get(j));
                    if (j < r.unlockedColors.size() - 1) pw.print(", ");
                }
                pw.println("]");
                pw.println("  }" + comma);
            }
            pw.println("}");
        } catch (IOException ex) {
            System.err.println("[Registry] Save failed: " + ex.getMessage());
        }
    }

    // ── Mini JSON helpers (no external library needed) ──────────────────

    private static List<String> splitTopLevelEntries(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if      (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        String last = s.substring(start).trim();
        if (!last.isEmpty()) out.add(last);
        return out;
    }

    private static String unquote(String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\""))
            return s.substring(1, s.length() - 1);
        return s;
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static int parseIntField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return 0;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return 0;
        StringBuilder sb = new StringBuilder();
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c) || c == '-') sb.append(c);
            else if (sb.length() > 0) break;
        }
        try { return Integer.parseInt(sb.toString()); }
        catch (NumberFormatException e) { return 0; }
    }
}
