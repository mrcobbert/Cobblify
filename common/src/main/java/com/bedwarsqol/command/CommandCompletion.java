package com.bedwarsqol.command;

import com.bedwarsqol.stats.StatsMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Tab completion for {@code /cobblify} (and its aliases), shared by the Forge and Weave command
 * classes so both offer the same words. {@code args} are the tokens after the command word with the
 * word being completed last — {@code ""} when the cursor sits after a space — which is the shape
 * both loaders hand over (Forge splits on {@code " "} with a {@code -1} limit; Weave drops the
 * command word after a {@code \s+} split that keeps a trailing empty token).
 */
public final class CommandCompletion {

    /** First-word subcommands, in help order; a bare player name is also accepted but not suggested. */
    public static final String[] SUBCOMMANDS = {
            "help", "mode", "stats", "statsurl", "statstoken", "urchin", "seraph",
            "urchinkey", "seraphkey", "session", "heightlimit", "settings",
    };

    private CommandCompletion() {
    }

    /** Suggestions for the last token of {@code args}; empty when nothing applies. */
    public static List<String> suggest(String[] args) {
        if (args == null || args.length == 0) return Collections.emptyList();
        if (args.length == 1) return matchingLastWord(args, SUBCOMMANDS);
        if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.US)) {
                case "mode":    return matchingLastWord(args, StatsMode.OPTIONS);
                case "session": return matchingLastWord(args, new String[]{"reset"});
                default:        return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }

    /** The options that start with the last token, case-insensitively, in the options' own order. */
    static List<String> matchingLastWord(String[] args, String[] options) {
        String last = args[args.length - 1];
        String prefix = last == null ? "" : last.toLowerCase(Locale.US);
        List<String> out = new ArrayList<String>();
        for (String o : options) {
            if (o.toLowerCase(Locale.US).startsWith(prefix)) out.add(o);
        }
        return out;
    }
}
