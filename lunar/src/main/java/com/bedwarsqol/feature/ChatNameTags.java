package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.stats.BedwarsMode;
import com.bedwarsqol.stats.BedwarsModeDetector;
import com.bedwarsqol.stats.BedwarsStats;
import com.bedwarsqol.stats.HypixelContext;
import com.bedwarsqol.stats.StatsCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.weavemc.api.event.ChatEvent;
import net.weavemc.api.event.SubscribeEvent;
import net.weavemc.api.event.TickEvent;
import net.weavemc.api.event.WorldEvent;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Annotates the sender's name in chat, inline. Two tags sit at the <i>front</i> of the line, ahead of
 * the rank and name and both owned by the <b>Chat Stats</b> sub-toggle of <b>Hypixel Stats</b>: the
 * player's current-mode FKDR bracket ({@code [4.45]}) and, for an account that has never played
 * Bedwars, a {@code [New]} flag. The trailing name tags belong to <b>Nick Utils</b> and follow the
 * name: a denicked player's real name ({@code (Dewier2)} — needs Auto Denick) and a {@code (Nicked)}
 * disguise flag (needs Nick Notify). For a denicked player the FKDR is the <i>real</i> account's.
 *
 * <p><b>Roster lines are the exception to "one tag per line".</b> A {@code /party list} line
 * ({@code "Party Leader: [VIP] SrCobb ●"}) names several players and no sender, so it gets one bracket
 * per member, spliced in front of that member's own rank tag instead of one at the head of the line —
 * see {@link #trackRoster}.
 *
 * <p><b>How it stays correct without baking stale data.</b> The denick and the FKDR both resolve
 * asynchronously, often after the message is already on screen. When the line arrives we splice in two
 * <i>empty, mutable</i> {@link ChatComponentText} components — one at the head of the sibling list (the
 * FKDR), one at the tail (the name tag) — and keep references to them. A light tick loop re-renders
 * each tracked component from the live caches and, when any changed, calls {@link GuiNewChat#refreshChat()}
 * once so already-shown lines (a player's first message included) pick up the newly-resolved data.
 *
 * <p><b>Why splice in place instead of reassigning.</b> We mutate the message component's own sibling
 * list rather than swapping the event's message reference. That is what lets the same path work under
 * Forge and Weave alike: Weave's hook renders from the original component reference (its
 * {@code ChatEvent.Received} message is final and never read back after dispatch), so an in-place
 * mutation is seen while a reassignment would be ignored. Inserting the FKDR at sibling index 0 lands it
 * at the very front because Hypixel's formatted chat lines carry an empty-text root with all content in
 * siblings. Prepending ahead of the name also keeps us clear of Hypixel's rank-card hover, which hangs
 * off the name component. (Lunar can still detach the <i>stored</i> line from this reference by storing
 * a converted copy at add time — {@link #ensureVisible} detects and repairs that after each tag write.)
 *
 * <p><b>Where it runs, and when it decides.</b> Every name-shaped line received on Hypixel is spliced
 * and tracked; whether its sender may be <i>trusted</i> is decided on the tick loop, not at receive.
 * The two context signals that decision needs — the Bedwars sidebar and the identities-visible scan —
 * converge seconds after a login or world switch and can blip mid-session (Hypixel rebuilds the
 * sidebar objective on update; tab churn can trip a scan), and a receive-time verdict made inside such
 * a window used to drop the line forever (the AA410/PackageList misses). Deferring costs nothing:
 * holders are invisible while empty. The trust rules themselves are unchanged — real identities (an
 * active game, or a Bedwars lobby whose skins aren't stripped, see {@link Denicks#identitiesVisible()})
 * trust every recognised shape; the anonymized pregame queue trusts only <i>typed</i> chat (Hypixel
 * obfuscates the queue's join/leave broadcasts but never what a player types), so those broadcasts'
 * junk names still never drive lookups. A line still undecided after {@link #DECIDE_WINDOW_MS} can
 * never be trusted and is dropped, and every decision is final: a line is judged by the context it was
 * sent in, never re-judged by a later one. (The queue→game transition is a world change, which clears
 * all tracked lines, so a stale queue line can never meet the game's real-identity context.)
 */
public final class ChatNameTags {

    /** ~0.5s between back-patch passes: cheap, and async stats rarely land faster than this anyway. */
    private static final int TICK_INTERVAL = 10;
    /** Width-matched to {@code [x.xx]} so the line doesn't jump when async FKDR lands. */
    private static final String FKDR_PENDING = "§7[-.--]§r ";
    /** Cap on tracked lines; oldest beyond this simply stop back-patching (their last render sticks). */
    private static final int MAX_TRACKED = 100;
    /**
     * How long a line may stay undecided while the context signals settle. Long enough to ride out
     * login/world-switch convergence and mid-session blips; far shorter than the queue's lifetime, so
     * an untrusted broadcast is dropped long before its context could ever legitimately change.
     */
    private static final long DECIDE_WINDOW_MS = 5000L;
    /** Stand-in "sender" for a roster line's holder: it has members, not one sender. Diag/index only. */
    private static final String ROSTER_SENDER = "party-list";

    private final Deque<Holder> tracked = new ArrayDeque<Holder>();
    private final Map<String, List<Holder>> byName = new HashMap<String, List<Holder>>();
    private int ticks;
    /** Last CTX diag line, so context flips log once instead of every tick. */
    private String lastCtx = "";

    /** The registered singleton, so a command (the chat-mode switch) can force an immediate repaint. */
    private static ChatNameTags active;

    public ChatNameTags() {
        active = this;
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        logWipe();
        tracked.clear();
        byName.clear();
        ticks = 0;
        ChatHoverStats.clearPins(); // tab identities reset with the world; drop name->UUID pins
    }

    /** What a world change is about to discard — liveUntagged are trusted lines whose FKDR never landed. */
    private void logWipe() {
        if (tracked.isEmpty()) return;
        int undecided = 0, live = 0, liveUntagged = 0;
        for (Holder h : tracked) {
            if (!h.decided) undecided++;
            else if (h.live) {
                live++;
                if (h.untagged()) liveUntagged++;
            }
        }
        DiagLog.log("WIPE worldChange tracked=" + tracked.size() + " undecided=" + undecided
                + " live=" + live + " liveUntagged=" + liveUntagged);
    }

    @SubscribeEvent
    public void onChat(ChatEvent.Received event) {
        if (event == null || event.getMessage() == null) return;
        // Never annotate the mod's own output (stats cards, command replies, alerts): its headers read
        // as lone usernames and would get a bogus FKDR bracket. Vital here because Weave routes local
        // addChatMessage through the same ChatEvent.Received hook as server chat. See ModChat.
        if (ModChat.isMarked(event.getMessage())) return;
        ClientSettings cfg = BedwarsQol.config;
        if (!enabled(cfg)) return;
        // Only the stable gate here (server IP). The Bedwars/identities context signals are
        // eventually-consistent and must not veto a line at receive — see the class javadoc.
        if (!HypixelContext.isOnHypixel()) return;

        // A /party list roster line names several players and no sender; it is tagged per member and
        // never falls through to the single-sender path (its "Party Leader:" head is a label, not a
        // player — the accounts Leader/Moderators/Members exist and used to be looked up here).
        List<String> roster = ChatSender.rosterMembers(event.getMessage());
        if (!roster.isEmpty()) {
            if (chatStatsEnabled(cfg)) trackRoster(event.getMessage(), roster, cfg);
            return;
        }

        // typedChatName is extractName's colon branch: whenever it is non-null the two agree, so one
        // sender string plus a "was this a typed line" flag captures both candidates for decide().
        String sender = ChatSender.extractName(event.getMessage());
        if (sender == null) return;
        if (isSelf(sender)) return;
        boolean typedShape = ChatSender.typedChatName(event.getMessage()) != null;

        // FKDR at the head of the line, the (real name)/(Nicked) tag at the tail — both empty for now,
        // filled once the line is trusted and back-patched as async data lands.
        ChatComponentText prefix = new ChatComponentText("");
        ChatComponentText suffix = new ChatComponentText("");
        String hoist = prependSibling(event.getMessage(), prefix);
        event.getMessage().appendSibling(suffix);
        DiagLog.log("RECV sender=" + sender + " typed=" + typedShape + " hoist=" + hoist);

        Holder h = new Holder(sender, typedShape, System.currentTimeMillis(), event.getMessage(), prefix, suffix);
        track(h);
        // With the context already settled (the common case), decide and render immediately so the
        // first frame isn't blank when the stats are cached.
        decide(h, operate(), typedChatContext());
        if (h.live) h.apply(cfg);
        // What the line reads as right now — a cache-hit tag included — is what a Lunar-stored COPY of
        // it will still read as later; ensureVisible() relocates the copy by exactly this text.
        h.baselineText = safeText(event.getMessage());
    }

    /**
     * Track a {@code /party list} roster line: one FKDR bracket in front of <i>each</i> member, ahead of
     * the rank tag they wear ({@code "[1.25] [MVP++] Dewier"}) so it reads in the same order as every
     * other chat line. Placement goes through {@link ChatSplice}, which inserts a mutable holder ahead
     * of a named player, splitting the covering leaf and preserving its style and rank-card hover.
     * The holders are empty when spliced, so the line's text — and therefore every later member's
     * offset — is unchanged by the ones before it. Your own name is skipped, as everywhere else in chat.
     *
     * <p>The tags then back-patch on the same tick loop as any other line: a member who isn't cached
     * yet shows the width-matched placeholder until their lookup lands.
     */
    private void trackRoster(IChatComponent message, List<String> members, ClientSettings cfg) {
        // Same reason as the single-sender path: a name still inside the root's own text isn't reachable
        // as a sibling, and the splice declines it.
        String hoist = hoistRootText(message);
        List<Holder.Slot> slots = new ArrayList<Holder.Slot>();
        for (String name : members) {
            if (isSelf(name)) continue;
            ChatComponentText tag = new ChatComponentText("");
            if (ChatSplice.spliceBeforeRankedName(message, name, tag)) {
                slots.add(new Holder.Slot(name, tag));
            }
        }
        DiagLog.log("ROSTER members=" + members.size() + " tagged=" + slots.size() + " hoist=" + hoist);
        if (slots.isEmpty()) return; // a party of just you, or a line we couldn't splice
        // Trusted like typed chat: a roster is your own party's state, which Hypixel never anonymizes.
        Holder h = new Holder(ROSTER_SENDER, true, System.currentTimeMillis(), message,
                new ChatComponentText(""), new ChatComponentText(""));
        h.roster = slots;
        track(h);
        decide(h, operate(), typedChatContext());
        if (h.live) h.apply(cfg);
        h.baselineText = safeText(message);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.Post event) {
        if (tracked.isEmpty()) return;
        if (++ticks < TICK_INTERVAL) return;
        ticks = 0;

        ClientSettings cfg = BedwarsQol.config;
        boolean moduleOn = enabled(cfg);
        boolean identitiesReal = operate();
        boolean typedCtx = typedChatContext();
        String ctx = "CTX bedwars=" + typedCtx + " identities=" + identitiesReal + " module=" + moduleOn;
        if (!ctx.equals(lastCtx)) {
            lastCtx = ctx;
            DiagLog.log(ctx);
        }
        boolean changed = false;
        for (java.util.Iterator<Holder> it = tracked.iterator(); it.hasNext(); ) {
            Holder h = it.next();
            if (!h.decided) {
                if (moduleOn) decide(h, identitiesReal, typedCtx);
                if (h.decided && !h.live) { // untrusted shape for its context — drop, still invisible
                    it.remove();
                    unindex(h);
                    continue;
                }
            }
            // When the module/context goes away we stop updating but leave prior renders as-is.
            if (moduleOn && h.live && (identitiesReal || typedCtx) && h.apply(cfg)) {
                ensureVisible(h);
                changed = true;
            }
        }
        if (changed) refreshChat();
    }

    /**
     * Settle whether this line's sender can be trusted, given the CURRENT context: real identities
     * trust every recognised shape, a typed-Bedwars context trusts typed lines only. Neither holding
     * yet leaves the line undecided (the signals converge within a second or two) until the decide
     * window lapses, after which it can never be trusted. Both verdicts are final.
     */
    private static void decide(Holder h, boolean identitiesReal, boolean typedCtx) {
        if (h.decided) return;
        if (identitiesReal || (typedCtx && h.typedShape)) {
            h.decided = true;
            h.live = true;
            DiagLog.log("TRUST sender=" + h.sender + " via=" + (identitiesReal ? "identities" : "typed")
                    + " after=" + (System.currentTimeMillis() - h.receivedMs) + "ms");
        } else if (System.currentTimeMillis() - h.receivedMs > DECIDE_WINDOW_MS) {
            h.decided = true;
            DiagLog.log("DROP sender=" + h.sender + " undecided past window (typed=" + h.typedShape + ")");
        }
    }

    // ---- tag construction -------------------------------------------------

    /**
     * The {@code [prefix, suffix]} annotation for a sender given the current caches — prefix is the
     * leading Chat Stats bracket (the FKDR, or a {@code [New]} never-played flag), suffix the trailing
     * Nick Utils name/nick tag; either may be "". Auto Denick reveals the real name and switches the
     * FKDR to the real account; with it off we never reveal the account (nor its stats) but Nick Notify
     * can still flag the disguise as {@code (Nicked)}.
     */
    private static String[] buildParts(Holder h, ClientSettings cfg) {
        String sender = h.sender;
        String real = Denicks.realNameForNick(sender);
        // Name-reveal and (Nicked) tags are Nick Utils features; keep them behind that module even when
        // Chat Stats alone enabled this path for the leading bracket.
        boolean reveal = cfg.nickUtils && real != null && cfg.autoDenick;

        UUID uuid = null;
        if (!reveal) {
            uuid = ChatSender.uuidInTab(sender);
            if (uuid != null) {
                h.tabUuid = uuid;
            } else if (h.tabUuid != null) {
                // Tab churn (remove→re-add on updates, death/spectate) blips a present player out of
                // the map for a tick or two. Falling through to the name key here repainted a resolved
                // FKDR as the pending placeholder AND fired a redundant name-keyed fetch — so once a
                // line has resolved its tab UUID, it keeps that key through dropouts.
                uuid = h.tabUuid;
                if (!h.pinLogged) {
                    h.pinLogged = true;
                    DiagLog.log("PIN sender=" + sender + " kept uuid through tab dropout");
                }
            }
        }
        String fkdrName = reveal ? real : sender;
        BedwarsStats st = resolveStats(fkdrName, uuid);

        String suffix = "";
        if (reveal) {
            suffix = " §7(§f" + real + "§7)";
        } else if (cfg.nickUtils && cfg.nickNotify && (real != null || (st != null && st.state == BedwarsStats.State.NICKED))) {
            suffix = " §7(§cNicked§7)";
        }
        return new String[]{fkdrBracket(st, cfg), suffix};
    }

    /**
     * The stats bracket a player's name is annotated with: their current-mode FKDR, or {@code [New]} for
     * an account with no Bedwars games, and "" when Chat Stats is off or the lookup ended in a state we
     * don't annotate. While stats are still fetching, a width-matched placeholder reserves the slot so
     * the line doesn't shift when the real FKDR back-patches.
     *
     * <p>Chat Stats (a Hypixel Stats sub-toggle) owns this bracket; {@code [New]} rides with the FKDR
     * here, not with the Nick Utils tags. Shared by the single-sender line head and the per-member
     * roster tags so both read identically.
     */
    private static String fkdrBracket(BedwarsStats st, ClientSettings cfg) {
        if (!chatStatsEnabled(cfg)) return "";
        if (st == null) return FKDR_PENDING;
        if (st.state == BedwarsStats.State.OK) {
            double fkdr = st.statsFor(chatDisplayMode(cfg)).fkdr;
            return "§7[" + BedwarsStats.fkdrColor(fkdr) + fmt2(fkdr) + "§7]§r ";
        }
        if (st.state == BedwarsStats.State.NEVER_PLAYED) return "§7[New]§r ";
        return "";
    }

    /**
     * The gamemode whose FKDR the chat bracket shows: the user's forced {@code /bw mode} choice, or —
     * when that is "auto" — the live per-game detection ({@link BedwarsModeDetector}, overall in a lobby).
     */
    private static BedwarsMode chatDisplayMode(ClientSettings cfg) {
        return BedwarsModeDetector.displayMode(cfg);
    }

    /**
     * Re-render every already-tracked line from the live config right now, then refresh chat — so a
     * {@code /bw mode} switch back-patches the FKDR on messages already on screen instead of waiting for
     * the next tick. Marshalled onto the client thread since it touches the chat GUI.
     */
    public static void refreshDisplayMode() {
        final ChatNameTags inst = active;
        if (inst == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) mc.addScheduledTask(inst::repaintTracked);
        else inst.repaintTracked();
    }

    private void repaintTracked() {
        ClientSettings cfg = BedwarsQol.config;
        // No enabled() gate: a toggle-OFF must still reach the holders to clear what they last wrote,
        // even when that toggle was the only thing keeping the module armed.
        if (cfg == null) return;
        boolean changed = false;
        for (Holder h : tracked) {
            if (h.live && h.apply(cfg)) {
                ensureVisible(h);
                changed = true;
            }
        }
        if (changed) refreshChat();
    }

    /**
     * Cached stats for a name (by tab UUID when supplied, else name-keyed), kicking a fetch if absent.
     *
     * <p>VISIBLE, not TAB: a chat line is on screen now, so it takes the high origin lane instead of
     * being coalesced into the background tab batch (where its latency became its position in a
     * 16-player scrape). It still shares the short coalescing window, unlike USER.
     */
    private static BedwarsStats resolveStats(String name, UUID uuid) {
        if (uuid != null) {
            BedwarsStats s = StatsCache.getCached(uuid);
            if (s == null) StatsCache.ensureFetched(uuid, StatsCache.PRIORITY_VISIBLE);
            return s;
        }
        BedwarsStats s = StatsCache.getCachedByName(name);
        if (s == null) StatsCache.ensureFetchedByName(name, StatsCache.PRIORITY_VISIBLE);
        return s;
    }

    /**
     * Insert {@code child} so it renders before everything on the line. Hypixel's hover-card lines
     * (hub lobby) carry an empty-text root with all content in siblings, so index 0 is already the
     * front — but plainer lines (the queue, some in-game formats) put the whole text in the root
     * component itself, which always renders ahead of any sibling and would push the FKDR to the end
     * of the line. For those we first hoist the root's own text into a sibling at index 0: the moved
     * component's empty style inherits the root's, so the rendered line is unchanged. The hoist must
     * blank the root in place (Weave renders the original component reference and ignores a swapped
     * message), hence the reflective write; if that write ever fails we degrade to the old end-of-line
     * spot rather than lose the tag.
     */
    private static String prependSibling(IChatComponent parent, IChatComponent child) {
        String hoist = hoistRootText(parent);
        child.getChatStyle().setParentStyle(parent.getChatStyle());
        parent.getSiblings().add(0, child);
        return hoist;
    }

    /**
     * Move a text-bearing root's own text into sibling 0, leaving the root empty like the lobby's
     * hover-card lines so the FKDR (spliced at sibling 0 next) renders at the true front. A no-op — with
     * the FKDR harmlessly degrading to <i>after</i> the text rather than being duplicated — when the
     * text can't be cleared.
     */
    private static String hoistRootText(IChatComponent parent) {
        if (!(parent instanceof ChatComponentText)) return "non-text-root";
        String text = parent.getUnformattedTextForChat();
        if (text == null || text.isEmpty()) return "empty-root";
        if (!blankRootText((ChatComponentText) parent, text)) return "FAILED";
        ChatComponentText moved = new ChatComponentText(text);
        moved.getChatStyle().setParentStyle(parent.getChatStyle());
        parent.getSiblings().add(0, moved);
        return "hoisted";
    }

    /**
     * Set the root's own text to "" in place, then confirm it actually happened. We can't name the
     * backing field (obfuscated under Weave) and must NOT pick it as "the only String field": Lunar adds
     * extra String fields to vanilla classes (see the {@code lunar-modifies-vanilla-fields} note), and
     * blanking the wrong one leaves the text on screen — exactly why the FKDR landed after the name on
     * queue/in-game <i>root-text</i> lines while the lobby's empty-root lines were fine. Instead we blank
     * every non-static String field whose current value IS the own text (the text field found <i>by
     * value</i>, however many same-typed fields Lunar added), walking up the class hierarchy in case
     * Lunar subclassed or relocated it. We then verify through the public accessor and only report
     * success when the text is genuinely gone, so a wrong guess degrades to "FKDR after the name" rather
     * than duplicating the line.
     */
    private static boolean blankRootText(ChatComponentText root, String ownText) {
        if (ownText == null || ownText.isEmpty()) return false;
        try {
            boolean wrote = false;
            for (Class<?> k = root.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (f.getType() != String.class) continue;
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true); // instance finals are writable after setAccessible
                    if (ownText.equals(f.get(root))) {
                        f.set(root, "");
                        wrote = true;
                    }
                }
            }
            if (!wrote) return false;
            String after = root.getUnformattedTextForChat();
            return after == null || after.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- bookkeeping ------------------------------------------------------

    private void track(Holder h) {
        tracked.addLast(h);
        List<Holder> list = byName.get(h.key);
        if (list == null) {
            list = new ArrayList<Holder>();
            byName.put(h.key, list);
        }
        list.add(h);
        while (tracked.size() > MAX_TRACKED) {
            Holder old = tracked.pollFirst();
            if (old == null) break;
            unindex(old);
        }
    }

    private void unindex(Holder h) {
        List<Holder> l = byName.get(h.key);
        if (l != null) {
            l.remove(h);
            if (l.isEmpty()) byName.remove(h.key);
        }
    }

    private static boolean enabled(ClientSettings cfg) {
        if (cfg == null) return false;
        // The leading FKDR/[New] bracket is the Chat Stats sub-toggle of Hypixel Stats; the trailing
        // name-reveal / (Nicked) tags need Nick Utils.
        boolean nickTags = cfg.nickUtils && (cfg.autoDenick || cfg.nickNotify);
        return chatStatsEnabled(cfg) || nickTags;
    }

    /** Chat Stats: the Hypixel Stats sub-toggle for the leading in-chat FKDR/[New] bracket. */
    private static boolean chatStatsEnabled(ClientSettings cfg) {
        return cfg.playerStats && cfg.playerStatsChat;
    }

    /** Identities are real here: an active game, or a lobby whose skins aren't stripped. */
    private static boolean operate() {
        if (!HypixelContext.isOnHypixel() || !HypixelContext.isInBedwars()) return false;
        return HypixelContext.isInActiveBedwarsGame() || Denicks.identitiesVisible();
    }

    /**
     * Any Bedwars context, real identities or not — in the anonymized queue, typed chat still names
     * its real sender, so name-keyed tags remain safe for lines a player actually typed.
     */
    private static boolean typedChatContext() {
        return HypixelContext.isOnHypixel() && HypixelContext.isInBedwars();
    }

    private static boolean isSelf(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.thePlayer != null && name.equalsIgnoreCase(mc.thePlayer.getName());
    }

    /**
     * Rebuild the chat's drawn lines so our in-place tag edits show, WITHOUT snapping a scrolled-up
     * reader to the bottom. Vanilla {@link GuiNewChat#refreshChat()} calls {@code resetScroll()}
     * (scrollPos→0, isScrolled→false); because we call it every tick as async FKDRs land — to back-patch
     * already-shown lines — that resets the view of anyone reading back-scroll, the reported "chat keeps
     * jumping down" bug.
     *
     * <p>We can't pin down the {@code scrollPos} field by name (obfuscated under Weave) nor as the lone
     * int (Lunar's GuiNewChat carries several extra int fields), so instead we snapshot <i>every</i>
     * primitive int/boolean field, refresh, then restore only those the refresh cleared — an int reset
     * to 0, a boolean reset to false — which is exactly {@code resetScroll}'s footprint. The rebuilt
     * drawn-line list is a {@code List} field, never snapshotted, so it survives; any unrelated primitive
     * that the refresh legitimately changed is left as-is. Degrades to a plain refresh if reflection is
     * unavailable.
     */
    private static void refreshChat() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.ingameGUI == null) return;
        GuiNewChat chat = mc.ingameGUI.getChatGUI();
        if (chat == null) return;

        resolveChatScrollFields();
        int[] intBefore = null;
        boolean[] boolBefore = null;
        try {
            if (chatIntFields.length > 0) {
                intBefore = new int[chatIntFields.length];
                for (int k = 0; k < chatIntFields.length; k++) intBefore[k] = chatIntFields[k].getInt(chat);
            }
            if (chatBoolFields.length > 0) {
                boolBefore = new boolean[chatBoolFields.length];
                for (int k = 0; k < chatBoolFields.length; k++) boolBefore[k] = chatBoolFields[k].getBoolean(chat);
            }
        } catch (Throwable t) {
            intBefore = null; // reflection unavailable — accept a plain refresh
            boolBefore = null;
        }

        chat.refreshChat();

        if (intBefore == null && boolBefore == null) return;
        try {
            if (intBefore != null) {
                for (int k = 0; k < chatIntFields.length; k++) {
                    if (intBefore[k] != 0 && chatIntFields[k].getInt(chat) == 0) {
                        chatIntFields[k].setInt(chat, intBefore[k]); // undo resetScroll's scrollPos→0
                    }
                }
            }
            if (boolBefore != null) {
                for (int k = 0; k < chatBoolFields.length; k++) {
                    if (boolBefore[k] && !chatBoolFields[k].getBoolean(chat)) {
                        chatBoolFields[k].setBoolean(chat, true); // undo resetScroll's isScrolled→false
                    }
                }
            }
            // Re-clamp the restored offset in case the rebuilt line list changed length (scroll(0) is a
            // no-op at the bottom and when the offset is still in range).
            chat.scroll(0);
        } catch (Throwable ignored) { }
    }

    // ---- stored-line surgery (Lunar copy-on-add) ----------------------------

    /**
     * Make sure {@code h}'s tags reach the component the chat GUI actually <i>stores</i>, not just the
     * one we spliced at receive. Lunar routes every freshly added line through its own chat pipeline
     * (decompiled: its GuiNewChat mixin posts the message to Lunar's event bus on the add path), and
     * whenever any of its listeners marks the message changed, vanilla ends up storing a converted
     * <b>copy</b> of the component. Our holders then live in an orphaned tree: {@link Holder#apply}
     * mutates them, {@link #refreshChat()} rebuilds — from the copy — and nothing changes on screen.
     * That was the "FKDR only shows when it resolved instantly" bug: a tag present <i>before</i> the add
     * survives inside the copy; every late back-patch lands on the orphan. (Refreshes are safe: the same
     * mixin passes {@code displayOnly} rebuilds through untouched.)
     *
     * <p>So after a tag write we verify the stored line by <b>identity</b> — if any of the chat GUI's
     * line lists holds our component, in-place holder writes render (Forge always; Lunar when nothing
     * marked the line changed) and the holder is marked verified for good. Otherwise we relocate the
     * stored copy by <b>value</b> — the line whose flattened text equals what this line read at add time
     * ({@link Holder#baselineText}) — and swap our original component back in, rebuilding the
     * {@link ChatLine} with its own update counter and id. From then on the holders are live again and
     * every future back-patch (mode switches included) renders normally.
     *
     * <p>Reflection follows the same rules as the scroll fix (see {@code lunar-modifies-vanilla-fields}):
     * fields are taken by type and contents, never by name or by being "the only one of a type", and any
     * failure degrades to the old behavior — the tag stays off that one line — rather than touching chat
     * state we don't understand. A line whose text a Lunar feature <i>visibly</i> rewrote no longer
     * equals its baseline and is deliberately left alone: we never clobber another mod's transform.
     */
    private static boolean ensureVisible(Holder h) {
        if (h.storedVerified) return true;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.ingameGUI == null) return false;
        GuiNewChat chat = mc.ingameGUI.getChatGUI();
        if (chat == null) return false;
        resolveChatLineListFields();
        try {
            // Pass 1 — identity: the stored component is ours; nothing to repair.
            for (Field f : chatLineListFields) {
                List<?> list = (List<?>) f.get(chat);
                if (list == null) continue;
                for (Object o : list) {
                    if (o instanceof ChatLine && ((ChatLine) o).getChatComponent() == h.root) {
                        h.storedVerified = true;
                        return true;
                    }
                }
            }
            // Pass 2 — value: find the stored copy by its add-time text, swap our component back in.
            // Full-message and drawn-row lists are both patched blind: the caller refreshes right after,
            // which rebuilds the drawn rows from the repaired full-message list anyway. Text equality is
            // per-sender ("Name: message"), so two players' identical messages can never cross-match.
            if (h.baselineText == null || h.baselineText.isEmpty()) return false;
            int replaced = 0;
            for (Field f : chatLineListFields) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) f.get(chat);
                if (list == null) continue;
                for (int i = 0; i < list.size(); i++) {
                    Object o = list.get(i);
                    if (!(o instanceof ChatLine)) continue;
                    ChatLine line = (ChatLine) o;
                    IChatComponent comp = line.getChatComponent();
                    if (comp == null || comp == h.root) continue;
                    if (!h.baselineText.equals(safeText(comp))) continue;
                    list.set(i, new ChatLine(line.getUpdatedCounter(), h.root, line.getChatLineID()));
                    replaced++;
                }
            }
            if (replaced > 0) {
                h.storedVerified = true;
                DiagLog.log("SURGERY sender=" + h.sender + " replaced=" + replaced
                        + " after=" + (System.currentTimeMillis() - h.receivedMs) + "ms");
                return true;
            }
            DiagLog.log("SURGERY-MISS sender=" + h.sender
                    + " after=" + (System.currentTimeMillis() - h.receivedMs) + "ms");
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** A component's full unformatted text, or null when the flatten throws (foreign component impls). */
    private static String safeText(IChatComponent c) {
        if (c == null) return null;
        try {
            return c.getUnformattedText();
        } catch (Throwable t) {
            return null;
        }
    }

    /** GuiNewChat's non-static List fields (the chat/drawn line lists among them), resolved once by type. */
    private static Field[] chatLineListFields = new Field[0];
    private static boolean chatLineListFieldsResolved;

    private static void resolveChatLineListFields() {
        if (chatLineListFieldsResolved) return;
        chatLineListFieldsResolved = true;
        List<Field> lists = new ArrayList<Field>();
        try {
            for (Field f : GuiNewChat.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!List.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                lists.add(f); // non-ChatLine lists (sent history, Lunar extras) are skipped per-element
            }
        } catch (Throwable ignored) { }
        chatLineListFields = lists.toArray(new Field[0]);
    }

    /** GuiNewChat's non-static primitive int/boolean fields (see {@link #refreshChat()}), resolved once. */
    private static Field[] chatIntFields = new Field[0];
    private static Field[] chatBoolFields = new Field[0];
    private static boolean chatScrollFieldsResolved;

    private static void resolveChatScrollFields() {
        if (chatScrollFieldsResolved) return;
        chatScrollFieldsResolved = true;
        List<Field> ints = new ArrayList<Field>();
        List<Field> bools = new ArrayList<Field>();
        try {
            for (Field f : GuiNewChat.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (t == int.class) { f.setAccessible(true); ints.add(f); }
                else if (t == boolean.class) { f.setAccessible(true); bools.add(f); }
            }
        } catch (Throwable ignored) { }
        chatIntFields = ints.toArray(new Field[0]);
        chatBoolFields = bools.toArray(new Field[0]);
    }

    private static String fmt2(double d) {
        return String.format(Locale.US, "%.2f", d);
    }

    /** One annotated chat line: the mutable head (FKDR) and tail (name tag) siblings we own. */
    private static final class Holder {
        final String key; // lower-cased sender, for grouped back-patching
        final String sender;
        /** The line names its players for real in any Bedwars context: typed chat, or a party roster. */
        final boolean typedShape;
        final long receivedMs;     // decide-window anchor
        final IChatComponent root; // the whole received line — re-attached when Lunar stored a copy
        final ChatComponentText prefix;
        final ChatComponentText suffix;
        /** Per-member tag slots on a {@code /party list} roster line; null on single-sender lines. */
        List<Slot> roster;
        boolean decided;           // verdict reached (final either way)
        boolean live;              // verdict: sender trusted, annotate + back-patch
        /** The line's full text at add time — the fingerprint a Lunar-stored copy still carries. */
        String baselineText;
        /** The chat GUI verifiably stores {@link #root} (found or repaired) — holder writes render. */
        boolean storedVerified;
        String lastPrefix = "";
        String lastSuffix = "";
        /** Last tab UUID resolved for the sender — the FKDR cache key, held through tab churn. */
        UUID tabUuid;
        boolean pinLogged;         // one PIN diag per line, not one per blipped tick

        Holder(String sender, boolean typedShape, long receivedMs, IChatComponent root,
               ChatComponentText prefix, ChatComponentText suffix) {
            this.key = sender.toLowerCase();
            this.sender = sender;
            this.typedShape = typedShape;
            this.receivedMs = receivedMs;
            this.root = root;
            this.prefix = prefix;
            this.suffix = suffix;
        }

        /** Recompute all parts and, where any differs from what's drawn, rewrite it. Returns true on change. */
        boolean apply(ClientSettings cfg) {
            if (roster != null) return applyRoster(cfg);
            String[] parts = buildParts(this, cfg);
            boolean changed = false;
            if (!parts[0].equals(lastPrefix)) {
                if (lastPrefix.isEmpty() && !parts[0].isEmpty()) {
                    DiagLog.log("TAGGED sender=" + sender + " after="
                            + (System.currentTimeMillis() - receivedMs) + "ms prefix=" + parts[0].trim());
                }
                lastPrefix = parts[0];
                writeChild(prefix, parts[0]);
                changed = true;
            }
            if (!parts[1].equals(lastSuffix)) {
                lastSuffix = parts[1];
                writeChild(suffix, parts[1]);
                changed = true;
            }
            return changed;
        }

        /**
         * Re-render every member's bracket on a roster line from the live caches. The Nick Utils tags
         * and the head slot belong to a line's single sender, so a roster line carries neither.
         */
        private boolean applyRoster(ClientSettings cfg) {
            boolean changed = false;
            for (Slot s : roster) {
                String text = fkdrBracket(s.stats(), cfg);
                if (text.equals(s.lastText)) continue;
                if (s.lastText.isEmpty()) {
                    DiagLog.log("TAGGED sender=" + s.name + " after="
                            + (System.currentTimeMillis() - receivedMs) + "ms prefix=" + text.trim());
                }
                s.lastText = text;
                writeChild(s.comp, text);
                changed = true;
            }
            return changed;
        }

        /** No bracket has rendered on this line yet — on a roster line, not on any of its members. */
        boolean untagged() {
            if (roster == null) return lastPrefix.isEmpty();
            for (Slot s : roster) if (!s.lastText.isEmpty()) return false;
            return true;
        }

        /** One party member's tag on a roster line: the spliced holder plus that member's cache key. */
        static final class Slot {
            final String name;
            final ChatComponentText comp;
            /** Last tab UUID seen for the member, held through tab churn exactly as buildParts does. */
            private UUID tabUuid;
            String lastText = "";

            Slot(String name, ChatComponentText comp) {
                this.name = name;
                this.comp = comp;
            }

            /** Cached stats for this member, kicking a fetch when nothing is cached yet. */
            BedwarsStats stats() {
                UUID uuid = ChatSender.uuidInTab(name);
                if (uuid != null) tabUuid = uuid;
                else uuid = tabUuid;
                return resolveStats(name, uuid);
            }
        }

        /** Replace a holder component's single text child with {@code text} (or empty it out). */
        private static void writeChild(ChatComponentText holder, String text) {
            holder.getSiblings().clear();
            if (!text.isEmpty()) holder.appendSibling(new ChatComponentText(text));
        }
    }
}
