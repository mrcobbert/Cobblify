package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.api.event.ChatEvent;
import net.weavemc.api.event.SubscribeEvent;
import net.weavemc.api.event.TickEvent;
import net.weavemc.api.event.WorldEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Party Join Alert: prints a red {@code "Party Joined"} / {@code "Party Left"} to chat when a premade
 * team drops into — or pulls out of — your Bedwars pregame lobby.
 *
 * <p><b>The signal.</b> A party is teleported in (and pulled out) as one unit, so its members'
 * broadcasts all land in the same instant — joins as {@code "<name> has joined (n/m)!"}, leaves as
 * {@code "<name> has quit!"} or {@code "<name> disconnected."}. We count broadcasts that arrive
 * together — within {@link #BURST_TICKS} of each other — and alert when {@link #PARTY_MIN} or more land
 * at once. Solo queuers trickle in and out one at a time and never reach that count; two parties moving
 * in quick succession are separated by the gap between their bursts, so each fires its own alert. Joins
 * and leaves are tracked as separate bursts, so a leaving party and an arriving solo never merge.
 *
 * <p>(Since Hypixel's pregame name obfuscation the lobby hides every enemy's name and skin, so this
 * timing signal — who arrives/leaves together — is all we have; identity-based detection is impossible.)
 *
 * <p><b>Our own party.</b> When we queue with a premade, our party is teleported in as one unit too,
 * so our partymates' joins burst together exactly like an enemy party's would. The tell-apart signal is
 * our own join: it lands inside that same burst and no other. So rather than discard it, we use it to
 * tag the burst as ours and stay silent. An enemy party's burst never contains our join, so it is never
 * suppressed — the guard can't cause a missed alert. (A leaving party never carries our own quit — we
 * are gone before it broadcasts — so leave bursts are judged on size alone.)
 *
 * <p><b>Guards.</b> A burst containing our own join is suppressed, a "Sending you to …" teleport drops
 * any open burst, and we only run in the pregame waiting lobby. Chat and tick events both run on the
 * client thread (Hypixel re-queues chat packets onto it), so the burst state needs no synchronisation;
 * {@link WorldEvent.Load} resets as a safety net.
 */
public final class PartyJoinAlert {

    private static final int BURST_TICKS = 3; // broadcasts within ~0.15s count as the same instant
    private static final int PARTY_MIN = 3;   // 3+ names arriving/leaving at once = a premade party

    /** "<name> has joined (n/m)!" — the per-join lobby broadcast; group 1 = name. */
    private static final Pattern JOIN =
            Pattern.compile("([A-Za-z0-9_]{3,16}) has joined \\(\\d+/\\d+\\)!");
    /** "<name> has quit!" / "<name> disconnected." — the per-leave lobby broadcasts; group 1 = name. */
    private static final Pattern LEAVE =
            Pattern.compile("([A-Za-z0-9_]{3,16}) (?:has quit!|disconnected\\.)");

    private final Burst joins = new Burst();  // same-instant arrivals
    private final Burst leaves = new Burst(); // same-instant departures
    private int partiesInLobby;               // net enemy parties currently in this lobby (reset on teleport/world load)

    /** Mirror of {@link #partiesInLobby} for out-of-instance readers (the lobby dashboard export). */
    private static volatile int lobbyPartyCount;

    /** The net enemy-party count in the current lobby; 0 when not in a pregame lobby. */
    public static int partiesInLobby() {
        return lobbyPartyCount;
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        reset();
    }

    @SubscribeEvent
    public void onChat(ChatEvent.Received event) {
        if (event == null || event.getMessage() == null) return;
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.partyJoinAlert) return;
        if (!HypixelContext.isOnHypixel() || !HypixelContext.isInBedwars()
                || HypixelContext.isInActiveBedwarsGame()) return;
        String msg = EnumChatFormatting.getTextWithoutFormattingCodes(event.getMessage().getUnformattedText());
        if (msg == null) return;
        handleChat(msg.trim());
    }

    private void handleChat(String msg) {
        if (msg.startsWith("Sending you to ")) { // teleport to a new mini-server
            reset();
            return;
        }
        String self = selfName();
        Matcher j = JOIN.matcher(msg);
        if (j.matches()) { joins.add(j.group(1), self); return; }
        Matcher l = LEAVE.matcher(msg);
        if (l.matches()) leaves.add(l.group(1), self);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.Post event) {
        tick();
    }

    private void tick() {
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.partyJoinAlert
                || !HypixelContext.isOnHypixel()
                || !HypixelContext.isInBedwars()
                || HypixelContext.isInActiveBedwarsGame()) {
            reset();
            return;
        }
        // Once a burst goes quiet, decide: 3+ at once = a party (joins also drop our own party).
        // partiesInLobby is a net count: a joining party adds one, a leaving party removes one (floored at 0).
        if (joins.closedAsParty()) announce("Party Joined", ++partiesInLobby);
        if (leaves.closedAsParty()) announce("Party Left", partiesInLobby = Math.max(0, partiesInLobby - 1));
        lobbyPartyCount = partiesInLobby;
    }

    private static String selfName() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return null;
        return mc.thePlayer.getName();
    }

    private static void announce(String label, int inLobby) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        mc.thePlayer.addChatMessage(ModChat.mark(new ChatComponentText(
                EnumChatFormatting.RED + label + " (" + inLobby + " in lobby)")));
    }

    private void reset() {
        joins.reset();
        leaves.reset();
        partiesInLobby = 0;
        lobbyPartyCount = 0;
    }

    /**
     * One same-instant run of join (or leave) broadcasts. Broadcasts within {@link #BURST_TICKS} of
     * each other accumulate; once the run falls quiet the burst is judged a party when it holds
     * {@link #PARTY_MIN}+ names and didn't include our own.
     */
    private static final class Burst {
        private int size;
        private int sinceTicks = BURST_TICKS + 1; // start "closed" so the first broadcast opens fresh
        private boolean hasSelf;                  // includes our own name → our party, not an enemy's

        void add(String name, String self) {
            if (sinceTicks > BURST_TICKS) { // a new burst (the prior one already closed)
                size = 0;
                hasSelf = false;
            }
            size++;
            if (self != null && name.equalsIgnoreCase(self)) hasSelf = true;
            sinceTicks = 0;
        }

        /** Advance one tick; true exactly once, on the tick a just-closed burst proves an enemy party. */
        boolean closedAsParty() {
            if (size > 0 && ++sinceTicks > BURST_TICKS) {
                boolean party = size >= PARTY_MIN && !hasSelf;
                size = 0;
                hasSelf = false;
                return party;
            }
            return false;
        }

        void reset() {
            size = 0;
            sinceTicks = BURST_TICKS + 1;
            hasSelf = false;
        }
    }
}
