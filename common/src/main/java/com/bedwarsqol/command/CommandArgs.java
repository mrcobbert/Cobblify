package com.bedwarsqol.command;

/**
 * Argument tidying shared by both command front-ends. Vanilla and Forge hand a command the raw line
 * split on a single space, so every doubled space in {@code /cobblify mode  solo} arrives as an empty
 * token and the subcommand reads it as its argument ("Unknown mode ''"). Weave splits on
 * {@code \s+}, so on Lunar this only ever has nothing to do.
 */
public final class CommandArgs {

    private CommandArgs() {}

    /** {@code args} without its null or empty tokens, order kept. A null array yields an empty one. */
    public static String[] dropEmpty(String[] args) {
        if (args == null) return new String[0];
        int kept = 0;
        for (String a : args) {
            if (a != null && !a.isEmpty()) kept++;
        }
        String[] out = new String[kept];
        int i = 0;
        for (String a : args) {
            if (a != null && !a.isEmpty()) out[i++] = a;
        }
        return out;
    }
}
