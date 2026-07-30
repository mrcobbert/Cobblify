package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.BackendTarget;
import com.bedwarsqol.stats.GameSessionTracker;
import com.bedwarsqol.stats.HypixelContext;
import com.bedwarsqol.stats.MojangNameResolver;
import com.bedwarsqol.stats.ScraperBackendClient;
import com.bedwarsqol.stats.SeraphTag;
import com.bedwarsqol.stats.UrchinTag;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pregame-queue chat alerts: community cheater tags (Urchin / Seraph) and nicked players, for anyone
 * who <b>types</b> in the Bedwars queue.
 *
 * <p><b>Why chat is the only channel here.</b> Since Hypixel's pregame name obfuscation the queue's tab
 * list carries junk names and stripped skins, so {@link UrchinAlert}/{@link SeraphAlert} — which sweep
 * the tab list and are gated to an active game — can never fire in the queue. What Hypixel never
 * obfuscates is what a player <i>types</i>: a {@code "<sender>: message"} line always names its real
 * sender. So this surface is driven purely by received chat, and only by the typed shape
 * ({@link ChatSender#typedChatName}); the queue's join/leave broadcasts are deliberately not parsed.
 *
 * <p><b>Why it does not touch the stats pipeline.</b> Lookups go through the same MANUAL, name-keyed
 * route {@code /cobblify urchin <name>} uses ({@link ScraperBackendClient#getUrchin}/
 * {@link ScraperBackendClient#getSeraph}), off the client thread. Nothing here writes the stats cache
 * or the eligibility snapshot, so no badge (tab, nametag) can render from a queue lookup — the
 * identity invariants that gate badges are unchanged.
 *
 * <p><b>Never accuse the wrong account.</b> A tag is announced only when the UUID the provider lookup
 * reports is the same account {@link MojangNameResolver} resolved the typed name to. A missing or
 * disagreeing UUID (a stale name alias, a re-registered name) is dropped silently.
 *
 * <p><b>Outbound volume.</b> One lookup per distinct name per queue, at most
 * {@link #MAX_LOOKUPS_PER_QUEUE} per queue; past the cap we stop silently. Both toggles are off by
 * default because they cause outbound requests, and the providers' keys are personal and rate-limited.
 * The per-queue state resets whenever {@link GameSessionTracker#currentSessionId()} changes.
 */
public final class QueueAlert {

    /** Hard cap on lookups per queue — the providers' keys are personal and forbid bulk use. */
    static final int MAX_LOOKUPS_PER_QUEUE = 16;

    /** Off the client thread, exactly like the {@code /cobblify urchin} command's lookup executor. */
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BedwarsQol-QueueAlert");
        t.setDaemon(true);
        return t;
    });

    private int currentSession = Integer.MIN_VALUE;
    /** Names already looked up this queue (lower-cased); also the cap counter. Client thread only. */
    private final Set<String> looked = new HashSet<String>();

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event == null || event.message == null) return;
        // Never treat the mod's own output as a player line (its alerts name players).
        if (ModChat.isMarked(event.message)) return;
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || (!cfg.queueTagAlert && !cfg.queueNickAlert)) return;
        // Queue only — NOT the Bedwars hub, which shares the "BED WARS" sidebar and has no active
        // game either. The hub's chat traffic would burn the lookup cap on players we're not queued
        // with; only a real pregame queue lists the match being assembled.
        if (!HypixelContext.isOnHypixel() || !HypixelContext.isInBedwarsQueue()) return;

        int session = GameSessionTracker.currentSessionId();
        if (session != currentSession) {
            currentSession = session;
            looked.clear();
        }

        String sender = ChatSender.typedChatName(event.message);
        if (sender == null) return;
        if (isSelf(sender)) return;
        if (!accept(looked, sender, MAX_LOOKUPS_PER_QUEUE)) return;

        final boolean wantTag = cfg.queueTagAlert;
        final boolean wantNick = cfg.queueNickAlert;
        final boolean urchin = cfg.urchinTags;
        final boolean seraph = cfg.seraphTags;
        final BackendTarget backend = cfg.backendTarget(); // one atomic capture per lookup
        final String url = backend.url;
        final String token = backend.token;
        final String name = sender;
        EXEC.submit(() -> lookup(name, wantTag, wantNick, urchin, seraph, url, token));
    }

    /**
     * Dedupe + cap decision for one typed name: true (and records it) only for a name not yet looked
     * up this queue while the cap still has room. Pure, so the bound is unit-testable.
     */
    static boolean accept(Set<String> seen, String name, int cap) {
        if (seen == null || name == null || name.isEmpty()) return false;
        if (seen.size() >= cap) return false; // cap reached — stop silently
        return seen.add(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether a provider lookup may be attributed to the account we resolved the typed name to. Pure.
     * Both forms (dashed / undashed) compare equal; a null or blank lookup UUID never matches, so an
     * unattributable result can only ever be dropped.
     */
    static boolean uuidMatches(UUID resolved, String lookupUuid) {
        if (resolved == null || lookupUuid == null) return false;
        String actual = lookupUuid.trim().replace("-", "");
        if (actual.isEmpty()) return false;
        return resolved.toString().replace("-", "").equalsIgnoreCase(actual);
    }

    /** Off-thread: resolve the name, then (for a real account) ask the enabled providers. */
    private static void lookup(String name, boolean wantTag, boolean wantNick,
                               boolean urchin, boolean seraph, String url, String token) {
        UUID resolved;
        try {
            resolved = MojangNameResolver.resolve(name);
        } catch (Throwable t) {
            return; // transient resolver failure — say nothing rather than guess "nicked"
        }
        if (resolved == null) {
            // No such Mojang account: the typed name is a nick.
            if (wantNick) scheduled(() -> announceNick(name));
            return;
        }
        if (!wantTag) return;
        if (url == null || url.trim().isEmpty()) return; // no backend configured — nothing to ask
        if (urchin) lookupUrchin(name, resolved, url, token);
        if (seraph) lookupSeraph(name, resolved, url, token);
    }

    private static void lookupUrchin(String name, UUID resolved, String url, String token) {
        ScraperBackendClient.UrchinLookup r;
        try {
            r = ScraperBackendClient.getUrchin(url, token, name);
        } catch (Throwable t) {
            return;
        }
        if (r == null || !r.success || r.notFound) return;
        if (!uuidMatches(resolved, r.uuid)) return; // mismatch => never accuse
        final UrchinTag tag = UrchinTag.priority(r.tags, System.currentTimeMillis());
        if (tag == null) return;
        scheduled(() -> announceUrchin(name, tag));
    }

    private static void lookupSeraph(String name, UUID resolved, String url, String token) {
        ScraperBackendClient.SeraphLookup r;
        try {
            r = ScraperBackendClient.getSeraph(url, token, name);
        } catch (Throwable t) {
            return;
        }
        if (r == null || !r.success || r.notFound) return;
        if (!uuidMatches(resolved, r.uuid)) return; // mismatch => never accuse
        final SeraphTag tag = SeraphTag.priority(r.tags);
        if (tag == null) return;
        scheduled(() -> announceSeraph(name, tag));
    }

    /** Client thread: the same cheater-type-only wording {@link UrchinAlert} uses. */
    private static void announceUrchin(String name, UrchinTag tag) {
        String line = UrchinAlertFormat.formatOrdinary(TeamColors.nameColor(name), name, tag);
        if (line == null) return; // sniper/caution types stay silent
        print(line);
        ClientSettings cfg = BedwarsQol.config;
        if (cfg != null && cfg.urchinAlertSound) pling();
    }

    /** Client thread: the same negative-tag-only wording {@link SeraphAlert} uses. */
    private static void announceSeraph(String name, SeraphTag tag) {
        String line = SeraphAlertFormat.formatOrdinary(TeamColors.nameColor(name), name, tag);
        if (line == null) return; // safelist/unknown kinds stay silent
        print(line);
        ClientSettings cfg = BedwarsQol.config;
        if (cfg != null && cfg.seraphAlertSound && tag.isCheaterType()) pling();
    }

    /** Client thread: the silent nick notice. */
    private static void announceNick(String name) {
        print("§8[§6Nick§8] " + TeamColors.nameColor(name) + name + " §7is nicked");
    }

    private static void print(String line) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        mc.thePlayer.addChatMessage(ModChat.mark(new ChatComponentText(line)));
    }

    private static void pling() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) mc.thePlayer.playSound("note.pling", 1.0f, 1.0f);
    }

    /** Run {@code r} on the client thread (chat output must land there). */
    private static void scheduled(Runnable r) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) mc.addScheduledTask(r);
    }

    private static boolean isSelf(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.thePlayer != null && name.equalsIgnoreCase(mc.thePlayer.getName());
    }
}
