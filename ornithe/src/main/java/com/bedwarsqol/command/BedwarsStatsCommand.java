package com.bedwarsqol.command;

import com.mojang.authlib.GameProfile;
import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.feature.ModChat;
import com.bedwarsqol.stats.BedwarsStats;
import com.bedwarsqol.stats.MojangNameResolver;
import com.bedwarsqol.stats.StatsCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Prints a player's BedWars stats card to local chat. No longer a command of its own — {@link
 * BedwarsQolCommand} ({@code /bw}) is the single entry point and calls {@link #showStats(String[])}
 * for {@code /bw <player>} and {@code /bw stats [player]} (no player defaults to yourself).
 */
public final class BedwarsStatsCommand {

    private BedwarsStatsCommand() {}

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BedwarsQol-BwCmd");
        t.setDaemon(true);
        return t;
    });

    /** Fetch and print the stats card for {@code args[0]}, or the local player when {@code args} is empty. */
    public static void showStats(String[] args) {
        if (BedwarsQol.config == null || !BedwarsQol.config.playerStats) {
            sendChat("§cPlayer Stats is disabled. Enable it in /cobblify.");
            return;
        }

        boolean backend = BedwarsQol.config.backendTarget().isConfigured();
        if (!backend) {
            sendChat("§cNo stats backend URL. Set §f/cobblify statsurl <url>§c.");
            return;
        }

        final String name = args.length > 0
                ? args[0]
                : Minecraft.getMinecraft().getSession().getUsername();

        sendChat("§7Fetching Bedwars stats for §f" + name + "§7...");
        final UUID local = localUuid(name); // client state is read here, never on EXEC
        EXEC.submit(() -> {
            try {
                UUID uuid = local != null ? local : MojangNameResolver.resolve(name);
                if (uuid == null) {
                    printCard(name, BedwarsStats.nicked());
                    return;
                }
                printCard(name, StatsCache.fetchNow(uuid, name, true));
            } catch (StatsCache.RateLimitedException e) {
                String msg = e.getMessage();
                sendChat("§eRate limited. " + (msg != null ? msg : "Try again shortly."));
            } catch (Throwable t) {
                Throwable cause = t;
                while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                String msg = cause.getMessage();
                if (msg == null || msg.isEmpty()) msg = cause.getClass().getSimpleName();
                sendChat("§cFailed to fetch stats: §f" + msg);
            }
        });
    }

    /**
     * The UUID for {@code name} from the local player or the tab list, or null when the name is not
     * there (the caller then asks Mojang off-thread).
     *
     * <p><b>Client thread only</b>: the tab map is a HashMap mutated by S38 packets, so iterating it
     * from the command's executor throws ConcurrentModificationException during lobby churn.
     */
    private static UUID localUuid(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return null;
        if (mc.thePlayer != null && mc.thePlayer.getGameProfile() != null
                && name.equalsIgnoreCase(mc.thePlayer.getGameProfile().getName())
                && mc.thePlayer.getGameProfile().getId() != null) {
            return mc.thePlayer.getGameProfile().getId();
        }
        NetHandlerPlayClient net = mc.getNetHandler();
        if (net == null) return null;
        NetworkPlayerInfo info = net.getPlayerInfo(name);
        if (info != null && info.getGameProfile() != null && info.getGameProfile().getId() != null) {
            return info.getGameProfile().getId();
        }
        for (NetworkPlayerInfo i : net.getPlayerInfoMap()) {
            GameProfile gp = i.getGameProfile();
            if (gp != null && gp.getId() != null && gp.getName() != null
                    && gp.getName().equalsIgnoreCase(name)) {
                return gp.getId();
            }
        }
        return null;
    }

    private static void printCard(String name, BedwarsStats stats) {
        String header = "§7§m----------§r §6§lBedwars §r§7§m----------";
        String footer = "§7§m------------------------------";
        sendChat(header);
        switch (stats.state) {
            case NICKED:
                sendChat("§e" + name + " §7is nicked or not found.");
                sendChat(footer);
                return;
            case ERROR:
                sendChat("§cCould not fetch stats for §e" + name + "§c.");
                sendChat(footer);
                return;
            case NEVER_PLAYED:
                sendChat("§e" + (stats.displayName != null ? stats.displayName : name) + " §7has never played Bedwars.");
                sendChat(footer);
                return;
            default: break;
        }

        String displayName = stats.displayName != null ? stats.displayName : name;
        StringBuilder nameLine = new StringBuilder("§e").append(displayName);
        if (!stats.rankPrefix.isEmpty()) nameLine.append(" ").append(stats.rankPrefix);
        sendChat(nameLine.toString());

        BedwarsStats.ModeStats o = stats.overall;
        sendChat("§6Overall");
        sendChat(line("Final Kills", num(o.finalKills))
                + sep() + label("Final Deaths") + ": §f" + num(o.finalDeaths)
                + sep() + label("FKDR") + ": " + BedwarsStats.fkdrColor(o.fkdr) + fmt2(o.fkdr));
        sendChat(line("Wins", num(o.wins))
                + sep() + label("Losses") + ": §f" + num(o.losses)
                + sep() + label("WLR") + ": §f" + fmt2(o.wlr));
        sendChat(line("Kills", num(o.kills))
                + sep() + label("Deaths") + ": §f" + num(o.deaths)
                + sep() + label("K/D") + ": §f" + fmt2(o.kd));

        if (stats.solo.hasGames() || stats.doubles.hasGames()
                || stats.threes.hasGames() || stats.fours.hasGames()) {
            sendChat("§6Modes");
            if (stats.solo.hasGames())    sendChat(modeLine("Solo", stats.solo));
            if (stats.doubles.hasGames()) sendChat(modeLine("Doubles", stats.doubles));
            if (stats.threes.hasGames())  sendChat(modeLine("3v3v3v3", stats.threes));
            if (stats.fours.hasGames())   sendChat(modeLine("4v4v4v4", stats.fours));
        }
        sendChat(footer);
    }

    /** One compact line summarizing a single mode. */
    private static String modeLine(String label, BedwarsStats.ModeStats m) {
        return "§7" + label
                + " §8| §7FKDR " + BedwarsStats.fkdrColor(m.fkdr) + fmt2(m.fkdr)
                + " §8| §7WLR §f" + fmt2(m.wlr)
                + " §8| §7Finals §f" + num(m.finalKills)
                + " §8| §7Wins §f" + num(m.wins);
    }

    private static String line(String name, String value) {
        return label(name) + ": " + (value.startsWith("§") ? value : "§f" + value);
    }

    private static String label(String s) {
        return "§7" + s;
    }

    private static String sep() {
        return " §8| ";
    }

    private static String num(int n) {
        return String.format(Locale.US, "%,d", n);
    }

    private static String fmt2(double d) {
        return String.format(Locale.US, "%.2f", d);
    }

    /**
     * Print a line locally. Most calls come from the fetch executor, so the only look at client
     * state happens inside the scheduled task, on the client thread: the player can be gone by the
     * time it runs (a disconnect between fetch and reply), and checking here as well would be both
     * an off-thread read and a stale answer.
     */
    private static void sendChat(String msg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        IChatComponent c = ModChat.mark(new ChatComponentText(msg));
        mc.addScheduledTask(() -> {
            if (mc.thePlayer != null) mc.thePlayer.addChatMessage(c);
        });
    }
}
