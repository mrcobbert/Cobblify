package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a small player head immediately left of the sender's name in chat, pixel-correct, default off.
 *
 * <p><b>Why this is a support class, not its own chat pipeline.</b> Heads used to be a second
 * {@code GuiNewChat.splitText} redirect with its own grammar and identity map — a weaker copy of what
 * {@link ChatNameTags} (the inline FKDR) already does. That lost the FKDR feature's context-settling and
 * Lunar copy-on-add repair, so heads missed {@code >>> … joined the lobby! <<<} lines and didn't appear
 * until a lobby swap. Heads now ride the FKDR pipeline: {@link ChatNameTags} splices an empty
 * <i>head holder</i> sibling immediately before the sender's name on every trusted line, and fills it
 * with one invisible per-skin {@link #isSentinel(char) sentinel} codepoint once the tab skin resolves —
 * exactly when, and on exactly the lines, the FKDR appears.
 *
 * <p>This class owns three things: the codepoint&harr;skin registry ({@link #sentinelFor}); the
 * component-tree splice that places the holder before the name ({@link #spliceHeadHolder}); and the draw
 * bridge ({@link #drawRowHeads}), which draws at the sentinel's left edge; the {@link #SLOT_GAP} spaces
 * reserve the room. The draw lives in {@code FontRendererMixin} — the shared low-level path, so heads
 * render under Lunar's own chat renderer too.
 */
public final class ChatPlayerHeads {

    private ChatPlayerHeads() {}

    /** Drawn head face size (px), square. The reserved slot is made of real spaces (see {@link #SLOT_GAP}). */
    public static final int FACE = 8;
    /**
     * The reserved head slot, as <b>real spaces</b> written right after the sentinel. Every font measures
     * these natively on both Forge and Lunar, so the name is pushed right regardless of whether our
     * {@code FontRenderer} char-width hook applies — the sentinel is only a zero-width position+skin marker.
     * ~3 spaces ≈ the 8px face + a small gap before the name.
     */
    public static final String SLOT_GAP = "   ";

    /**
     * A private-use sub-range reserved for head sentinels: one codepoint per distinct skin currently on
     * screen. 256 slots comfortably covers a busy hub (heads only exist on trusted senders in your tab),
     * and a full registry recycles its least-recently-used slot. Kept narrow so a stray server-sent PUA
     * glyph elsewhere in the range is only ever treated as a head when we actually allocated it (see
     * {@link #isSentinel}).
     */
    static final char SENTINEL_MIN = '\uE000';
    static final char SENTINEL_MAX = '\uE0FF';
    private static final int SLOTS = (SENTINEL_MAX - SENTINEL_MIN) + 1;

    /** codepoint -> skin (index = codepoint - SENTINEL_MIN); null entry = unallocated. Client thread only. */
    private static final ResourceLocation[] slots = new ResourceLocation[SLOTS];
    /** skin -> codepoint, so the same skin reuses one slot across every line it appears on. */
    private static final Map<ResourceLocation, Character> bySkin = new HashMap<ResourceLocation, Character>();
    /** Per-slot use stamp (same index as {@link #slots}); the smallest one is the eviction victim. */
    private static final long[] used = new long[SLOTS];
    /** Monotonic stamp source: bumped on every allocation and every re-use of an existing slot. */
    private static long useTick;
    private static int next;

    // ---- codepoint registry ---------------------------------------------------------------------

    /**
     * The sentinel codepoint for a skin, allocating one on first use; 0 only for a null skin. A full
     * registry recycles the least-recently-used slot, so the oldest head on screen goes stale rather
     * than new senders silently losing theirs.
     */
    public static char sentinelFor(ResourceLocation skin) {
        if (skin == null) return 0;
        Character c = bySkin.get(skin);
        if (c != null) {
            used[c.charValue() - SENTINEL_MIN] = ++useTick;
            return c.charValue();
        }
        int idx;
        if (next < SLOTS) {
            idx = next++;
        } else {
            idx = 0; // full: evict the slot untouched for longest (linear scan, 256 entries, new skins only)
            for (int i = 1; i < SLOTS; i++) if (used[i] < used[idx]) idx = i;
            bySkin.remove(slots[idx]);
        }
        char ch = (char) (SENTINEL_MIN + idx);
        slots[idx] = skin;
        used[idx] = ++useTick;
        bySkin.put(skin, Character.valueOf(ch));
        return ch;
    }

    /** True when {@code c} is a codepoint we actually allocated to a skin (not just any PUA char). */
    public static boolean isSentinel(char c) {
        return c >= SENTINEL_MIN && c <= SENTINEL_MAX && slots[c - SENTINEL_MIN] != null;
    }

    private static ResourceLocation skinFor(char c) {
        if (c < SENTINEL_MIN || c > SENTINEL_MAX) return null;
        return slots[c - SENTINEL_MIN];
    }

    /** The tab-list skin the client already shows for {@code sender} (a nick keeps its nick skin), or null. */
    public static ResourceLocation skinForSender(String sender) {
        NetworkPlayerInfo info = playerInfoCI(sender);
        return info == null ? null : info.getLocationSkin();
    }

    /**
     * Drop every allocation. Nothing in the client calls this any more — chat lines outlive both the
     * world change and the toggle, so wiping the registry under them would dangle or alias their
     * sentinels; it survives as the reset seam for tests against this static registry.
     */
    public static void clear() {
        for (int i = 0; i < next; i++) slots[i] = null;
        bySkin.clear();
        next = 0;
        loggedDraw = false;
    }

    /**
     * Toggle flipped in the settings GUI: rebuild chat so head slots appear/disappear now. The registry
     * is left intact so a re-enable restores the same heads on the lines that already carry sentinels.
     */
    public static void onToggle() {
        loggedDraw = false;
        ChatNameTags.refreshDisplayMode();
    }

    // ---- draw bridge (called by FontRendererMixin's drawString TAIL) ----------------------------

    /**
     * Paint a head at every head sentinel in the row about to be drawn. The head sits after the
     * sentinel's measured advance (0 when the width hook applied, its native width when it didn't),
     * so head-to-name spacing is identical either way; the {@link #SLOT_GAP} spaces right after it
     * hold the name clear. Honors the row's fade alpha (the top byte of {@code color}).
     */
    public static void drawRowHeads(FontRenderer fr, String rowText, int x, int y, int color) {
        if (rowText == null || rowText.isEmpty()) return;
        if (BedwarsQol.config == null || !BedwarsQol.config.chatPlayerHeads) return;
        int a = (color >>> 24) & 0xFF;
        if (a <= 3) return; // vanilla skips near-transparent rows
        for (int i = 0; i < rowText.length(); i++) {
            char c = rowText.charAt(i);
            if (!isSentinel(c)) continue;
            ResourceLocation skin = skinFor(c);
            if (skin == null) continue;
            int offset = fr.getStringWidth(rowText.substring(0, i)) + fr.getCharWidth(c);
            drawHead(skin, x + offset, y, a);
            if (!loggedDraw) { loggedDraw = true; DiagLog.log("HEAD-DRAW fired (render hook live)"); }
        }
    }

    /** One-shot: proves the FontRenderer draw hook actually paints (esp. under Lunar's chat renderer). */
    private static boolean loggedDraw;

    private static void drawHead(ResourceLocation skin, int x, int y, int alpha) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || skin == null) return;
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.color(1f, 1f, 1f, alpha / 255f);
        mc.getTextureManager().bindTexture(skin);
        // 8x8 face then the 8x8 hat overlay (standard 64x64 skin UVs), as the vanilla tab overlay does.
        Gui.drawScaledCustomSizeModalRect(x, y, 8f, 8f, 8, 8, 8, 8, 64f, 64f);
        Gui.drawScaledCustomSizeModalRect(x, y, 40f, 8f, 8, 8, 8, 8, 64f, 64f);
        GlStateManager.color(1f, 1f, 1f, 1f); // leave blend enabled (vanilla's pre-call state)
    }

    // ---- name-slot splice (called by ChatNameTags at receive) -----------------------------------

    /**
     * Insert {@code holder} (an empty, mutable component {@link ChatNameTags} back-patches) as a sibling
     * immediately before {@code sender}'s name in {@code root}, splitting the covering leaf if the name
     * sits mid-leaf. Returns false (no head, FKDR untouched) when the name can't be located in a sibling —
     * {@link ChatNameTags} has already hoisted any root-own text into a sibling by the time it calls us, so
     * the name is normally reachable. Never throws.
     */
    public static boolean spliceHeadHolder(IChatComponent root, String sender, ChatComponentText holder) {
        try {
            if (root instanceof ChatComponentTranslation) return false; // vanilla <Name> path; not our shapes
            int target = locateName(root.getUnformattedText(), sender);
            if (target < 0) return false;
            int rootOwn = safeOwnLen(root);
            if (target < rootOwn) return false; // name still in root own text (hoist failed) — degrade
            int[] cursor = {rootOwn};
            return insertBeforeName(root, target, cursor, holder);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Recursively find the leaf covering flattened offset {@code target}; splice {@code holder} before it. */
    private static boolean insertBeforeName(IChatComponent parent, int target, int[] cursor,
                                            ChatComponentText holder) {
        List<IChatComponent> sibs = (List<IChatComponent>) parent.getSiblings();
        for (int i = 0; i < sibs.size(); i++) {
            IChatComponent child = sibs.get(i);
            if (child instanceof ChatComponentTranslation) return false; // don't splice inside translations
            int ownStart = cursor[0];
            int ownEnd = ownStart + safeOwnLen(child);
            if (target >= ownStart && target < ownEnd && child instanceof ChatComponentText) {
                insertSplitting(sibs, i, (ChatComponentText) child, target - ownStart, holder);
                return true;
            }
            cursor[0] = ownEnd;
            if (insertBeforeName(child, target, cursor, holder)) return true;
        }
        return false;
    }

    /** Replace {@code sibs[i]} (a leaf) with {@code before / holder / after}, keeping its style + children. */
    private static void insertSplitting(List<IChatComponent> sibs, int i, ChatComponentText leaf, int k,
                                        ChatComponentText holder) {
        // The common Hypixel shape gives the sender its own styled leaf. Keep that exact object so
        // Lunar's copy-on-add path cannot lose styling while converting a replacement component.
        if (k == 0) {
            sibs.add(i, holder);
            return;
        }
        String text = leaf.getUnformattedTextForChat();
        ChatStyle style = leaf.getChatStyle();
        ChatComponentText before = new ChatComponentText(text.substring(0, k));
        ChatComponentText after = new ChatComponentText(text.substring(k)); // carries the name; keep its hover
        if (style != null) {
            before.setChatStyle(style.createDeepCopy());
            after.setChatStyle(style.createDeepCopy());
        }
        for (IChatComponent s : (List<IChatComponent>) leaf.getSiblings()) after.appendSibling(s);
        sibs.set(i, before);
        sibs.add(i + 1, holder);
        sibs.add(i + 2, after);
    }

    // ---- pure name location (unit-tested) -------------------------------------------------------

    /**
     * The start index of {@code sender} in {@code flat} (the line's unformatted text), or -1. Bracket
     * spans ({@code [rank]}, the FKDR {@code [x.xx]}) are masked so a name is never matched inside one,
     * and the match must be word-bounded so {@code "Bob"} doesn't hit inside {@code "Bobby"}. The first
     * such occurrence is the sender slot for every Hypixel shape {@link ChatSender} recognises.
     */
    static int locateName(String flat, String sender) {
        if (flat == null || sender == null || sender.isEmpty()) return -1;
        String masked = blankBrackets(flat); // index-stable: [..] spans become spaces
        int from = 0;
        while (from <= masked.length() - sender.length()) {
            int idx = masked.indexOf(sender, from);
            if (idx < 0) return -1;
            if (wordBounded(masked, idx, sender.length())) return idx;
            from = idx + 1;
        }
        return -1;
    }

    private static boolean wordBounded(String s, int start, int len) {
        int end = start + len;
        boolean leftOk = start == 0 || !isNameChar(s.charAt(start - 1));
        boolean rightOk = end >= s.length() || !isNameChar(s.charAt(end));
        return leftOk && rightOk;
    }

    private static boolean isNameChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    /** Replace every {@code [..]} span (brackets included) with spaces, preserving length/indices. */
    static String blankBrackets(String s) {
        char[] c = s.toCharArray();
        boolean in = false;
        for (int i = 0; i < c.length; i++) {
            if (c[i] == '[') { in = true; c[i] = ' '; }
            else if (c[i] == ']') { c[i] = ' '; in = false; }
            else if (in) c[i] = ' ';
        }
        return new String(c);
    }

    /** Own (non-child) text length of a component, treating null/foreign as 0. */
    private static int safeOwnLen(IChatComponent c) {
        try {
            String own = c.getUnformattedTextForChat();
            return own == null ? 0 : own.length();
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---- skin lookup ----------------------------------------------------------------------------

    /** Case-insensitive tab lookup (vanilla getPlayerInfo(String) is case-sensitive). */
    public static NetworkPlayerInfo playerInfoCI(String name) {
        if (name == null) return null;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return null;
        NetHandlerPlayClient net = mc.getNetHandler();
        if (net == null) return null;
        for (NetworkPlayerInfo info : net.getPlayerInfoMap()) {
            if (info == null) continue;
            GameProfile gp = info.getGameProfile();
            if (gp == null || gp.getName() == null) continue;
            if (gp.getName().equalsIgnoreCase(name)) return info;
        }
        return null;
    }
}
