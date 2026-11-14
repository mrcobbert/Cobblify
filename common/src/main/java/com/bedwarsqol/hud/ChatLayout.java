package com.bedwarsqol.hud;

/**
 * The chat as a HUD item: its rows, its box, the drawing transform and the click mapping. Mirrors vanilla
 * 1.8.9 {@code GuiNewChat} (mapped bytecode): {@code drawChat} reads {@code getLineCount()} at 18, draws
 * row {@code i} at chat-local {@code y = -9i} after {@code translate(2, 20); scale(chatScale)} with a
 * fill {@code (0, y - 9, ceil(width / scale) + 4, y)} at 295; {@code GuiIngame} calls it after
 * {@code translate(0, sh - 48)}, so the chat's bottom-left is {@code (2, sh - 28)}.
 * {@code getChatComponent} maps raw LWJGL coordinates with {@code rawX / sf - 3}, {@code rawY / sf - 27}.
 *
 * <p><b>Slot rule (contract).</b> The item stores a <i>slot</i> of {@link #closedLineCount} rows. Its
 * bottom edge is what the anchor holds: lines grow upward from that bottom for any anchor, and an open
 * chat adds rows above it. The slot is never clamped. Everything that draws, frames, walls or maps clicks
 * uses the <i>visible box</i> ({@link #visibleBox}): the rows actually shown, at the slot's bottom,
 * clamped on screen. Storing a position ({@link #storedSlot}) keeps the given box's bottom.
 */
public final class ChatLayout {

    /** Height of one chat row in chat px. */
    public static final int ROW_HEIGHT = 9;
    /** At most this many rows show while chat is closed, whatever vanilla's Unfocused Height says. */
    public static final int MAX_CLOSED_LINES = 10;

    private static final float VANILLA_LEFT = 2f;
    private static final float VANILLA_BOTTOM_GAP = 28f;
    /** {@code GuiIngame}'s {@code translate(0, sh - 48)} plus {@code drawChat}'s {@code translate(2, 20)}. */
    private static final float INGAME_Y = 48f, CHAT_X = 2f, CHAT_Y = 20f;
    /** {@code getChatComponent}'s fixed origin, from the window's bottom-left, in GUI px. */
    private static final float CLICK_BOTTOM = VANILLA_BOTTOM_GAP;

    private ChatLayout() {
    }

    /** Rows a closed chat shows: vanilla's count ({@code calculateChatboxHeight(unfocused) / 9}), at most 10. */
    public static int closedLineCount(int vanillaClosed) {
        return Math.max(0, Math.min(MAX_CLOSED_LINES, vanillaClosed));
    }

    /** Rows the item's box hugs: the closed chat's shown rows, at least one so an empty chat can still be placed. */
    public static int boxRows(int drawn, int closed) {
        return Math.max(1, Math.min(closed, drawn));
    }

    /** Rows vanilla's {@code drawChat} loop draws: from {@code scroll}, at most {@code lineCount}. */
    public static int shownRows(int drawn, int lineCount, int scroll) {
        return Math.max(0, Math.min(lineCount, drawn - scroll));
    }

    /** Width of the line fill on screen at scale 1: {@code (ceil(width / chatScale) + 4) * chatScale}. */
    public static float lineAreaWidth(int chatWidth, float chatScale) {
        float v = (float) chatWidth / chatScale;
        int ceil = (int) v;
        if (v > ceil) ceil++;
        return (ceil + 4) * chatScale;
    }

    /** Vanilla's slot: {@code closed} rows standing on the bottom-left {@code (2, sh - 28)}. */
    public static float[] vanillaSlot(int sh, int chatWidth, float chatScale, int closed) {
        float h = closed * ROW_HEIGHT * chatScale;
        return new float[]{VANILLA_LEFT, sh - VANILLA_BOTTOM_GAP - h, lineAreaWidth(chatWidth, chatScale), h};
    }

    /** The never-moved slot: vanilla's, scaled by the item's scale about its bottom-left. */
    public static float[] autoSlot(float[] vanillaSlot, float sh, float s) {
        return OverlayBox.scaledAbout(vanillaSlot, VANILLA_LEFT, sh - VANILLA_BOTTOM_GAP, s);
    }

    /** The slot {@code slotH} high whose bottom-left is the box's bottom-left. */
    public static float[] slotFromBox(float[] box, float slotH) {
        return new float[]{box[0], box[1] + box[3] - slotH, box[2], slotH};
    }

    /**
     * What to store for a visible box the player put at {@code box}: {@code {x, y, anchor}} of the slot that
     * shares its bottom. The anchor is the screen third the box (what the player sees) is in.
     */
    public static int[] storedSlot(float[] box, float slotH, float sw, float sh) {
        int anchor = HudPlacement.anchorFor(box[0], box[1], box[2], box[3], sw, sh);
        float[] slot = slotFromBox(box, slotH);
        return new int[]{HudPlacement.storedX(slot[0], anchor, slot[2], sw),
                HudPlacement.storedY(slot[1], anchor, slot[3], sh), anchor};
    }

    /**
     * As {@link #storedSlot}, unrounded: {@code {x, y, anchor}} of the same slot to a fraction of a GUI pixel, so Edit HUD
     * can move the chat by one screen pixel and a wall-safe spot is stored exactly. {@code Math.round} of x and y is
     * {@link #storedSlot}'s whole position.
     */
    public static float[] exactStoredSlot(float[] box, float slotH, float sw, float sh) {
        int anchor = HudPlacement.anchorFor(box[0], box[1], box[2], box[3], sw, sh);
        float[] slot = slotFromBox(box, slotH);
        return new float[]{HudPlacement.exactStoredX(slot[0], anchor, slot[2], sw),
                HudPlacement.exactStoredY(slot[1], anchor, slot[3], sh), anchor};
    }

    /** The slot for a stored position ({@code autoSlot} while never moved); never clamped. x and y may be exact. */
    public static float[] slot(float x, float y, int anchor, float[] autoSlot, float sw, float sh) {
        if (anchor == HudPlacement.AUTO) return autoSlot;
        float w = autoSlot[2], h = autoSlot[3];
        return new float[]{HudPlacement.absoluteX(x, anchor, w, sw), HudPlacement.absoluteY(y, anchor, h, sh), w, h};
    }

    /**
     * The visible box: {@code rows} rows of {@code rowH} at the slot's bottom, pulled on screen. Near the top
     * edge a growing chat moves its bottom down just enough to keep its top row on screen.
     */
    public static float[] visibleBox(float[] slot, int rows, float rowH, float sw, float sh) {
        float h = rows * rowH;
        float x = HudPlacement.clamp(slot[0], slot[2], 0f, sw);
        float y = HudPlacement.clamp(slot[1] + slot[3] - h, h, 0f, sh);
        return new float[]{x, y, slot[2], h};
    }

    /**
     * The translate applied at the {@code drawChat} call site before {@code scale(s)}: with GuiIngame's
     * {@code translate(0, sh - 48)} and drawChat's own {@code translate(2, 20)}, it maps vanilla's chat
     * point {@code v} to {@code B + s * (v - (2, sh - 28))}, where B is the box's bottom-left.
     */
    public static float[] callSiteTranslate(float boxLeft, float boxBottom, float s, float sh) {
        return new float[]{boxLeft - CHAT_X * s, boxBottom - (sh - INGAME_Y) - CHAT_Y * s};
    }

    /**
     * A raw LWJGL x (device px) over the moved chat, as the raw x over vanilla's chat at the same chat
     * point: vanilla's {@code getChatComponent} math then answers for the moved chat. The device pixel's
     * centre is mapped back and the vanilla device pixel holding it returned, so the result is exactly
     * {@code rawX} at vanilla's spot and scale 1.
     */
    public static int toVanillaRawX(int rawX, int sf, float boxLeft, float s) {
        float gx = (rawX + 0.5f) / sf;
        return (int) Math.floor(sf * (VANILLA_LEFT + (gx - boxLeft) / s));
    }

    /** As {@link #toVanillaRawX} for a raw LWJGL y (device px from the window's bottom). */
    public static int toVanillaRawY(int rawY, int sf, float sh, float boxBottom, float s) {
        float gy = (rawY + 0.5f) / sf;
        return (int) Math.floor(sf * (CLICK_BOTTOM + (gy - (sh - boxBottom)) / s));
    }

    /** Whether the chat draws vanilla's fill behind its lines (Background On); the scrollbar always draws. */
    public static boolean lineFill(boolean background) {
        return background;
    }
}
