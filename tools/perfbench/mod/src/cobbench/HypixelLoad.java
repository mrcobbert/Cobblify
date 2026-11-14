package cobbench;

/**
 * Scene C's Hypixel-like load (pure JDK, copied verbatim into the Forge twin): a Bed Wars sidebar and chat
 * at 8 lines/s in Hypixel's formats, delivered through the normal incoming-chat handler so every client's
 * chat hooks run. No server, no account: everything is local to the single-player client.
 */
public final class HypixelLoad {
    public static final long CHAT_INTERVAL_NS = 125_000_000L;
    public static final String TITLE = "§e§lBED WARS";
    public static final String[] SIDEBAR = {
            "§711/14/26 §8m42F",
            "",
            "§fDiamond II in §a4:52",
            " ",
            "§c§lR §fRed: §a✔",
            "§9§lB §fBlue: §a✔",
            "§a§lG §fGreen: §a✔",
            "§e§lY §fYellow: §c✘",
            "§b§lA §fAqua: §a2",
            "§f§lW §fWhite: §a✔",
            "§d§lP §fPink: §a✔ §7YOU",
            "§8§lS §fGray: §a✔",
            "  ",
            "§ewww.hypixel.net",
    };
    private static final String[] CHAT = {
            "§7[§f%d✩§7] §6[MVP§2++§6] Player%d§f: gg",
            "§cPlayer%d §7was knocked into the void by §9Player%d§7. §b§lFINAL KILL!",
            "§6+%d coins! (Final Kill)",
            "§f§lBED DESTRUCTION > §eYellow Bed §7was destroyed by §aPlayer%d§7!",
            "§7[§f%d✩§7] §b[MVP§c+§b] Player%d§f: who has diamonds",
            "§aYou purchased §6Wool",
            "§9Party §8> §b[MVP§c+§b] Player%d§f: rush red",
            "§dFrom §6[MVP§2++§6] Player%d§7: duel?",
            "§cPlayer%d §7was shot by §ePlayer%d§7.",
            "§b+%d Bed Wars Experience (Time Played)",
    };
    private static int counter;

    private HypixelLoad() {}

    public static String nextLine() {
        int k = counter++;
        String f = CHAT[k % CHAT.length];
        return String.format(f, 100 + k % 900, 1 + (k * 7) % 97);
    }

    /** Hypixel's invisible, unique score holder for line i. */
    public static String holder(int i) {
        return "§" + Integer.toHexString(i % 16) + "§r" + (i >= 16 ? "§" + Integer.toHexString(i / 16) : "");
    }

    public static String prefix(String line) {
        return line.length() <= 16 ? line : line.substring(0, 16);
    }

    public static String suffix(String line) {
        return line.length() <= 16 ? "" : line.substring(16, Math.min(line.length(), 32));
    }
}
