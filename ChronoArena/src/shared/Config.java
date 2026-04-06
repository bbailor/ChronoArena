package shared;

import java.io.*;
import java.util.Properties;

/**
 * Loads game.properties from the working directory (or several fallback locations).
 * No IP or port is ever hardcoded - all values come from this file.
 */
public class Config {
    private static final Properties props = new Properties();

    static {
        // Search order:
        //  1. -Dconfig=/path/to/game.properties  (explicit override)
        //  2. ./game.properties                   (current working dir)
        //  3. ../game.properties                  (one level up, e.g. running from out/)
        //  4. Classpath root                      (bundled inside JAR)
        String override = System.getProperty("config");
        String[] fileCandidates = (override != null)
            ? new String[]{ override }
            : new String[]{ "game.properties", "../game.properties" };

        boolean loaded = false;

        for (String path : fileCandidates) {
            File f = new File(path);
            if (f.exists()) {
                try (InputStream in = new FileInputStream(f)) {
                    props.load(in);
                    System.out.println("[Config] Loaded: " + f.getAbsolutePath());
                    loaded = true;
                    break;
                } catch (IOException ignored) {}
            }
        }

        if (!loaded) {
            // Try classpath (works when game.properties is bundled in the JAR)
            try (InputStream in = Config.class.getClassLoader()
                    .getResourceAsStream("game.properties")) {
                if (in != null) {
                    props.load(in);
                    System.out.println("[Config] Loaded from classpath (JAR).");
                    loaded = true;
                }
            } catch (IOException ignored) {}
        }

        if (!loaded) {
            System.err.println("[Config] game.properties not found - using built-in defaults.");
            loadDefaults();
        }
    }

    private static void loadDefaults() {
        props.setProperty("server.ip",               "127.0.0.1");
        props.setProperty("server.tcp.port",         "5000");
        props.setProperty("server.udp.port",         "5001");
        props.setProperty("round.duration.seconds",  "180");
        props.setProperty("tick.rate.ms",            "50");
        props.setProperty("max.players",             "8");
        props.setProperty("grace.timer.ms",          "5000");
        props.setProperty("freeze.duration.ms",      "3000");
        props.setProperty("zone.capture.time.ms",    "2000");
        props.setProperty("item.spawn.interval.ms",  "8000");
        props.setProperty("points.per.zone.tick",    "2");
        props.setProperty("item.energy.value",       "10");
        props.setProperty("item.weapon.value",       "0");
        props.setProperty("tag.penalty.points",      "10");
    }

    public static String get(String key) {
        return props.getProperty(key);
    }

    public static int getInt(String key) {
        String val = props.getProperty(key);
        if (val == null) throw new RuntimeException(
            "[Config] Missing key: '" + key + "' - check game.properties");
        return Integer.parseInt(val.trim());
    }

    public static long getLong(String key) {
        String val = props.getProperty(key);
        if (val == null) throw new RuntimeException(
            "[Config] Missing key: '" + key + "' - check game.properties");
        return Long.parseLong(val.trim());
    }
}
