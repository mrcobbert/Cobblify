package com.bedwarsqol.anticheat;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.DiagLog;
import com.bedwarsqol.feature.ModChat;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLadder;
import net.minecraft.block.BlockWeb;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Cheater Detector: flags OTHER players whose observed behaviour is impossible for a vanilla client.
 *
 * <p>Strictly passive — it only reads packets the vanilla client already receives, never sends or
 * alters anything, and its output is a private local chat line (click to prefill {@code /wdr}). All
 * detection math runs on raw packet values fed by {@code NetHandlerPlayClientMixin}, never on the
 * interpolated render entities (which lag the packets by a 3-tick lerp).
 *
 * <p>The checks are ports of the strongest open-source observer-side signals (see the project memory
 * for the full research): MWE's through-wall/autoblock/eat/noslow checks, plus an anti-knockback check
 * built on the fact that the server broadcasts the exact knockback impulse (S12) for every damaged
 * player. Attacks by other players are invisible as such — they are reconstructed by correlating an
 * attacker's arm-swing with the victim's hurt/crit packets in the same tick, and only when that
 * attribution is unambiguous (exactly one candidate swinger in range) does a combat check run.
 *
 * <p>Every check is a violation level with decay, and every threshold needs several independent events —
 * one packet can never flag. Teleports/rubber-banding (the #1 false-positive source per MWE's four-year
 * fix history), high victim latency, sneak edge-guards, and environmental damage all subtract or suspend
 * evidence rather than feed it. The module runs only inside an active Bedwars game and emits a private,
 * click-to-{@code /wdr} chat line — never a number, never an automated action.
 */
public final class CheaterDetector {

    static final int CHECK_WALL = 0, CHECK_KB = 1, CHECK_AUTOBLOCK = 2, CHECK_EAT = 3,
            CHECK_NOSLOW = 4, CHECK_PITCH = 5, CHECK_MULTI = 6;
    static final int CHECK_COUNT = 7;
    private static final String[] CHECK_LABEL = {
            "Kill Aura (through-wall hits)", "Anti-Knockback", "Autoblock",
            "Kill Aura (attacks while eating)", "No Slowdown", "Invalid Rotations", "Forcefield (multi-target)"};

    /** Attacker lag compensation: the victim is rewound up to this many ticks (~400ms) and the
     *  friendliest historical position wins, so laggy-but-legit hits never accrue wall evidence. */
    private static final int MAX_REWIND = 9;
    private static final double MAX_REACH = 3.15;
    /** Attribution range: swinger-to-victim squared distance beyond which a swing can't be the hit. */
    private static final double ATTRIB_RANGE_SQ = 42.0;
    /** Melee-plausible squared distance — tighter than the attribution net. The forcefield check requires
     *  victims within this of the swinger so an explosion's far-flung second victim can't be counted. */
    private static final double MELEE_RANGE_SQ = 20.25; // 4.5 blocks
    /** Recently changed block positions are ignored as wall obstructions for this many ticks — the
     *  attacker's client may not know about them yet (Bedwars is wall-placement central). */
    private static final int BLOCK_TTL = 20;
    /** Minimum ticks between two announcements of the same check on the same player (60s). */
    private static final int FLAG_COOLDOWN = 1200;
    /** Max announcements per player per check per world — after that, stay silent. */
    private static final int FLAG_LIMIT = 3;

    private static final CheaterDetector INSTANCE = new CheaterDetector();

    public static CheaterDetector get() {
        return INSTANCE;
    }

    /** One line at mod startup so the diag log ALWAYS states whether the detector is even enabled — the
     *  first thing to check when "it never flags", since the master toggle defaults OFF and each device
     *  keeps its own config. Logged before any game, independent of the active gate. */
    public static void logStartup() {
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null) { DiagLog.log("[AC] config: <not loaded>"); return; }
        DiagLog.log("[AC] config anticheat=" + cfg.anticheat + " (kb=" + cfg.acAntiKb
                + " wall=" + cfg.acThroughWall + " autoblock=" + cfg.acAutoblock + " eat=" + cfg.acEating
                + " noslow=" + cfg.acNoSlow + ")"
                + (cfg.anticheat ? "" : " — DISABLED; enable in Settings > Hypixel > Cheater Detector"));
    }

    private final Map<Integer, PlayerData> players = new HashMap<Integer, PlayerData>();
    private final Map<Long, Integer> recentBlocks = new HashMap<Long, Integer>();
    /** Lowercase names flagged by any check this session; read by the Urchin fusion surface. Cleared
     *  in {@link #reset()} (per-game), so a stale flag can never fuse across games. */
    private final Set<String> flaggedNames = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private int tick;

    // Liveness telemetry (written to the diag log every 30s while active + on teardown). Proves the
    // pipeline is alive even when nothing flags: if attributedHits climbs, combat attribution works and
    // silence just means no cheater crossed a threshold; if a check's accrual count climbs but it never
    // flags, its threshold needs tuning; all zeros means it isn't running (gate off / spectating).
    private int hbAttributed;
    private final int[] hbAccrual = new int[CHECK_COUNT];
    private final String[] CHECK_TAG = {"wall", "kb", "autoblock", "eat", "noslow", "pitch", "multi"};
    // Deeper "why didn't it fire" counters, so a 0 accrual can be traced to the exact failing precondition:
    // swings seen, swings while holding a sword, swings while the block flag was set, the highest block-hold
    // reached, metadata packets seen, and knockback windows armed vs actually evaluated.
    private int hbSwings, hbSwordSwing, hbBlkSwing, hbMaxUse, hbMeta, hbKbArm, hbKbEval, hbKbDbg;
    // Attribution decision gate (PLAN.md Phase 0): every attributeAttack entry lands in exactly one
    // bucket — attributed (hbAttributed), refractory (includes the benign duplicate call when hurt and
    // velocity both trigger within one tick), self-plausible, no candidate, or multiple candidates.
    // The shadow counters measure, WITHOUT changing behaviour, what the two candidate fixes would do:
    // staleUniq = a lone candidate whose swing was 2-3 ticks old (a 3-tick window recovers the hit),
    // staleConflict = attributed today but a 3-tick window would have made it ambiguous (recall LOST
    // by widening), selfNotOurs = dropped as self-plausible although our own attack log shows we never
    // hit that victim (exact self-tracking recovers it).
    private int hbAtTry, hbAtRefr, hbAtSelf, hbAtSelfNotOurs, hbAtNone, hbAtStaleUniq, hbAtMulti,
            hbAtStaleConflict;
    /** Our own melee attacks (entity id + tick, small ring), recorded by the read-only
     *  PlayerControllerMP.attackEntity tap. Telemetry only in Phase 0 — no check reads it. */
    private static final int SELF_ATK_LOG = 8;
    /** Ticks within which a logged self-attack claims a hit on that victim: refractory (3) plus a
     *  round-trip allowance, conservative so shadowSelfNotOurs UNDER-counts recoverable hits. */
    private static final int SELF_ATK_WINDOW = 8;
    private final int[] selfAtkId = new int[SELF_ATK_LOG];
    private final int[] selfAtkTick = new int[SELF_ATK_LOG];
    private int selfAtkHead = -1;
    private int hbSelfAtk;

    /** Whether checks should run, recomputed once per client tick (see {@link #onClientTick}). Read on
     *  the packet path in lieu of {@link #enabled} so the per-packet cost stays a single volatile read —
     *  {@code enabled} does scoreboard + server-IP string work that must not run hundreds of times a
     *  second. One-tick staleness is harmless (the tick loop resets all state the moment it flips off). */
    private static volatile boolean active;

    private CheaterDetector() {
        java.util.Arrays.fill(selfAtkId, -1);
    }

    // ------------------------------------------------------------------
    // Gating
    // ------------------------------------------------------------------

    /** Cheap pre-check the packet mixin runs before forwarding anything: active, in-world, and on the
     *  client thread (packet handlers are invoked once on the Netty thread and re-queued — only the
     *  client-thread pass is processed, so every handler below is single-threaded). */
    public static boolean wantsPackets() {
        if (!active) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) return false;
        return mc.isCallingFromMinecraftThread();
    }

    /** Run inside an active Bedwars game OR a Duels game — the two Hypixel PvP contexts this targets.
     *  Bedwars is gated to active matches (skips its lobby/pregame); Duels also covers its lobby, which
     *  is harmless (no combat there). Both gates keep the detector out of unrelated minigames and their
     *  posed entities. Singleplayer is allowed for dev testing against the integrated server's packets. */
    private static boolean enabled(Minecraft mc) {
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.anticheat) return false;
        if (mc.isSingleplayer()) return true;
        return HypixelContext.isOnHypixel()
                && (HypixelContext.isInActiveBedwarsGame() || HypixelContext.isInDuels());
    }

    // ------------------------------------------------------------------
    // Packet entry points (called from NetHandlerPlayClientMixin, client thread only)
    // ------------------------------------------------------------------

    // These run at the HEAD of vanilla packet handlers, so a detector exception would otherwise abort
    // the vanilla body (dropped block change → permanent ghost block, dropped move → rubber-banding).
    // Every entry point is fully guarded: the detector must never be able to break packet handling.

    public static void packetAnimation(int entityId, int type) {
        try { INSTANCE.handleAnimation(entityId, type); } catch (Throwable t) { fail(t); }
    }

    public static void packetEntityStatus(Entity e, byte opcode) {
        try {
            if (opcode == 2 && e instanceof EntityPlayer) INSTANCE.handleHurt((EntityPlayer) e);
        } catch (Throwable t) { fail(t); }
    }

    public static void packetVelocity(int entityId, double vx, double vy, double vz) {
        try { INSTANCE.handleVelocity(entityId, vx, vy, vz); } catch (Throwable t) { fail(t); }
    }

    public static void packetMetadataFlags(int entityId, byte flags) {
        try { INSTANCE.handleMetadataFlags(entityId, flags); } catch (Throwable t) { fail(t); }
    }

    public static void packetHeadLook(Entity e, float yawDeg) {
        try {
            if (e instanceof EntityPlayer) {
                PlayerData d = INSTANCE.dataFor((EntityPlayer) e);
                if (d != null) d.yawHead = yawDeg;
            }
        } catch (Throwable t) { fail(t); }
    }

    public static void packetLook(Entity e, float yawDeg, float pitchDeg) {
        try {
            if (e instanceof EntityPlayer) INSTANCE.handleLook((EntityPlayer) e, yawDeg, pitchDeg);
        } catch (Throwable t) { fail(t); }
    }

    public static void packetBlockChange(BlockPos pos) {
        try { INSTANCE.recentBlocks.put(pos.toLong(), INSTANCE.tick + BLOCK_TTL); } catch (Throwable t) { fail(t); }
    }

    /** Our own melee attack, from the PlayerControllerMP.attackEntity HEAD tap (client thread, the
     *  attack itself proceeds untouched). Recorded only — Phase 0 telemetry for shadowSelfNotOurs. */
    public static void selfAttack(Entity target) {
        try {
            if (!active || target == null) return;
            CheaterDetector cd = INSTANCE;
            cd.selfAtkHead = (cd.selfAtkHead + 1) % SELF_ATK_LOG;
            cd.selfAtkId[cd.selfAtkHead] = target.getEntityId();
            cd.selfAtkTick[cd.selfAtkHead] = cd.tick;
            cd.hbSelfAtk++;
        } catch (Throwable t) { fail(t); }
    }

    private boolean selfAttackedRecently(EntityPlayer victim) {
        for (int i = 0; i < SELF_ATK_LOG; i++) {
            if (selfAtkId[i] == victim.getEntityId() && tick - selfAtkTick[i] <= SELF_ATK_WINDOW) return true;
        }
        return false;
    }

    private static boolean loggedFailure;

    /** Swallow any detector error so vanilla packet handling continues; log the first one for tuning. */
    private static void fail(Throwable t) {
        if (!loggedFailure) {
            loggedFailure = true;
            DiagLog.log("[AC] detector error suppressed (further ones silenced): " + t);
        }
    }

    // ------------------------------------------------------------------
    // Packet handlers
    // ------------------------------------------------------------------

    private void handleAnimation(int entityId, int type) {
        // Only arm swings are load-bearing. The crit/sharpness particles (types 4/5) confirm a landed
        // hit, but not every melee hit is a crit — the reliable "a hit landed" signal we attribute on is
        // the knockback (S12), which accompanies every melee hit and arrives last (see handleVelocity).
        if (type != 0) return;
        EntityPlayer p = playerById(entityId);
        if (p == null) return;
        PlayerData d = dataFor(p);
        if (d == null) return;
        d.lastSwingTick = tick;
        hbSwings++;
        checkAutoblock(p, d);
    }

    private void handleHurt(EntityPlayer victim) {
        PlayerData d = dataForIncludingSelf(Minecraft.getMinecraft(), victim);
        if (d == null) return;
        d.lastHurtTick = tick;
        // Attribution is driven by the velocity packet, which arrives AFTER this hurt within the tick and
        // carries the knockback the combat checks need. On the rare reordering where velocity came first,
        // it's already here — run attribution now so we don't miss the hit.
        if (d.kbTick == tick) attributeAttack(victim, d);
    }

    private void handleVelocity(int entityId, double vx, double vy, double vz) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity e = mc.theWorld.getEntityByID(entityId);
        if (!(e instanceof EntityPlayer)) return;
        EntityPlayer victim = (EntityPlayer) e; // include self: a hit ON us broadcasts our S12 to us too
        PlayerData d = dataForIncludingSelf(mc, victim);
        if (d == null) return;
        // Any new impulse voids an in-flight measurement window (a combo restarts the clock).
        d.kbPending = false;
        d.kbVX = vx;
        d.kbVY = vy;
        d.kbVZ = vz;
        d.kbTick = tick;
        // Only damage knockback opens a window: S12 for players is only broadcast on velocityChanged,
        // and requiring a same-tick hurt filters plugin-sent velocity and fishing-rod pulls. This is also
        // the moment we have the whole hit — swing, hurt, and knockback — so attribution runs here.
        if (tick - d.lastHurtTick <= 1) {
            armKnockback(d);
            attributeAttack(victim, d);
        }
    }

    /** The window's start position is the last tick's sample — the victim's pre-knockback spot. */
    private void armKnockback(PlayerData d) {
        if (d.posSamples < 1) return;
        hbKbArm++;
        d.kbPending = true;
        d.kbStartX = d.x(0);
        d.kbStartY = d.y(0);
        d.kbStartZ = d.z(0);
    }

    private void handleLook(EntityPlayer p, float yawDeg, float pitchDeg) {
        PlayerData d = dataFor(p);
        if (d == null) return;
        d.yaw = yawDeg;
        d.pitch = pitchDeg;
        // Impossible rotation: a vanilla client clamps pitch to ±90° (±64 as an angle byte). Beyond
        // that is a derp/spinbot fingerprint that survives quantization (90.35° rounds to 64).
        if (Math.abs(pitchDeg) > 90.4f) {
            d.badPitch.add(1);
            hbAccrual[CHECK_PITCH]++;
            maybeFlag(p, d, CHECK_PITCH, d.badPitch, "pitch " + String.format("%.1f", pitchDeg) + "°");
        }
    }

    private void handleMetadataFlags(int entityId, byte flags) {
        EntityPlayer p = playerById(entityId);
        if (p == null || !isValidUuid(p)) return;
        // Track flags even during the settle window (bypass isValidTarget) so the spawn/re-entry resync
        // that clears a stale using-item flag isn't dropped, and state is correct when checks begin.
        PlayerData d = touch(p);
        hbMeta++;
        boolean nowUsing = (flags & 0x10) != 0;
        // Clear the block/eat counter the instant the flag drops — a legit release-then-attack sends the
        // swing in the next tick, and the counter must already read 0 by then (tickPlayer only zeroes it
        // at the next END, too late for a swing packet processed earlier in the same drain).
        if (!nowUsing && d.usingItem) d.useTicks = 0;
        d.sneaking = (flags & 0x02) != 0;
        d.sprinting = (flags & 0x08) != 0;
        d.usingItem = nowUsing;
    }

    // ------------------------------------------------------------------
    // Attack attribution: who hit this victim?
    // ------------------------------------------------------------------

    /** Correlates a victim's hurt/crit packet with the one nearby player whose arm-swing arrived this
     *  tick (or the previous — packets can straddle a tick boundary). Ambiguous cases (no candidate,
     *  several candidates, or ourselves plausibly the attacker) are dropped: mis-attribution is worse
     *  than missed coverage. */
    private void attributeAttack(EntityPlayer victim, PlayerData vd) {
        hbAtTry++;
        // Dedupe the hurt + up-to-two crit packets of one hit. A short refractory (not strict
        // tick-equality) is needed because those packets can straddle a tick boundary; it can't drop a
        // real second hit — a victim is invulnerable for ~10 ticks after taking damage, far longer.
        if (tick - vd.lastAttributedTick <= 3) { hbAtRefr++; return; }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer self = mc.thePlayer;
        if (victim == self) {
            // We can't be our own attacker; fall through with self excluded below.
        } else if (self.isSwingInProgress && self.getDistanceSqToEntity(victim) < ATTRIB_RANGE_SQ) {
            hbAtSelf++;
            // Shadow only: exact self-attack tracking would keep this hit if we never hit that victim.
            if (!selfAttackedRecently(victim)) hbAtSelfNotOurs++;
            return; // we plausibly threw this hit ourselves — ambiguous
        }
        EntityPlayer attacker = null;
        int staleCandidates = 0; // shadow only: otherwise-valid swingers 2-3 ticks old
        for (Object o : mc.theWorld.playerEntities) {
            EntityPlayer p = (EntityPlayer) o;
            if (p == victim || p == self || !p.isEntityAlive() || !isValidTarget(mc, p)) continue;
            PlayerData d = players.get(p.getEntityId());
            if (d == null || tick - d.lastSwingTick > 3) continue;
            if (p.getDistanceSqToEntity(victim) > ATTRIB_RANGE_SQ) continue;
            if (p.isOnSameTeam(victim)) continue; // teammates can't damage each other
            if (tick - d.lastSwingTick > 1) { staleCandidates++; continue; } // shadow-counted, never used
            if (attacker != null) { hbAtMulti++; return; } // two candidate swingers: ambiguous
            attacker = p;
        }
        if (attacker == null) {
            hbAtNone++;
            if (staleCandidates == 1) hbAtStaleUniq++; // a 3-tick swing window would attribute this hit
            return;
        }
        if (staleCandidates > 0) hbAtStaleConflict++;  // …but would make THIS hit ambiguous instead
        vd.lastAttributedTick = tick;
        hbAttributed++;
        onAttack(attacker, players.get(attacker.getEntityId()), victim, vd);
    }

    private void onAttack(EntityPlayer a, PlayerData ad, EntityPlayer v, PlayerData vd) {
        ClientSettings cfg = BedwarsQol.config;

        // A genuine melee hit BY this attacker: real knockback this tick (not fire-tick/fall), pushing
        // the victim away from the attacker (not from an explosion's centre or along an arrow's flight).
        // This is what separates a real sword hit from an explosion/arrow that merely damages a player
        // near a swinging miner in the same tick.
        boolean meleeHit = combatKb(vd) && kbAwayFromAttacker(a, v, vd);

        // Forcefield: a vanilla client hits at most one entity per tick, so two DISTINCT melee victims in
        // one tick is impossible. Both must be melee-range hits by this attacker.
        if (meleeHit && a.getDistanceSqToEntity(v) <= MELEE_RANGE_SQ) {
            if (ad.victimsTick == tick && ad.lastVictimId != v.getEntityId()) {
                ad.multi.add(1);
                hbAccrual[CHECK_MULTI]++;
                maybeFlag(a, ad, CHECK_MULTI, ad.multi, "2 melee victims in one tick");
            }
            ad.victimsTick = tick;
            ad.lastVictimId = v.getEntityId();
        }

        // Kill Aura B (MWE): landing a melee hit mid-consumption. Requires the attacker to STILL be using
        // the item at hit time — a legit cancel-eat-to-punch drops the flag (and useTicks) first. The
        // [7,32] window ports MWE's 1.7-client guards: <7 tolerates jitter, >32 means the state desynced.
        // Gated on meleeHit like wall/multi: a fire-tick, fall, or arrow on someone near a flailing
        // eater must not count as the eater's hit.
        if (cfg.acEating && meleeHit && ad.usingItem && holdingConsumable(a) && ad.useTicks >= 7 && ad.useTicks <= 32) {
            ad.eat.add(100);
            hbAccrual[CHECK_EAT]++;
            maybeFlag(a, ad, CHECK_EAT, ad.eat, "attacked " + ad.useTicks + " ticks into eating/drinking");
        }

        // Through-wall only judges genuine melee hits by this attacker; a fire-tick, fall-damage, or
        // stray-arrow coincidence with a nearby miner's swing never reaches it.
        if (cfg.acThroughWall && meleeHit) checkThroughWall(a, ad, v, vd);
    }

    /** True when this victim took a real melee/projectile knockback THIS tick — a substantial-impulse
     *  S12 — as opposed to fire-tick / fall / poison damage, which also emits an S19 hurt plus a
     *  near-zero S12 (any {@code attackEntityFrom} sets {@code velocityChanged}, but ambient sources
     *  impart almost no motion). */
    private boolean combatKb(PlayerData vd) {
        return vd.kbTick == tick && (vd.kbVX * vd.kbVX + vd.kbVZ * vd.kbVZ) >= 0.09;
    }

    /** True when the knockback pushed the victim roughly away from this attacker (within ~60°). Vanilla
     *  melee knockback is directed {@code normalize(victim - attacker)}; an explosion pushes away from
     *  the blast centre and an arrow along its flight, so a bystanding swinger who merely coincides with
     *  environmental/projectile damage fails this and is not blamed for it. */
    private boolean kbAwayFromAttacker(EntityPlayer a, EntityPlayer v, PlayerData vd) {
        double dx = v.posX - a.posX, dz = v.posZ - a.posZ;
        double dl = Math.sqrt(dx * dx + dz * dz);
        if (dl < 0.01) return true; // overlapping: direction is meaningless, don't use it to exclude
        double vh = Math.sqrt(vd.kbVX * vd.kbVX + vd.kbVZ * vd.kbVZ);
        if (vh < 0.01) return false;
        double dot = (vd.kbVX / vh) * (dx / dl) + (vd.kbVZ / vh) * (dz / dl);
        return dot > 0.5;
    }

    // ------------------------------------------------------------------
    // Checks
    // ------------------------------------------------------------------

    /** MWE KillAuraACheck port: cast the attacker's server look-ray and count 0.1-block steps of solid
     *  wall in front of every position the victim occupied in the last {@value #MAX_REWIND} ticks. Only
     *  if EVERY historical view was obstructed does evidence accrue (min over history = full lag comp);
     *  blocks changed in the last second are ignored (ghost blocks). */
    private void checkThroughWall(EntityPlayer a, PlayerData ad, EntityPlayer v, PlayerData vd) {
        if (ad.posSamples < 1 || vd.posSamples < 2) return;
        if (tick - ad.lastTeleportTick < 5 || tick - vd.lastTeleportTick < 5) return;
        float yaw = Float.isNaN(ad.yawHead) ? ad.yaw : ad.yawHead;
        float pitch = ad.pitch;
        if (Float.isNaN(yaw) || Float.isNaN(pitch)) return;

        Minecraft mc = Minecraft.getMinecraft();
        int crowd = nearbyPlayers(mc, v, 8.0);
        if (crowd >= 15) return; // confined-space brawl: attribution and rays are meaningless

        double ox = ad.x(0);
        double oy = ad.y(0) + (ad.sneaking ? 1.54 : 1.62);
        double oz = ad.z(0);
        double yawR = Math.toRadians(yaw), pitR = Math.toRadians(pitch);
        double dx = -Math.sin(yawR) * Math.cos(pitR);
        double dy = -Math.sin(pitR);
        double dz = Math.cos(yawR) * Math.cos(pitR);

        int best = Integer.MAX_VALUE;
        int rewind = Math.min(MAX_REWIND, vd.posSamples - 1);
        // The freshest server position is not in the ring yet — it's pushed at tick END, but attribution
        // runs mid-drain — so include it as an extra candidate (ago = -1). Without it a victim who just
        // rounded a corner into view is judged only against stale, behind-the-wall history.
        double liveX = (v == mc.thePlayer) ? v.posX : v.serverPosX / 32.0;
        double liveY = (v == mc.thePlayer) ? v.posY : v.serverPosY / 32.0;
        double liveZ = (v == mc.thePlayer) ? v.posZ : v.serverPosZ / 32.0;
        for (int ago = -1; ago <= rewind; ago++) {
            double vx = ago < 0 ? liveX : vd.x(ago);
            double vy = ago < 0 ? liveY : vd.y(ago);
            double vz = ago < 0 ? liveZ : vd.z(ago);
            // Victim hitbox with slop: 0.6 wide + 0.1 margin each side, feet-0.1 to +1.95.
            double minX = vx - 0.4, maxX = vx + 0.4;
            double minY = vy - 0.1, maxY = vy + 1.95;
            double minZ = vz - 0.4, maxZ = vz + 0.4;
            if (ox > minX && ox < maxX && oy > minY && oy < maxY && oz > minZ && oz < maxZ) return; // eyes inside target
            double entry = rayBoxEntry(ox, oy, oz, dx, dy, dz, minX, minY, minZ, maxX, maxY, maxZ);
            if (Double.isNaN(entry)) continue;
            int steps = wallSteps(mc, ox, oy, oz, dx, dy, dz, entry);
            if (steps < best) best = steps;
            if (best == 0) return; // at least one lag-compensated view was clean
        }
        if (best == Integer.MAX_VALUE) return; // look-ray never met the target: no verdict either way
        if (best <= 3) return; // <=0.3 block: a stale-rotation graze or hitbox slop, not a real wall

        if (crowd > 8) best = Math.max(1, best / 2); // crowded fight: damp, don't trust geometry fully
        ad.wall.add(Math.min(15, best) * 25);
        hbAccrual[CHECK_WALL]++;
        maybeFlag(a, ad, CHECK_WALL, ad.wall,
                String.format("hit through ~%.1f blocks of wall", best / 10.0));
    }

    /** Anti-knockback: the server told every observer the exact impulse it applied (S12). Over the next
     *  few ticks the victim must have moved along it — knockback alone displaces ~2.5x the impulse, far
     *  beyond what counter-strafing can hide. We take the PEAK outward displacement across the window
     *  (robust to a W-tapper who slides then walks back), and only after waiting out the victim's ping
     *  (their displacement reaches us a full round-trip late — the single biggest FP source if ignored).
     *  Sneakers, teleports, death, and anything solid or motion-arresting (walls, webs, ladders) along
     *  the path void the sample. */
    private void evalKnockback(EntityPlayer p, PlayerData d) {
        d.kbPending = false;
        if (!BedwarsQol.config.acAntiKb) return;
        // A sneaking, grounded victim is legitimately held by the vanilla edge-guard: knockback can move
        // them little or not at all, so we simply can't judge — don't accrue either way.
        if (!p.isEntityAlive() || d.sneaking || tick - d.lastTeleportTick < 6) return;
        double vh = Math.sqrt(d.kbVX * d.kbVX + d.kbVZ * d.kbVZ);
        if (vh < 0.3) return; // weak/ambiguous impulse (non-sprint tap, residual drag, fire/fall S12)
        double ux = d.kbVX / vh, uz = d.kbVZ / vh;
        Minecraft mc = Minecraft.getMinecraft();
        // Path guard (MoonLight's fix for the classic FP): anything that legitimately stops the slide —
        // a solid block, a cobweb, or a ladder the victim clings to — voids the sample.
        for (double t = 0.4; t <= 1.2; t += 0.4) {
            if (motionStopAt(mc, d.kbStartX + ux * t, d.kbStartY + 0.1, d.kbStartZ + uz * t)
                    || motionStopAt(mc, d.kbStartX + ux * t, d.kbStartY + 1.4, d.kbStartZ + uz * t)) {
                return;
            }
        }
        // Peak outward displacement over the samples we hold: a real knockback always shows a positive
        // peak; a fully-cancelled one never does, regardless of when the victim's echo reached us.
        double proj = -1e9;
        int win = Math.min(PlayerData.POS_HISTORY - 1, d.posSamples - 1);
        for (int ago = 0; ago <= win; ago++) {
            double pp = (d.x(ago) - d.kbStartX) * ux + (d.z(ago) - d.kbStartZ) * uz;
            if (pp > proj) proj = pp;
        }
        double needed = Math.min(0.3, 0.5 * vh);
        hbKbEval++;
        boolean cancelled = proj < needed;
        if (hbKbDbg < 25) { // a few real samples per game so the numbers can be tuned from the log
            hbKbDbg++;
            DiagLog.log(String.format("[AC] kb-eval %s proj=%.2f needed=%.2f vh=%.2f -> %s",
                    p.getName(), proj, needed, vh, cancelled ? "CANCELLED +15" : "clean -3"));
        }
        if (cancelled) {
            d.antiKb.add(15);
            hbAccrual[CHECK_KB]++;
            maybeFlag(p, d, CHECK_KB, d.antiKb,
                    String.format("moved %.2f of expected ~%.2f blocks after knockback", proj, 2.5 * vh));
        } else {
            d.antiKb.add(-3);
        }
    }

    /** Ticks to defer the anti-KB evaluation for this victim: 4 base + their round-trip, so the
     *  post-knockback displacement has actually reached us. Capped so the window stays inside the
     *  position ring. Ping comes from the tab list (S38), coarse but the only latency signal we get. */
    private int kbEvalDelay(EntityPlayer p) {
        int pingMs = 0;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getNetHandler() != null) {
            net.minecraft.client.network.NetworkPlayerInfo info =
                    mc.getNetHandler().getPlayerInfo(p.getUniqueID());
            if (info != null) pingMs = info.getResponseTime();
        }
        return 4 + Math.max(0, Math.min(8, pingMs / 50));
    }

    /** Autoblock: swinging while the sword-block flag has been held over 5 ticks. Legit 1.8 block-hitting
     *  must release the use-item state to attack, flickering the flag for at least a tick — state we
     *  track from raw metadata packets, so the equipment-flicker bypass (which only breaks the client's
     *  rendered pose) can't hide it. */
    private void checkAutoblock(EntityPlayer p, PlayerData d) {
        if (!BedwarsQol.config.acAutoblock || !holdingSword(p)) return;
        hbSwordSwing++;
        if (d.usingItem) hbBlkSwing++;
        // Must STILL be blocking as the swing lands. A legit block-hitter releases first (dropping the
        // flag and, via handleMetadataFlags, useTicks), so a real autoblock — sword swing with the
        // block flag never having dropped — is the only thing that satisfies both clauses.
        if (d.usingItem && d.useTicks > 5) {
            d.autoblock.add(5);
            hbAccrual[CHECK_AUTOBLOCK]++;
            maybeFlag(p, d, CHECK_AUTOBLOCK, d.autoblock, "swung " + d.useTicks + " ticks into blocking");
        } else if (d.useTicks == 0) {
            d.autoblock.add(-2);
        }
    }

    /** No Slowdown / omni-sprint: the sprint and use-item metadata flags set together while moving at
     *  sprint speed. A vanilla client cannot do this — using an item cuts forward input below the
     *  sprint threshold, so sprint always drops within a tick. */
    private void checkNoSlow(EntityPlayer p, PlayerData d) {
        if (!BedwarsQol.config.acNoSlow) return;
        if (!(d.usingItem && d.sprinting && d.useTicks > 5 && d.sprintTicks > 5 && holdingUsable(p))) {
            if (!d.usingItem) d.noSlow.add(-1);
            return;
        }
        if (d.posSamples < 4 || tick - d.lastTeleportTick < 5) return;
        double mx = d.x(0) - d.x(3), mz = d.z(0) - d.z(3);
        double speed = Math.sqrt(mx * mx + mz * mz) / 3.0; // blocks per tick; use-item walk ~0.09, sprint ~0.28
        if (speed < 0.18) return;
        // Rubber-band guard (MWE 2024-03): flying backwards relative to the head = a server correction
        // or knockback replay, not sprinting. Subtract instead of just skipping.
        if (!Float.isNaN(d.yawHead)) {
            float moveYaw = (float) Math.toDegrees(Math.atan2(-mx, mz));
            if (Math.abs(MathHelper.wrapAngleTo180_float(moveYaw - d.yawHead)) > 135f) {
                d.noSlow.add(-3);
                return;
            }
        }
        d.noSlow.add(2);
        hbAccrual[CHECK_NOSLOW]++;
        maybeFlag(p, d, CHECK_NOSLOW, d.noSlow,
                String.format("sprinting at %.1f m/s while using an item", speed * 20.0));
    }

    // ------------------------------------------------------------------
    // Tick driver
    // ------------------------------------------------------------------

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || !enabled(mc)) {
            endSession("(game gate closed)");
            logIdle(mc); // periodic "here's WHY it's off" so silence is never ambiguous
            if (!players.isEmpty() || !recentBlocks.isEmpty()) reset(); // stray state accrued while idle
            return;
        }
        if (!active) {
            active = true;
            lastIdleReason = "";
            DiagLog.log("[AC] active — entered a Bedwars game, watching now");
        }
        tick++;
        tickSelf(mc);
        for (Object o : mc.theWorld.playerEntities) {
            EntityPlayer p = (EntityPlayer) o;
            if (p == mc.thePlayer || !isValidTarget(mc, p)) continue;
            PlayerData d = dataFor(p);
            if (d == null) continue;
            tickPlayer(p, d);
        }
        if (tick % 600 == 0) heartbeat("alive"); // liveness proof to the diag log every ~30s
        if (tick % 40 == 0) prune(mc);
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        // The world switch is the FIRST signal a game ended — it used to reset() before the tick loop
        // could print its summary, which zeroed every per-game "ended" line. End the session here.
        endSession("(world changed)");
        if (!players.isEmpty() || !recentBlocks.isEmpty()) reset();
    }

    /** Atomic end-of-session transition: one truthful summary from the still-live counters, then the
     *  gate flag, then a full state clear. Idempotent — the gate-close tick and a world change can
     *  both call it, whichever fires first wins and the other is a no-op. */
    private void endSession(String why) {
        if (!active) return;
        heartbeat("ended " + why);
        active = false;
        lastIdleReason = "";
        reset();
    }

    /** We are only ever a victim, never a check target, but the wall check needs our position history —
     *  and for ourselves it is exact (own movement is client-authoritative), not 1/32-quantized. */
    private void tickSelf(Minecraft mc) {
        EntityPlayer self = mc.thePlayer;
        PlayerData d = dataForIncludingSelf(mc, self);
        if (d == null) return;
        if (d.posSamples > 0) {
            double jx = self.posX - d.x(0), jy = self.posY - d.y(0), jz = self.posZ - d.z(0);
            if (jx * jx + jy * jy + jz * jz > 16.0) d.lastTeleportTick = tick;
        }
        d.pushPos(self.posX, self.posY, self.posZ);
    }

    private void tickPlayer(EntityPlayer p, PlayerData d) {
        // Server-truth position sample: the tracker's last received absolute position (1/32 fixed
        // point), bypassing the entity's 3-tick render lerp entirely.
        double sx = p.serverPosX / 32.0, sy = p.serverPosY / 32.0, sz = p.serverPosZ / 32.0;
        if (d.posSamples > 0) {
            double jx = sx - d.x(0), jy = sy - d.y(0), jz = sz - d.z(0);
            if (jx * jx + jy * jy + jz * jz > 16.0) d.lastTeleportTick = tick;
        }
        d.pushPos(sx, sy, sz);

        d.useTicks = d.usingItem ? d.useTicks + 1 : 0;
        d.sprintTicks = d.sprinting ? d.sprintTicks + 1 : 0;
        if (d.useTicks > hbMaxUse) hbMaxUse = d.useTicks;

        if (d.kbPending && tick - d.kbTick >= kbEvalDelay(p)) evalKnockback(p, d);

        checkNoSlow(p, d);

        // Fast per-tick decay for the magnitude-based checks; a slow -1/s backstop for the rest so no
        // stray coincidence can accumulate to a flag over a long game (they earn their thresholds from
        // genuine bursts, which climb far faster than 1/s).
        d.wall.tick();
        d.eat.tick();
        if (tick % 20 == 0) {
            d.antiKb.tick();
            d.autoblock.tick();
            d.noSlow.tick();
            d.badPitch.tick();
            d.multi.tick();
        }
    }

    private void prune(Minecraft mc) {
        Iterator<Map.Entry<Integer, PlayerData>> it = players.entrySet().iterator();
        while (it.hasNext()) {
            if (mc.theWorld.getEntityByID(it.next().getKey()) == null) it.remove();
        }
        Iterator<Map.Entry<Long, Integer>> bt = recentBlocks.entrySet().iterator();
        while (bt.hasNext()) {
            if (bt.next().getValue() < tick) bt.remove();
        }
    }

    /** Whether {@code name} has any live Cheater Detector flag this session (Urchin fusion input). */
    public boolean hasLiveFlags(String name) {
        return name != null && flaggedNames.contains(name.toLowerCase());
    }

    private void reset() {
        players.clear();
        recentBlocks.clear();
        flaggedNames.clear();
        hbAttributed = 0;
        java.util.Arrays.fill(hbAccrual, 0);
        hbSwings = hbSwordSwing = hbBlkSwing = hbMaxUse = hbMeta = hbKbArm = hbKbEval = hbKbDbg = 0;
        hbAtTry = hbAtRefr = hbAtSelf = hbAtSelfNotOurs = hbAtNone = hbAtStaleUniq = hbAtMulti = 0;
        hbAtStaleConflict = hbSelfAtk = 0;
        selfAtkHead = -1;
        java.util.Arrays.fill(selfAtkId, -1);
    }

    private int idleTicks;
    private String lastIdleReason = "";

    /** When the master toggle is on but no game is running, log WHY the detector is asleep (on change,
     *  and every ~30s), so "no [AC] activity" is never mistaken for "broken". Silent when the feature is
     *  off — the startup config line already states that. */
    private void logIdle(Minecraft mc) {
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.anticheat) return;
        String reason;
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) reason = "no world loaded";
        else if (!HypixelContext.isOnHypixel() && !mc.isSingleplayer()) reason = "not on Hypixel";
        else if (HypixelContext.isInBedwars()) reason = "in Bedwars lobby/pregame, waiting for the game to start";
        else reason = "not in a Bedwars game or Duel";
        idleTicks++;
        if (!reason.equals(lastIdleReason) || idleTicks % 600 == 0) {
            DiagLog.log("[AC] idle — " + reason);
            lastIdleReason = reason;
        }
    }

    /** Liveness line for the diag log. {@code tracking} = players under watch; {@code attributedHits} =
     *  combat hits successfully tied to an attacker (climbs = the pipeline works); {@code accruals} =
     *  per-check evidence events (a check accruing but never flagging = its threshold needs tuning; all
     *  zero across a whole game = it isn't seeing combat, e.g. spectating or the gate is off). */
    private void heartbeat(String state) {
        StringBuilder sb = new StringBuilder("[AC] ").append(state)
                .append(" tracking=").append(players.size())
                .append(" attributedHits=").append(hbAttributed)
                .append(" accruals[");
        for (int i = 0; i < CHECK_COUNT; i++) {
            if (i > 0) sb.append(' ');
            sb.append(CHECK_TAG[i]).append('=').append(hbAccrual[i]);
        }
        sb.append("] why[swings=").append(hbSwings).append(" swordSwing=").append(hbSwordSwing)
                .append(" blkSwing=").append(hbBlkSwing).append(" maxUseTicks=").append(hbMaxUse)
                .append(" meta=").append(hbMeta).append(" kbArm=").append(hbKbArm)
                .append(" kbEval=").append(hbKbEval).append(']');
        sb.append(" attrib[try=").append(hbAtTry).append(" refr=").append(hbAtRefr)
                .append(" self=").append(hbAtSelf).append(" selfNotOurs=").append(hbAtSelfNotOurs)
                .append(" none=").append(hbAtNone).append(" staleUniq=").append(hbAtStaleUniq)
                .append(" multi=").append(hbAtMulti).append(" staleConflict=").append(hbAtStaleConflict)
                .append("] selfAtk=").append(hbSelfAtk);
        DiagLog.log(sb.toString());
    }

    // ------------------------------------------------------------------
    // Flagging
    // ------------------------------------------------------------------

    private void maybeFlag(EntityPlayer p, PlayerData d, int check, Vl vl, String detail) {
        if (!vl.tripped()) return;
        vl.reset(); // a re-flag must earn the full threshold again
        if (tick - d.lastFlagTick[check] < FLAG_COOLDOWN || d.flagCount[check] >= FLAG_LIMIT) return;
        d.lastFlagTick[check] = tick;
        d.flagCount[check]++;
        if (p.getName() != null) flaggedNames.add(p.getName().toLowerCase());
        announce(p, check, d.flagCount[check], detail);
        DiagLog.log("[AC] " + p.getName() + " flagged " + CHECK_LABEL[check] + " | " + detail);
    }

    private static void announce(EntityPlayer p, int check, int count, String detail) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        String name = p.getName();
        ChatComponentText msg = new ChatComponentText("");
        msg.appendSibling(colored("⚠ ", EnumChatFormatting.RED));
        msg.appendSibling(colored(name, EnumChatFormatting.YELLOW));
        msg.appendSibling(colored(" suspicious: ", EnumChatFormatting.GRAY));
        msg.appendSibling(colored(CHECK_LABEL[check], EnumChatFormatting.RED));
        if (count > 1) msg.appendSibling(colored(" (x" + count + ")", EnumChatFormatting.DARK_GRAY));
        ChatComponentText hover = new ChatComponentText(
                EnumChatFormatting.GRAY + detail + "\n" + EnumChatFormatting.DARK_GRAY
                        + "Click to prefill /wdr " + name + " — review before sending.");
        msg.getChatStyle()
                .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/wdr " + name));
        mc.thePlayer.addChatMessage(ModChat.mark(msg));
        mc.thePlayer.playSound("note.pling", 1.0f, 1.2f);
    }

    private static ChatComponentText colored(String text, EnumChatFormatting color) {
        ChatComponentText c = new ChatComponentText(text);
        c.getChatStyle().setColor(color);
        return c;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private EntityPlayer playerById(int entityId) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity e = mc.theWorld.getEntityByID(entityId);
        if (!(e instanceof EntityPlayer) || e == mc.thePlayer) return null;
        return (EntityPlayer) e;
    }

    private PlayerData dataFor(EntityPlayer p) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!isValidTarget(mc, p)) return null;
        return touch(p);
    }

    private PlayerData dataForIncludingSelf(Minecraft mc, EntityPlayer p) {
        if (p != mc.thePlayer && !isValidTarget(mc, p)) return null;
        return touch(p);
    }

    /** Fetch (or create) this player's record, resetting it on re-entry. When {@code ticksExisted}
     *  regresses, the entity id was reused or the player re-entered tracking range, so the buffered
     *  position history and metadata flags are stale — dropping them prevents a "stuck blocking / stuck
     *  sprinting" state from carrying over and slowly flagging an innocent. */
    private PlayerData touch(EntityPlayer p) {
        PlayerData d = players.get(p.getEntityId());
        if (d != null && p.ticksExisted < d.lastTicksExisted) d = null;
        if (d == null) {
            d = new PlayerData();
            players.put(p.getEntityId(), d);
        }
        d.lastTicksExisted = p.ticksExisted;
        return d;
    }

    /** Real (or nicked-real) players only: UUID v2 is a Hypixel NPC/watchdog bot, other versions are
     *  fake entities. */
    private static boolean isValidUuid(EntityPlayer p) {
        int version = p.getUniqueID().version();
        return version == 1 || version == 4;
    }

    /** A player the checks may run against: real UUID, not self, past the spawn-settle buffer that skips
     *  the first second of stale state. (Metadata tracking deliberately bypasses the settle buffer via
     *  {@link #touch} so flag state is already correct the instant checks begin.) */
    private static boolean isValidTarget(Minecraft mc, EntityPlayer p) {
        return p != mc.thePlayer && p.ticksExisted >= 20 && isValidUuid(p);
    }

    private static boolean holdingSword(EntityPlayer p) {
        ItemStack s = p.getHeldItem();
        return s != null && s.getItem() instanceof ItemSword;
    }

    private static boolean holdingConsumable(EntityPlayer p) {
        ItemStack s = p.getHeldItem();
        return s != null && s.getMaxItemUseDuration() == 32; // food + drinkable potions
    }

    private static boolean holdingUsable(EntityPlayer p) {
        ItemStack s = p.getHeldItem();
        return s != null && s.getMaxItemUseDuration() > 0; // sword block, food, potion, bow
    }

    private static int nearbyPlayers(Minecraft mc, EntityPlayer around, double range) {
        int n = 0;
        for (Object o : mc.theWorld.playerEntities) {
            EntityPlayer p = (EntityPlayer) o;
            if (p != around && p.getDistanceSqToEntity(around) < range * range) n++;
        }
        return n;
    }

    /** Anything that legitimately arrests a knocked-back victim: a movement-blocking block, or a cobweb /
     *  ladder (whose material does NOT report {@code blocksMovement} but which clamp motion to near-zero).
     *  No recent-block skip here — however new, a real block stops the slide (Bedwars clutch-placing
     *  behind yourself mid-combo is routine). */
    private boolean motionStopAt(Minecraft mc, double x, double y, double z) {
        Block b = mc.theWorld.getBlockState(new BlockPos(x, y, z)).getBlock();
        if (b instanceof BlockWeb || b instanceof BlockLadder) return true;
        return b.getMaterial().blocksMovement();
    }

    private boolean isRecentBlock(BlockPos bp) {
        Integer expiry = recentBlocks.get(bp.toLong());
        return expiry != null && expiry >= tick;
    }

    /** Steps of solid, full-cube, not-recently-changed wall along the ray before it reaches the target
     *  box (0.1-block spacing, so 10 steps ~= one full block of wall). */
    private int wallSteps(Minecraft mc, double ox, double oy, double oz,
                          double dx, double dy, double dz, double entry) {
        int steps = 0;
        for (double t = 0.05; t < entry; t += 0.1) {
            BlockPos bp = new BlockPos(ox + dx * t, oy + dy * t, oz + dz * t);
            if (isRecentBlock(bp)) continue;
            Block b = mc.theWorld.getBlockState(bp).getBlock();
            if (b.getMaterial().blocksMovement() && b.isFullCube()) steps++;
        }
        return steps;
    }

    /** Slab-method ray/AABB entry distance, or NaN when the ray misses within {@link #MAX_REACH}. */
    private static double rayBoxEntry(double ox, double oy, double oz,
                                      double dx, double dy, double dz,
                                      double minX, double minY, double minZ,
                                      double maxX, double maxY, double maxZ) {
        double tmin = 0.0, tmax = MAX_REACH;
        double[] o = {ox, oy, oz}, d = {dx, dy, dz}, lo = {minX, minY, minZ}, hi = {maxX, maxY, maxZ};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9) {
                if (o[i] < lo[i] || o[i] > hi[i]) return Double.NaN;
                continue;
            }
            double t1 = (lo[i] - o[i]) / d[i];
            double t2 = (hi[i] - o[i]) / d[i];
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            if (t1 > tmin) tmin = t1;
            if (t2 < tmax) tmax = t2;
            if (tmin > tmax) return Double.NaN;
        }
        return tmin;
    }
}
