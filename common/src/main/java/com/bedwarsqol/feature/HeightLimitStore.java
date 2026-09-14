package com.bedwarsqol.feature;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists the height limits {@link HeightLimitCore} learns in-game as {@code <map name>=<maxY>}
 * lines in {@code ~/.cobblify/height-limits.txt} — the same well-known directory on Forge and
 * Lunar as the diag log, so one path works for every install and the file is human-editable.
 * Silent on I/O failure; a missing or unreadable file is just an empty map.
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
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            out.println("# Bedwars build limits learned in-game (highest placeable Y). Overrides the shipped table.");
            for (Map.Entry<String, Integer> e : learnedByName.entrySet()) {
                out.println(e.getKey().replace('=', ' ').trim() + "=" + e.getValue());
            }
        } catch (IOException ignored) {
        }
    }
}
