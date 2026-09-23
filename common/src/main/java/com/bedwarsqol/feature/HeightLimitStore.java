package com.bedwarsqol.feature;

import com.bedwarsqol.config.AtomicFileWrite;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists the height limits {@link HeightLimitCore} learns in-game as {@code <map name>=<maxY>}
 * lines in {@code ~/.cobblify/height-limits.txt} — the same well-known directory on Forge and
 * Lunar as the diag log, so one path works for every install and the file is human-editable.
 * Silent on I/O failure; a missing or unreadable file is just an empty map.
 *
 * <p>A save renders the whole file and hands it to {@link AtomicFileWrite} instead of truncating
 * the live one and printing into it: a crash between those two moments used to leave a header-only
 * file, and every limit the mod had learned over months of games was lost.
 */
public final class HeightLimitStore {

    private HeightLimitStore() {
    }

    public static File defaultFile() {
        return new File(new File(System.getProperty("user.home"), ".cobblify"), "height-limits.txt");
    }

    public static Map<String, Integer> load(File file) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (file == null || !file.isFile()) return out;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int eq = s.lastIndexOf('=');
                if (eq <= 0) continue;
                String name = s.substring(0, eq).trim();
                try {
                    int y = Integer.parseInt(s.substring(eq + 1).trim());
                    if (!name.isEmpty() && y > 0) out.put(name, y);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    public static void save(File file, Map<String, Integer> learnedByName) {
        if (file == null || learnedByName == null) return;
        StringBuilder text = new StringBuilder();
        text.append("# Bedwars build limits learned in-game (highest placeable Y). Overrides the shipped table.\n");
        for (Map.Entry<String, Integer> e : learnedByName.entrySet()) {
            text.append(e.getKey().replace('=', ' ').trim()).append('=').append(e.getValue()).append('\n');
        }
        try {
            // AtomicFileWrite creates the parent directory and renames the finished bytes into place.
            AtomicFileWrite.write(file, text.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Silent by contract, and AtomicFileWrite can also throw unchecked (a bad path).
        }
    }
}
