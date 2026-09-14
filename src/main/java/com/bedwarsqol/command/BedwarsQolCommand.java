package com.bedwarsqol.command;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.ChatNameTags;
import com.bedwarsqol.feature.HeightLimitCore;
import com.bedwarsqol.feature.HeightLimitWatch;
import com.bedwarsqol.feature.ModChat;
import com.bedwarsqol.feature.SessionStats;
import com.bedwarsqol.feature.SessionStatsWatch;
import com.bedwarsqol.gui.SettingsGui;
import com.bedwarsqol.stats.BackendTarget;
import com.bedwarsqol.stats.ProviderKeySubmitter;
import com.bedwarsqol.stats.StatsCache;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /cobblify} — the mod's single command (aliases {@code /bw}, {@code /bedwarsqol}, {@code /hypixelclient}).
 * <ul>
 *   <li>{@code /cobblify} — open the settings GUI</li>
 *   <li>{@code /cobblify <player>} / {@code /cobblify stats [player]} — print a Bedwars stats card</li>
 *   <li>{@code /cobblify mode <auto|all|solo|2s|3s|4s>} — set the in-chat FKDR gamemode (back-patches chat)</li>
 *   <li>{@code /cobblify statsurl|statstoken ...} — configure the stats backend</li>
 *   <li>{@code /cobblify help} — list all subcommands</li>
 * </ul>
 * A first token that isn't one of the reserved subcommands is treated as a player name for the card.
 */
public class BedwarsQolCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "cobblify";
    }

    @Override
    public List<String> getCommandAliases() {
        return Arrays.asList("bw", "bedwarsqol", "hypixelclient");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/cobblify [help|mode|stats|statsurl|statstoken]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        // Guard the whole dispatch so a failing subcommand can never escape as an unhandled command.
        try {
            dispatch(sender, args);
        } catch (Throwable t) {
            try { send(sender, "§cCommand failed: §f" + describe(t)); } catch (Throwable ignored) { }
        }
    }

    private void dispatch(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            openGui();
            return;
        }
        switch (args[0].toLowerCase(Locale.US)) {
            case "help":
            case "?":
                sendHelp(sender);
                return;
            case "mode":
                handleMode(sender, args);
                return;
            case "statsurl":
                handleStatsUrl(sender, args);
                return;
            case "statstoken":
                handleStatsToken(sender, args);
                return;
            case "menu":
            case "gui":
            case "settings":
            case "config":
                openGui();
                return;
            case "stats":
                BedwarsStatsCommand.showStats(Arrays.copyOfRange(args, 1, args.length));
                return;
            case "urchin":
                handleUrchin(sender, args);
                return;
            case "seraph":
                handleSeraph(sender, args);
                return;
            case "urchinkey":
                handleUrchinKey(sender, args);
                return;
            case "seraphkey":
                handleSeraphKey(sender, args);
                return;
            case "session":
                handleSession(sender, args);
                return;
            case "heightlimit":
                handleHeightLimit(sender);
                return;
            default:
                // A non-reserved first token is a player name → stats card.
                BedwarsStatsCommand.showStats(args);
                return;
        }
    }

    private static void openGui() {
        // Defer opening to a later tick. This runs synchronously inside client-command handling (which
        // fires during chat-send), and right after we return Minecraft's GuiChat calls
        // displayGuiScreen(null) to close the chat box — which would instantly close a GUI opened
        // inline. addScheduledTask runs its task inline when called ON the client thread (so it does NOT
        // defer); hopping onto a short-lived thread makes the nested addScheduledTask enqueue instead,
        // so the GUI opens next tick, after the chat box is already gone.
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        Thread t = new Thread(() -> mc.addScheduledTask(() -> mc.displayGuiScreen(new SettingsGui())),
                "BedwarsQol-OpenGui");
        t.setDaemon(true);
        t.start();
    }

    /** A short human description of a throwable for a one-line chat error. */
    private static String describe(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
    }

    private void sendHelp(ICommandSender sender) {
        send(sender, "§7§m----§r §6§lCobblify §r§7(/cobblify, /bw)§r §7§m----");
        send(sender, "§f/cobblify §7— open the settings menu");
        send(sender, "§f/cobblify <player> §7— a player's Bedwars stats");
        send(sender, "§f/cobblify stats §7— your own stats");
        send(sender, "§f/cobblify mode <auto|all|solo|2s|3s|4s> §7— chat FKDR gamemode");
        send(sender, "§f/cobblify statsurl <url> §7— set the stats backend");
        send(sender, "§f/cobblify statstoken <token> §7— set the backend token");
        send(sender, "§f/cobblify urchin <player> §7— community Urchin tags for a player");
        send(sender, "§f/cobblify seraph <player> §7— Seraph tags for a player");
        send(sender, "§f/cobblify session [reset] §7— this session's Bedwars tally");
        send(sender, "§f/cobblify heightlimit §7— current map, build limit and how it was determined");
        send(sender, "§f/cobblify help §7— this page");
        send(sender, "§7§m------------------------------");
    }

    private void handleHeightLimit(ICommandSender sender) {
        HeightLimitCore core = HeightLimitWatch.core();
        String map = core.map();
        send(sender, "§7§m----§r §6§lHeight Limit §r§7§m----");
        if (map == null) {
            send(sender, "§7Not on a Bedwars map server (no location event or sidebar Map: line yet).");
        } else {
            double feet = Minecraft.getMinecraft().thePlayer == null ? 0 : Minecraft.getMinecraft().thePlayer.posY;
            send(sender, "§fMap §7— §f" + map + (core.mode() == null ? "" : " §7(" + core.mode() + ")"));
            send(sender, "§fHeight Limit §7— §f" + (core.limit() < 0 ? "?" : core.limit())
                    + " §7(" + core.source().name().toLowerCase() + ")  §fDistance §7— §f"
                    + (core.limit() < 0 ? "?" : core.distance(feet)));
        }
        send(sender, "§7" + core.describe());
        send(sender, "§7Learned maps: " + core.learnedCount() + " (~/.cobblify/height-limits.txt)");
    }

    private void handleSession(ICommandSender sender, String[] args) {
        if (args.length >= 2 && "reset".equalsIgnoreCase(args[1])) {
            SessionStatsWatch.reset();
            send(sender, "§eSession stats reset.");
            return;
        }
        // Same rows as the HUD panel.
        SessionStats s = SessionStatsWatch.core();
        send(sender, "§7§m----§r §6§lGame§r §7§m----");
        send(sender, "§7Finals §f" + s.gameFinals() + "  §7Beds §f" + s.gameBeds() + "  §7Kills §f" + s.gameKills());
        send(sender, "§7§m----§r §6§lSession§r §7§m----");
        send(sender, "§7Finals §f" + s.finalKills() + " §7/ FKDR §f" + SessionStats.formatRatio(s.fkdr()));
        send(sender, "§7Beds §f" + s.beds() + " §7/ BBLR §f" + SessionStats.formatRatio(s.bblr()));
        send(sender, "§7Kills §f" + s.kills() + " §7/ KDR §f" + SessionStats.formatRatio(s.kdr()));
        send(sender, "§7Wins §f" + s.wins() + " §7/ WLR §f" + SessionStats.formatRatio(s.wlr()));
        send(sender, "§7Winstreak §f" + s.winstreak() + "  §7Session Games §f" + s.games()
                + "  §7Session Time §f" + s.elapsed(System.currentTimeMillis()) + "   §8(/cobblify session reset)");
    }

    private void handleMode(ICommandSender sender, String[] args) {
        ClientSettings cfg = settings();
        if (args.length < 2 || "show".equalsIgnoreCase(args[1])) {
            send(sender, "§eChat stats FKDR mode: §f" + modeLabel(cfg.chatStatsMode));
            send(sender, "§7Change with §f/cobblify mode <auto|all|solo|2s|3s|4s>§7.");
            return;
        }
        String canonical = canonicalMode(args[1]);
        if (canonical == null) {
            send(sender, "§cUnknown mode '§f" + args[1] + "§c'. Options: §fauto, all, solo, 2s, 3s, 4s§c.");
            return;
        }
        cfg.chatStatsMode = canonical;
        cfg.save();
        send(sender, "§aChat now shows §f" + modeLabel(canonical) + "§a FKDR. Updating existing lines…");
        ChatNameTags.refreshDisplayMode();
    }

    /** Canonical stored token for a user-typed mode word, or null when unrecognised. */
    private static String canonicalMode(String raw) {
        switch (raw.trim().toLowerCase(Locale.US)) {
            case "auto": case "detect": case "default": return "auto";
            case "all": case "overall": return "overall";
            case "solo": case "solos": case "1s": case "1v1": return "solo";
            case "doubles": case "double": case "2s": case "2v2v2v2": return "doubles";
            case "threes": case "three": case "3s": case "3v3v3v3": return "threes";
            case "fours": case "four": case "4s": case "4v4v4v4": return "fours";
            default: return null;
        }
    }

    /** Human label for a stored mode token. */
    private static String modeLabel(String canonical) {
        if (canonical == null) return "Auto (per-game)";
        switch (canonical) {
            case "overall": return "Overall";
            case "solo": return "Solo";
            case "doubles": return "Doubles (2v2v2v2)";
            case "threes": return "3v3v3v3";
            case "fours": return "4v4v4v4";
            default: return "Auto (per-game)";
        }
    }

    private static final java.util.concurrent.ExecutorService URCHIN_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "BedwarsQol-Urchin");
                t.setDaemon(true);
                return t;
            });

    /** Shared submit path for the urchinkey/seraphkey handlers: validation, JSON body, background
     *  POST on {@link #URCHIN_EXEC}, client-thread result marshal, and the post-set invalidate /
     *  pre-clear strip sequencing (see {@link ProviderKeySubmitter}). */
    private static final ProviderKeySubmitter KEY_SUBMITTER =
            new ProviderKeySubmitter(URCHIN_EXEC, BedwarsQolCommand::scheduled);

    /** {@code /cobblify urchin <name>} — on-demand community-tag lookup via the Worker's manual route. */
    private void handleUrchin(ICommandSender sender, String[] args) {
        ClientSettings cfg = settings();
        if (!cfg.urchinTags) {
            send(sender, "§cUrchin Tags is disabled. Enable it in /cobblify.");
            return;
        }
        if (args.length < 2 || args[1].trim().isEmpty()) {
            send(sender, "§eUsage: §f/cobblify urchin <player>");
            return;
        }
        final String name = args[1].trim();
        final BackendTarget backend = cfg.backendTarget(); // one atomic capture per operation
        final String url = backend.url;
        final String token = backend.token;
        if (url.isEmpty()) {
            send(sender, "§cNo stats backend URL. Set §f/cobblify statsurl <url>§c.");
            return;
        }
        send(sender, "§7Looking up Urchin tags for §f" + name + "§7...");
        URCHIN_EXEC.submit(() -> {
            try {
                com.bedwarsqol.stats.ScraperBackendClient.UrchinLookup r =
                        com.bedwarsqol.stats.ScraperBackendClient.getUrchin(url, token, name);
                scheduled(() -> printUrchin(name, r));
            } catch (Throwable t) {
                scheduled(() -> send(sender, "§cUrchin lookup failed for §f" + name + "§c."));
            }
        });
    }

    private static void printUrchin(String name, com.bedwarsqol.stats.ScraperBackendClient.UrchinLookup r) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        if (r == null || !r.success) {
            local("§cUrchin unavailable right now.");
            return;
        }
        if (r.notFound) {
            local("§7No Hypixel player named §f" + name + "§7 was found.");
            return;
        }
        long now = System.currentTimeMillis();
        java.util.List<com.bedwarsqol.stats.UrchinTag> tags =
                com.bedwarsqol.stats.UrchinTag.activeTags(r.tags, now);
        if (tags.isEmpty()) {
            if (r.unavailable) local("§7Urchin unavailable right now.");
            else local("§a" + name + " §7has no Urchin tags.");
            return;
        }
        local("§8[§6Cobblify§8] §fUrchin tags for §e" + name + (r.stale ? " §8(cached)" : "") + "§7:");
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        for (com.bedwarsqol.stats.UrchinTag t : tags) {
            StringBuilder sb = new StringBuilder("  ").append(t.color()).append("[")
                    .append(t.displayIcon()).append("] §f").append(t.displayName());
            if (!t.reason.isEmpty()) sb.append(" §7- §f").append(t.reason);
            if (t.addedOnMs > 0) sb.append(" §8(").append(fmt.format(new java.util.Date(t.addedOnMs))).append(')');
            local(sb.toString());
        }
    }

    /** {@code /cobblify seraph <name>} — on-demand Seraph-tag lookup via the Worker's manual route. */
    private void handleSeraph(ICommandSender sender, String[] args) {
        ClientSettings cfg = settings();
        if (!cfg.seraphTags) {
            send(sender, "§cSeraph Tags is disabled. Enable it in /cobblify.");
            return;
        }
        if (args.length < 2 || args[1].trim().isEmpty()) {
            send(sender, "§eUsage: §f/cobblify seraph <player>");
            return;
        }
        final String name = args[1].trim();
        final BackendTarget backend = cfg.backendTarget(); // one atomic capture per operation
        final String url = backend.url;
        final String token = backend.token;
        if (url.isEmpty()) {
            send(sender, "§cNo stats backend URL. Set §f/cobblify statsurl <url>§c.");
            return;
        }
        send(sender, "§7Looking up Seraph tags for §f" + name + "§7...");
        URCHIN_EXEC.submit(() -> {
            try {
                com.bedwarsqol.stats.ScraperBackendClient.SeraphLookup r =
                        com.bedwarsqol.stats.ScraperBackendClient.getSeraph(url, token, name);
                scheduled(() -> printSeraph(name, r));
            } catch (Throwable t) {
                scheduled(() -> send(sender, "§cSeraph lookup failed for §f" + name + "§c."));
            }
        });
    }

    private static void printSeraph(String name, com.bedwarsqol.stats.ScraperBackendClient.SeraphLookup r) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        if (r == null || !r.success) {
            local("§cSeraph unavailable right now.");
            return;
        }
        if (r.notFound) {
            local("§7No Hypixel player named §f" + name + "§7 was found.");
            return;
        }
        java.util.List<com.bedwarsqol.stats.SeraphTag> tags =
                com.bedwarsqol.stats.SeraphTag.activeTags(r.tags);
        if (tags.isEmpty()) {
            if (r.unavailable) local("§7Seraph unavailable right now.");
            else local("§a" + name + " §7has no Seraph tags.");
            return;
        }
        local("§8[§6Cobblify§8] §fSeraph tags for §e" + name + "§7:");
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        for (com.bedwarsqol.stats.SeraphTag t : tags) {
            StringBuilder sb = new StringBuilder("  ").append(t.color()).append("[")
                    .append(t.displayIcon()).append("] §f").append(t.displayName());
            if (!t.reason.isEmpty()) sb.append(" §7- §f").append(t.reason);
            if (t.addedOnMs > 0) sb.append(" §8(").append(fmt.format(new java.util.Date(t.addedOnMs))).append(')');
            local(sb.toString());
        }
    }

    /**
     * {@code /cobblify urchinkey <key|clear>} — sets/clears the server-side Urchin key. The key never touches
     * chat history, disk, logs, exceptions, or URLs. The whole handler is guarded so it can never throw.
     */
    private void handleUrchinKey(ICommandSender sender, String[] args) {
        try {
            // Fail closed: never construct or submit the body unless the raw command line is verified
            // gone from up-arrow history, or the key would linger there while being posted (B1).
            if (!scrubUrchinKeyHistory()) {
                send(sender, "§cCould not clear the command from chat history - key not sent. "
                        + "Use §fwrangler secret put URCHIN_KEY§c instead.");
                return;
            }
            ClientSettings cfg = settings();
            final BackendTarget backend = cfg.backendTarget(); // one atomic capture per submission
            if (backend.url.isEmpty()) {
                send(sender, "§cNo stats backend URL. Set §f/cobblify statsurl <url>§c first.");
                return;
            }
            if (args.length < 2 || args[1].trim().isEmpty()) {
                send(sender, "§eUsage: §f/cobblify urchinkey <key|clear>");
                return;
            }
            final boolean clear = "clear".equalsIgnoreCase(args[1].trim())
                    || "none".equalsIgnoreCase(args[1].trim());
            send(sender, "§7Submitting Urchin key...");
            if (clear) {
                // The submitter strips local tags BEFORE the request goes out, not on the response
                // (I1): the Worker commits the key deletion before it replies, so a committed-but-
                // response-lost clear must still leave the client display safe. finishUrchinKey then
                // only confirms or warns about the server outcome; it never re-derives display
                // safety from the network result.
                KEY_SUBMITTER.submitClear(ProviderKeySubmitter.Provider.URCHIN, backend,
                        StatsCache::stripUrchinTags, res -> finishUrchinKey(res, true));
            } else {
                KEY_SUBMITTER.submitSet(ProviderKeySubmitter.Provider.URCHIN, backend, args[1].trim(),
                        StatsCache::invalidateUrchinResolution, res -> finishUrchinKey(res, false));
            }
        } catch (Throwable t) {
            try { send(sender, "§cUrchin key update failed."); } catch (Throwable ignored) { }
        }
    }

    private static void finishUrchinKey(com.bedwarsqol.stats.ScraperBackendClient.SecretPostResult res, boolean clear) {
        if (clear) {
            // Local tags were already stripped before the request was submitted (see handleUrchinKey):
            // display safety must not depend on the response. On success confirm; on any failure or
            // ambiguous transport outcome, warn generically that the server-side clear may not have
            // taken effect (no key text).
            if (res != null && res.success) {
                local("§aUrchin key cleared.");
            } else {
                local("§eUrchin key clear submitted, but the server did not confirm it - the server-side "
                        + "clear may not have taken effect. Retry, or remove it with §fwrangler secret "
                        + "delete URCHIN_KEY§e / §fwrangler kv§e.");
            }
            return;
        }
        if (res != null && res.success) {
            // Provider resolution was already invalidated by the submitter (client thread, pre-callback).
            local("§aUrchin key updated.");
            return;
        }
        String err = res == null ? null : res.error;
        if ("key_managed_by_secret".equals(err)) {
            local("§cThe key is managed by a wrangler secret - use §fwrangler secret delete URCHIN_KEY§c first.");
        } else if ("invalid_key_length".equals(err)) {
            local("§cKey must be 8-200 characters.");
        } else if (res != null && res.status == 403) {
            local("§cUnauthorized. Set a matching §f/cobblify statstoken§c, or provision with §fwrangler secret put URCHIN_KEY§c.");
        } else {
            local("§cUrchin key update failed. Check §f/cobblify statsurl§c/§fstatstoken§c, or use §fwrangler secret put URCHIN_KEY§c.");
        }
    }

    /**
     * Remove any {@code /bw|bedwarsqol|hypixelclient|cobblify urchinkey ...} lines from the up-arrow history and
     * verify. Returns true only when the history is confirmed clear; a null/unavailable chat GUI or an
     * unverifiable removal fails closed (false), so the caller aborts before the key is posted (B1).
     */
    private static boolean scrubUrchinKeyHistory() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.ingameGUI == null) return false;
        java.util.List<String> sent;
        try {
            sent = mc.ingameGUI.getChatGUI().getSentMessages();
        } catch (Throwable t) {
            return false;
        }
        return UrchinKeyScrub.scrubKeyEntries(sent);
    }

    /**
     * {@code /cobblify seraphkey <key|clear>} — sets/clears the server-side Seraph key. Mirrors
     * {@link #handleUrchinKey}: the key never touches chat history, disk, logs, exceptions, or URLs, and
     * the whole handler is guarded so it can never throw.
     */
    private void handleSeraphKey(ICommandSender sender, String[] args) {
        try {
            if (!scrubSeraphKeyHistory()) {
                send(sender, "§cCould not clear the command from chat history - key not sent. "
                        + "Use §fwrangler secret put SERAPH_KEY§c instead.");
                return;
            }
            ClientSettings cfg = settings();
            final BackendTarget backend = cfg.backendTarget(); // one atomic capture per submission
            if (backend.url.isEmpty()) {
                send(sender, "§cNo stats backend URL. Set §f/cobblify statsurl <url>§c first.");
                return;
            }
            if (args.length < 2 || args[1].trim().isEmpty()) {
                send(sender, "§eUsage: §f/cobblify seraphkey <key|clear>");
                return;
            }
            final boolean clear = "clear".equalsIgnoreCase(args[1].trim())
                    || "none".equalsIgnoreCase(args[1].trim());
            send(sender, "§7Submitting Seraph key...");
            if (clear) {
                // The submitter strips local tags BEFORE the request goes out (I1): the Worker
                // commits the deletion before it replies, so a committed-but-response-lost clear
                // must still leave the display safe.
                KEY_SUBMITTER.submitClear(ProviderKeySubmitter.Provider.SERAPH, backend,
                        StatsCache::stripSeraphTags, res -> finishSeraphKey(res, true));
            } else {
                KEY_SUBMITTER.submitSet(ProviderKeySubmitter.Provider.SERAPH, backend, args[1].trim(),
                        StatsCache::invalidateSeraphResolution, res -> finishSeraphKey(res, false));
            }
        } catch (Throwable t) {
            try { send(sender, "§cSeraph key update failed."); } catch (Throwable ignored) { }
        }
    }

    private static void finishSeraphKey(com.bedwarsqol.stats.ScraperBackendClient.SecretPostResult res, boolean clear) {
        if (clear) {
            if (res != null && res.success) {
                local("§aSeraph key cleared.");
            } else {
                local("§eSeraph key clear submitted, but the server did not confirm it - the server-side "
                        + "clear may not have taken effect. Retry, or remove it with §fwrangler secret "
                        + "delete SERAPH_KEY§e / §fwrangler kv§e.");
            }
            return;
        }
        if (res != null && res.success) {
            // Provider resolution was already invalidated by the submitter (client thread, pre-callback).
            local("§aSeraph key updated.");
            return;
        }
        String err = res == null ? null : res.error;
        if ("key_managed_by_secret".equals(err)) {
            local("§cThe key is managed by a wrangler secret - use §fwrangler secret delete SERAPH_KEY§c first.");
        } else if ("invalid_key_length".equals(err)) {
            local("§cKey must be 8-200 characters.");
        } else if (res != null && res.status == 403) {
            local("§cUnauthorized. Set a matching §f/cobblify statstoken§c, or provision with §fwrangler secret put SERAPH_KEY§c.");
        } else {
            local("§cSeraph key update failed. Check §f/cobblify statsurl§c/§fstatstoken§c, or use §fwrangler secret put SERAPH_KEY§c.");
        }
    }

    private static boolean scrubSeraphKeyHistory() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.ingameGUI == null) return false;
        java.util.List<String> sent;
        try {
            sent = mc.ingameGUI.getChatGUI().getSentMessages();
        } catch (Throwable t) {
            return false;
        }
        return SeraphKeyScrub.scrubKeyEntries(sent);
    }

    /** Run {@code r} on the client thread (chat/cache mutations must land there). */
    private static void scheduled(Runnable r) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) mc.addScheduledTask(r);
    }

    private static void local(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(ModChat.mark(new ChatComponentText(message)));
        }
    }

    private void handleStatsUrl(ICommandSender sender, String[] args) {
        ClientSettings cfg = settings();
        if (args.length < 2 || "show".equalsIgnoreCase(args[1])) {
            BackendTarget resolved = cfg.backendTarget();
            if (!resolved.isConfigured()) {
                send(sender, "§eNo stats backend URL set. Use §f/cobblify statsurl <url>§e.");
            } else if (cfg.statsBackendUrl.isEmpty()) {
                send(sender, "§aStats backend: §f" + resolved.url + " §7(built-in)");
            } else {
                send(sender, "§aStats backend: §f" + resolved.url);
            }
            return;
        }
        if ("clear".equalsIgnoreCase(args[1]) || "reset".equalsIgnoreCase(args[1])) {
            cfg.statsBackendUrl = ClientSettings.DEFAULT_STATS_BACKEND_URL;
            cfg.save();
            send(sender, "§aCleared stats backend URL (stats disabled until set again).");
            return;
        }
        String url = args[1].trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        cfg.statsBackendUrl = url;
        cfg.save();
        send(sender, "§aSaved stats backend URL.");
    }

    private void handleStatsToken(ICommandSender sender, String[] args) {
        ClientSettings cfg = settings();
        if (args.length < 2 || "show".equalsIgnoreCase(args[1])) {
            if (cfg.statsBackendToken.isEmpty()) {
                send(sender, "§eNo stats token set (requests sent unauthenticated).");
            } else {
                send(sender, "§aStats token: §f" + mask(cfg.statsBackendToken));
            }
            return;
        }
        if ("clear".equalsIgnoreCase(args[1]) || "none".equalsIgnoreCase(args[1])) {
            cfg.statsBackendToken = "";
            cfg.save();
            send(sender, "§aCleared stats token (requests now unauthenticated).");
            return;
        }
        cfg.statsBackendToken = args[1].trim();
        cfg.save();
        send(sender, "§aSaved stats token.");
    }

    /** First/last 4 chars only — never echo a full secret to chat. */
    private static String mask(String s) {
        if (s.length() <= 8) return "****";
        return s.substring(0, 4) + "…" + s.substring(s.length() - 4);
    }

    private static ClientSettings settings() {
        if (BedwarsQol.config == null) BedwarsQol.config = new ClientSettings();
        BedwarsQol.config.sanitize();
        return BedwarsQol.config;
    }

    private static void send(ICommandSender sender, String message) {
        sender.addChatMessage(ModChat.mark(new ChatComponentText(message)));
    }
}
