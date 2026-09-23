package com.bedwarsqol.bedwars;

import com.bedwarsqol.feature.ChatSender;
import com.bedwarsqol.stats.BedwarsMode;
import com.bedwarsqol.stats.BedwarsModeDetector;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the live diamond/emerald generator countdowns and tiers off the floating holograms
 * Hypixel renders above each generator (stacked invisible {@link EntityArmorStand}s), and
 * keeps the countdown running locally when you walk out of range.
 *
 * <p><b>In range</b> we read the server's own "Spawns in N" number — authoritative by
 * construction (correct across tier-ups, forge upgrades, mode rates, joining mid-game). We
 * also read the "Tier &lt;roman&gt;" line and remember the longest countdown we've seen at
 * the current tier, which <i>is</i> the spawn interval.
 *
 * <p><b>Out of range</b> the hologram entity no longer exists client-side, so we extrapolate:
 * the gens keep spawning on the server on a fixed cycle, so we anchor on the last real reading
 * and decrement by wall-clock time, wrapping at the interval. This stays accurate while the
 * tier is stable and <b>re-syncs the instant a generator is back on screen</b>. If you remain out
 * of range <i>across</i> a tier upgrade, we also catch Hypixel's generator-upgrade chat broadcast
 * (see {@link #onChat}) to correct the tier/interval immediately; otherwise we lean on the
 * auto-resync the next time a hologram is on screen.
 *
 * <p>Matching is loose (strip formatting, then compare) so a Hypixel color-code tweak doesn't
 * break it. Confirm the strings in-game (docs/IN_GAME_TEST.md).
 */
public final class GeneratorTracker {

    private static final int TICK_INTERVAL = 4;          // ~0.2s; the number only changes once/sec
    private static final double SIBLING_RANGE_SQ = 2.0 * 2.0; // max horizontal gap to a sibling line

    // Anchors for the local countdown: last value read, when we read it (ms), the inferred
    // spawn interval, and the tier — per generator type. value/interval -1 = never seen.
    private static volatile int diamondValue = -1;
    private static volatile long diamondTime;
    private static volatile int diamondInterval = -1;
    private static volatile int diamondTierV = -1;

    private static volatile int emeraldValue = -1;
    private static volatile long emeraldTime;
    private static volatile int emeraldInterval = -1;
    private static volatile int emeraldTierV = -1;

    private int ticks;

    /** Seconds until the next diamond spawn (live in range, extrapolated out of range), or {@code -1}. */
    public static int diamondSeconds() {
        return predict(diamondValue, diamondTime, diamondInterval);
    }

    /** Seconds until the next emerald spawn (live in range, extrapolated out of range), or {@code -1}. */
    public static int emeraldSeconds() {
        return predict(emeraldValue, emeraldTime, emeraldInterval);
    }

    /** Diamond generator tier (1/2/3), or {@code -1} if never seen. Shared map-wide. */
    public static int diamondTier() {
        return diamondTierV;
    }

    /** Emerald generator tier (1/2/3), or {@code -1} if never seen. Shared map-wide. */
    public static int emeraldTier() {
        return emeraldTierV;
    }

    public static void reset() {
        diamondValue = -1;
        diamondInterval = -1;
        diamondTierV = -1;
        emeraldValue = -1;
        emeraldInterval = -1;
        emeraldTierV = -1;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++ticks < TICK_INTERVAL) return;
        ticks = 0;
        update();
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        reset();
    }

    /**
     * Hypixel broadcasts a chat line when a generator tiers up (e.g. "Generators upgraded to
     * Diamond II"). Catching it corrects the tier/interval immediately even while we're out of
     * range of the hologram — the one case the wall-clock extrapolation could otherwise miss.
     *
     * <p>Only the broadcast counts: a line a player typed is never a broadcast, so a teammate asking
     * for "the diamond generator to 2" cannot re-anchor the countdown ({@link GeneratorUpgradeParse}).
     */
    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event == null || event.message == null) return;
        if (!HypixelContext.isInActiveBedwarsGame()) return;
        String msg = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        if (msg == null) return;
        String lower = msg.toLowerCase();
        if (!lower.contains("upgrad") || !lower.contains("generator")) return;
        boolean diamond = lower.contains("diamond");
        boolean emerald = lower.contains("emerald");
        if (!diamond && !emerald) return;
        int tier = GeneratorUpgradeParse.tier(msg, ChatSender.typedChatName(event.message));
        if (tier < 1) return;
        long now = System.currentTimeMillis();
        if (diamond) applyUpgrade(true, tier, now);
        if (emerald) applyUpgrade(false, tier, now);
    }

    /** Re-anchor a generator to a freshly-announced tier (a full interval until the hologram re-syncs). */
    private static void applyUpgrade(boolean diamond, int tier, long now) {
        int interval = seedInterval(diamond, tier);
        if (diamond) {
            diamondTierV = tier;
            diamondInterval = interval;
            diamondValue = interval;
            diamondTime = now;
        } else {
            emeraldTierV = tier;
            emeraldInterval = interval;
            emeraldValue = interval;
            emeraldTime = now;
        }
    }

    private static void update() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null
                || !HypixelContext.isInActiveBedwarsGame()) {
            reset();
            return;
        }

        List<Stand> stands = new ArrayList<>();
        for (Object o : mc.theWorld.loadedEntityList) {
            if (!(o instanceof EntityArmorStand)) continue;
            Entity e = (Entity) o;
            String raw = e.getCustomNameTag();
            if (raw == null || raw.isEmpty()) continue;
            String text = EnumChatFormatting.getTextWithoutFormattingCodes(raw);
            if (text == null) continue;
            text = text.trim();
            if (text.isEmpty()) continue;
            stands.add(new Stand(e, text));
        }

        Stand diamond = nearestNamed(stands, mc.thePlayer, "Diamond");
        if (diamond != null) {
            int v = spawnSeconds(stands, diamond);
            if (v >= 0) recordDiamond(v, tier(stands, diamond));
        }
        Stand emerald = nearestNamed(stands, mc.thePlayer, "Emerald");
        if (emerald != null) {
            int v = spawnSeconds(stands, emerald);
            if (v >= 0) recordEmerald(v, tier(stands, emerald));
        }
    }

    private static void recordDiamond(int value, int tier) {
        int seed = Math.max(seedInterval(true, tier), value);
        diamondInterval = tier != diamondTierV ? seed : Math.max(diamondInterval, seed);
        if (tier > 0) diamondTierV = tier;
        diamondValue = value;
        diamondTime = System.currentTimeMillis();
    }

    private static void recordEmerald(int value, int tier) {
        int seed = Math.max(seedInterval(false, tier), value);
        emeraldInterval = tier != emeraldTierV ? seed : Math.max(emeraldInterval, seed);
        if (tier > 0) emeraldTierV = tier;
        emeraldValue = value;
        emeraldTime = System.currentTimeMillis();
    }

    /**
     * A best-effort interval (seconds) for the given tier, used only as a floor before we've
     * observed a full cycle's peak. The real interval is learned from the hologram via
     * {@code max(...)}, so these just guard against under-counting from a brief glance.
     */
    private static int seedInterval(boolean diamond, int tier) {
        if (diamond) {
            switch (tier) {
                case 2: return 24;
                case 3: return 12;
                default: return 30;
            }
        }
        boolean team = BedwarsModeDetector.current() == BedwarsMode.THREES
                || BedwarsModeDetector.current() == BedwarsMode.FOURS;
        switch (tier) {
            case 2: return team ? 40 : 50;
            case 3: return team ? 28 : 35;
            default: return team ? 56 : 65;
        }
    }

    /** Extrapolate the countdown from the anchor, wrapping at the interval. Result in [1, interval]. */
    private static int predict(int value, long time, int interval) {
        if (value < 0 || interval <= 0) return -1;
        long elapsed = (System.currentTimeMillis() - time) / 1000L;
        if (elapsed < 0) elapsed = 0;
        long r = (value - elapsed) % interval;
        if (r <= 0) r += interval;
        return (int) r;
    }

    /** The generator name stand of the given type nearest the player. */
    private static Stand nearestNamed(List<Stand> stands, Entity player, String name) {
        Stand best = null;
        double bestDist = Double.MAX_VALUE;
        for (Stand s : stands) {
            if (!s.text.equalsIgnoreCase(name)) continue;
            double d = player.getDistanceSqToEntity(s.entity);
            if (d < bestDist) {
                bestDist = d;
                best = s;
            }
        }
        return best;
    }

    /** Parse "Spawns in N" from the sibling stand closest to the generator's name stand. */
    private static int spawnSeconds(List<Stand> stands, Stand gen) {
        Stand sibling = nearestSibling(stands, gen, "Spawns in");
        if (sibling == null) return -1;
        String digits = sibling.text.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return -1;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /** Parse "Tier &lt;roman&gt;" from the sibling stand closest to the generator's name stand. */
    private static int tier(List<Stand> stands, Stand gen) {
        Stand sibling = nearestSibling(stands, gen, "Tier");
        if (sibling == null) return -1;
        return roman(sibling.text.substring(sibling.text.toLowerCase().indexOf("tier") + 4).trim());
    }

    /** The stand whose text starts with {@code prefix} that is closest (horizontally) to {@code gen}. */
    private static Stand nearestSibling(List<Stand> stands, Stand gen, String prefix) {
        String lower = prefix.toLowerCase();
        Stand best = null;
        double bestDist = SIBLING_RANGE_SQ;
        for (Stand s : stands) {
            if (s == gen) continue;
            if (!s.text.toLowerCase().startsWith(lower)) continue;
            double dx = s.entity.posX - gen.entity.posX;
            double dz = s.entity.posZ - gen.entity.posZ;
            double d = dx * dx + dz * dz;
            if (d <= bestDist) {
                bestDist = d;
                best = s;
            }
        }
        return best;
    }

    private static int roman(String s) {
        if (s == null) return -1;
        String r = s.toUpperCase().replaceAll("[^IVX]", "");
        if (r.startsWith("III")) return 3;
        if (r.startsWith("IV")) return 4;
        if (r.startsWith("II")) return 2;
        if (r.startsWith("I")) return 1;
        return -1;
    }

    private static final class Stand {
        final Entity entity;
        final String text;

        Stand(Entity entity, String text) {
            this.entity = entity;
            this.text = text;
        }
    }
}
