package com.bedwarsqol.mixin;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.Denicks;
import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.hud.OverlayItems;
import com.bedwarsqol.hud.TabListLayout;
import com.bedwarsqol.hud.TabOverlayScale;
import com.bedwarsqol.stats.HypixelContext;
import com.bedwarsqol.stats.TabListLookup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.scoreboard.IScoreObjectiveCriteria;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps Minecraft's default tab player list, but adds client tweaks on top of it:
 *   - The tab list item: the whole vanilla list drawn at the item's box (moved and scaled; vanilla's top
 *     centre until moved), with the Background look (Lunar's fills, or none).
 *   - Hide Header/Footer: suppresses the server-sent text above/below the list.
 *   - BedWars stats overlay, drawn inline on the vanilla list.
 *
 * <p><b>How overlap is prevented (bulletproof).</b> Vanilla's column width is
 * {@code i1 = min(columns * ((head?9:0) + maxName + score + 13), width-50) / columns}. The literal
 * {@code 13} is the per-cell space reserved at the RIGHT for the ping icon. Two things cause names to
 * collide with the ping / stats overlay:
 * <ol>
 *   <li>The {@code 13} reserve is far too small for the stats text, so they get drawn
 *       on top of the name. We {@link #bedwarsqol$widenPingReserve widen that constant} to the widest
 *       ping+stats across all players, so every column reserves exactly enough room on the right.</li>
 *   <li>The {@code width-50} clamp shrinks {@code i1} below the content width when names are long (or
 *       there are many columns), and the name is still drawn at full length — so it overruns the
 *       reserved zone. We {@link #bedwarsqol$noColumnClamp remove that clamp} so the column is always
 *       long enough for the longest name + reserve, then scale the whole list down in
 *       {@link #bedwarsqol$tabStart} to keep it on-screen.</li>
 * </ol>
 */
@Mixin(GuiPlayerTabOverlay.class)
public abstract class GuiPlayerTabOverlayMixin implements TabOverlayScale {

    /** Vanilla's hardcoded per-cell reserve (px) for the signal-bar ping icon, in the column-width formula. */
    private static final int VANILLA_PING_RESERVE = 13;
    /** Head-icon width vanilla reserves on a (encrypted/integrated) server — always the case on Hypixel. */
    private static final int HEAD_WIDTH = 9;
    /** Gap (px) vanilla puts between columns. */
    private static final int COLUMN_GAP = 5;

    @Shadow public abstract String getPlayerName(NetworkPlayerInfo info);

    @Shadow private IChatComponent header;
    @Shadow private IChatComponent footer;

    // Stash the header/footer while we blank them for a render pass, so the network-set values survive.
    private IChatComponent bedwarsqol$savedHeader;
    private IChatComponent bedwarsqol$savedFooter;
    private boolean bedwarsqol$restoreHeaderFooter;

    /** Overall tab scale: the tab list item's scale (1.0 = vanilla's full size). */
    private static float bedwarsqol$sizeScale() {
        return BedwarsQol.config == null ? OverlayItems.DEFAULT_SCALE : BedwarsQol.config.tabListHudScale;
    }

    /**
     * Scale to draw the stats overlay at, relative to the (already size-scaled) tab matrix. The whole
     * list is GL-scaled by {@link #bedwarsqol$sizeScale()}; if the overlay also used a flat 0.75 it would
     * compound (0.7 * 0.75 ≈ 0.53x at Small) and the numbers would look tiny and cramped. Dividing the
     * outer scale back out keeps the overlay's on-screen size roughly constant across Size settings,
     * capped at 1.0 so it never grows past its full-size look. The reserve math and the draw must use
     * this same value so the reserved gap and the drawn text stay in sync.
     */
    private static float bedwarsqol$statsScale() {
        return TabListLayout.statsScale(bedwarsqol$sizeScale());
    }

    /**
     * True while in an active Bedwars game, where Hypixel masks every player's ping to a flat 1ms — so
     * any ping readout (numeric value or the vanilla signal bars) is meaningless. We hide the ping
     * element entirely there and give the freed width to names/stats; in lobbies and queues ping is
     * real, so it renders as normal.
     */
    private static boolean bedwarsqol$hidePing() {
        return HypixelContext.isInActiveBedwarsGame();
    }

    /**
     * Per-render-pass memo of each player's stats overlay text ("" = none), so the reserve math and the
     * draw build it once per frame instead of three times per player — and agree on the same string even
     * if a background fetch lands mid-frame. Cleared at the start of every {@code renderPlayerlist}.
     */
    private static final Map<NetworkPlayerInfo, String> bedwarsqol$statsMemo = new HashMap<>();

    /** Memoized {@link TabListLookup#statsTextForPlayerInfo}, never null ("" = no overlay). */
    private static String bedwarsqol$statsFor(NetworkPlayerInfo info) {
        String s = bedwarsqol$statsMemo.get(info);
        if (s == null) {
            s = TabListLookup.statsTextForPlayerInfo(info);
            if (s == null) s = "";
            bedwarsqol$statsMemo.put(info, s);
        }
        return s;
    }

    /** Width (px) the right-hand zone needs for one player: ping value (or vanilla bar) + stats overlay. */
    private static int bedwarsqol$rightReserveFor(FontRenderer fr, NetworkPlayerInfo info) {
        // Vanilla's 13px bar reserve; the ping element is hidden entirely in an active game (reserves nothing).
        int reserve = bedwarsqol$hidePing() ? 0 : VANILLA_PING_RESERVE;
        String stats = bedwarsqol$statsFor(info);
        if (!stats.isEmpty()) {
            // Scaled stats width + 2px gap to the score column + 2px safety.
            reserve += (int) Math.ceil(fr.getStringWidth(stats) * bedwarsqol$statsScale()) + 4;
        }
        return reserve;
    }

    /**
     * Appends the recovered real name in gray after a denicked player's tab name — {@code Nick (Real)}.
     * Injected into the vanilla name getter so the width math (which keys off {@link #getPlayerName})
     * and the draw both use the extended name, keeping columns wide enough to avoid overlap. Gated by
     * Nick Utils + Auto Denick; a no-op for players we haven't denicked (real == null).
     */
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void bedwarsqol$appendRealName(NetworkPlayerInfo info, CallbackInfoReturnable<String> cir) {
        ClientSettings cfg = BedwarsQol.config;
        if (cfg == null || !cfg.nickUtils || !cfg.autoDenick) return;
        if (info == null || info.getGameProfile() == null) return;
        String name = info.getGameProfile().getName();
        if (name == null) return;
        String real = Denicks.realNameForNick(name);
        if (real == null) return;
        cir.setReturnValue(cir.getReturnValue() + " §7(" + real + ")");
    }

    /**
     * Widens vanilla's {@code +13} ping-icon reserve in the column-width formula to whatever the widest
     * ping value + stats overlay actually needs across every player on the list. Applied uniformly to
     * every column, so the name column shrinks by exactly the reserved amount and names can never overlap
     * the ping or the stats.
     */
    @ModifyConstant(method = "renderPlayerlist", constant = @Constant(intValue = VANILLA_PING_RESERVE))
    private int bedwarsqol$widenPingReserve(int original) {
        Minecraft mc = Minecraft.getMinecraft();
        NetHandlerPlayClient net = mc.getNetHandler();
        if (net == null) return original;
        FontRenderer fr = mc.fontRendererObj;
        int max = original;
        for (NetworkPlayerInfo info : net.getPlayerInfoMap()) {
            if (info == null) continue;
            int r = bedwarsqol$rightReserveFor(fr, info);
            if (r > max) max = r;
        }
        return max;
    }

    /**
     * Removes vanilla's {@code width-50} clamp on the column width (the 2nd {@code Math.min(int,int)} in
     * {@code renderPlayerlist}). Vanilla clamps the column to the screen, which truncates it below the
     * content width and makes long names overlap the ping/stats. We keep the full natural width — so the
     * column is always long enough for the longest name + reserve — and instead scale the whole list to
     * fit the screen in {@link #bedwarsqol$tabStart}. Returning the natural width (first arg) leaves the
     * clamp a no-op.
     */
    @Redirect(method = "renderPlayerlist",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I", ordinal = 1))
    private int bedwarsqol$noColumnClamp(int natural, int screenCap) {
        return natural;
    }

    /** Number of columns vanilla splits {@code n} players into (max 20 rows per column). */
    private static int bedwarsqol$columns(int n) {
        return TabListLayout.columns(n);
    }

    /** Vanilla's head column: drawn on an integrated or encrypted (online-mode) server, as on Hypixel. */
    private static boolean bedwarsqol$heads(Minecraft mc, NetHandlerPlayClient net) {
        return mc.isIntegratedServerRunning() || net.getNetworkManager().getIsencrypted();
    }

    /**
     * The full, unclamped on-screen width of the tab grid: {@code columns * i1 + (columns-1) * gap},
     * mirroring vanilla's own geometry but with our widened reserve. Used to decide how far to scale the
     * list down so it always fits the screen.
     */
    private int bedwarsqol$naturalTabWidth(Scoreboard scoreboard, ScoreObjective objective) {
        Minecraft mc = Minecraft.getMinecraft();
        NetHandlerPlayClient net = mc.getNetHandler();
        if (net == null) return 0;
        FontRenderer fr = mc.fontRendererObj;
        Collection<NetworkPlayerInfo> all = net.getPlayerInfoMap();
        int n = Math.min(all.size(), 80);
        if (n <= 0) return 0;

        boolean hearts = objective != null
                && objective.getRenderType() == IScoreObjectiveCriteria.EnumRenderType.HEARTS;
        int maxName = 0, reserve = VANILLA_PING_RESERVE, scoreW = 0;
        for (NetworkPlayerInfo info : all) {
            if (info == null) continue;
            maxName = Math.max(maxName, fr.getStringWidth(getPlayerName(info)));
            reserve = Math.max(reserve, bedwarsqol$rightReserveFor(fr, info));
            if (objective != null && !hearts && scoreboard != null && info.getGameProfile() != null) {
                Score sc = scoreboard.getValueFromObjective(info.getGameProfile().getName(), objective);
                if (sc != null) scoreW = Math.max(scoreW, fr.getStringWidth(" " + sc.getScorePoints()));
            }
        }
        int score = objective == null ? 0 : (hearts ? 90 : scoreW);
        int columns = bedwarsqol$columns(n);
        int i1 = (bedwarsqol$heads(mc, net) ? HEAD_WIDTH : 0) + maxName + score + reserve;
        return columns * i1 + (columns - 1) * COLUMN_GAP;
    }

    @Override
    public float[] bedwarsqol$measure(int width, Scoreboard scoreboard, ScoreObjective objective) {
        Minecraft mc = Minecraft.getMinecraft();
        NetHandlerPlayClient net = mc.getNetHandler();
        FontRenderer fr = mc.fontRendererObj;
        if (net == null || fr == null) return null;
        int n = Math.min(net.getPlayerInfoMap().size(), 80);
        int natural = bedwarsqol$naturalTabWidth(scoreboard, objective);
        boolean hide = BedwarsQol.config != null && BedwarsQol.config.tabHideHeaderFooter;
        int[] header = bedwarsqol$wrappedLines(fr, hide ? null : this.header, width);
        int[] footer = bedwarsqol$wrappedLines(fr, hide ? null : this.footer, width);
        int listWidth = Math.max(natural, Math.max(header[1], footer[1]));
        float[] rect = TabListLayout.vanillaRect(width, listWidth, header[0], TabListLayout.rows(n, bedwarsqol$columns(n)), footer[0]);
        return new float[]{rect[0], rect[1], rect[2], rect[3], natural};
    }

    /** {lines, widest line} of a header or footer, wrapped as vanilla wraps it ({@code width - 50}); {0, 0} without one. */
    private static int[] bedwarsqol$wrappedLines(FontRenderer fr, IChatComponent text, int width) {
        if (text == null) return new int[]{0, 0};
        List<String> lines = fr.listFormattedStringToWidth(text.getFormattedText(), width - 50);
        int widest = 0;
        for (String line : lines) widest = Math.max(widest, fr.getStringWidth(line));
        return new int[]{lines.size(), widest};
    }

    /** True while GuiIngameOverlayScaleMixin has applied the scale around the call (see TabOverlayScale). */
    private boolean bedwarsqol$wrapped;
    /** Stats recorded during a wrapped list, drawn after it: {x, y, scale} with the text alongside. */
    private final List<float[]> bedwarsqol$pendingStatsAt = new ArrayList<float[]>();
    private final List<String> bedwarsqol$pendingStats = new ArrayList<String>();

    @Override
    public void bedwarsqol$beginWrappedTab(int width, Scoreboard scoreboard, ScoreObjective objective) {
        bedwarsqol$pendingStatsAt.clear();
        bedwarsqol$pendingStats.clear();
        bedwarsqol$pushTabScale(width, scoreboard, objective);
        bedwarsqol$wrapped = true;
    }

    @Override
    public void bedwarsqol$drawPendingStats() {
        FontRenderer fr = Minecraft.getMinecraft().fontRendererObj;
        for (int i = 0; i < bedwarsqol$pendingStats.size(); i++) {
            float[] at = bedwarsqol$pendingStatsAt.get(i);
            bedwarsqol$drawStats(fr, bedwarsqol$pendingStats.get(i), at[0], at[1], at[2]);
        }
    }

    @Override
    public void bedwarsqol$endWrappedTab() {
        bedwarsqol$wrapped = false;
        bedwarsqol$pendingStatsAt.clear();
        bedwarsqol$pendingStats.clear();
        GlStateManager.popMatrix();
    }

    private static void bedwarsqol$drawStats(FontRenderer fr, String stats, float drawX, float drawY, float statsScale) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(drawX, drawY, 0F);
        GlStateManager.scale(statsScale, statsScale, 1F);
        fr.drawStringWithShadow(stats, 0F, 0F, 0xFFFFFFFF);
        GlStateManager.popMatrix();
    }

    @Inject(method = "renderPlayerlist", at = @At("HEAD"))
    private void bedwarsqol$tabStart(int width, Scoreboard scoreboard, ScoreObjective objective, CallbackInfo ci) {
        bedwarsqol$statsMemo.clear();
        if (BedwarsQol.config != null && BedwarsQol.config.tabHideHeaderFooter) {
            bedwarsqol$savedHeader = this.header;
            bedwarsqol$savedFooter = this.footer;
            this.header = null;
            this.footer = null;
            bedwarsqol$restoreHeaderFooter = true;
        }
        if (!bedwarsqol$wrapped) bedwarsqol$pushTabScale(width, scoreboard, objective);
    }

    /**
     * Pushes the matrix and maps the list onto the tab list item's box: {@code translate(box.x, box.y);
     * scale(s); translate(-rect.x, -rect.y)}. With the column clamp removed, a long-named / many-player list
     * can be wider than the screen, so the drawn scale shrinks to fit (never overlapping), but never grows
     * beyond the item's scale. A never-moved list stays centred at the top of the screen.
     */
    private void bedwarsqol$pushTabScale(int width, Scoreboard scoreboard, ScoreObjective objective) {
        GlStateManager.pushMatrix();
        float[] m = bedwarsqol$measure(width, scoreboard, objective);
        if (m == null) return;
        int height = new ScaledResolution(Minecraft.getMinecraft()).getScaledHeight();
        float[] box = TabOverlayScale.placement(BedwarsQol.config, m, width, height);
        float s = box[4];
        if (s == 1f && box[0] == m[0] && box[1] == m[1]) return;
        GlStateManager.translate(box[0], box[1], 0f);
        GlStateManager.scale(s, s, 1f);
        GlStateManager.translate(-m[0], -m[1], 0f);
    }

    private static boolean bedwarsqol$tabBackground() {
        return BedwarsHudRenderer.overlayBackground(BedwarsQol.config, BedwarsHudRenderer.TAB_LIST_HUD);
    }

    /** Each player's row cell ({@code 0x20FFFFFF} in vanilla). */
    @ModifyConstant(method = "renderPlayerlist", constant = @Constant(intValue = 553648127), require = 1)
    private int bedwarsqol$tabRowFill(int vanilla) {
        return TabListLayout.rowFill(bedwarsqol$tabBackground());
    }

    /** The header, list and footer fills ({@code Integer.MIN_VALUE}, three {@code ldc}s). */
    @ModifyConstant(method = "renderPlayerlist", constant = @Constant(intValue = Integer.MIN_VALUE), require = 1)
    private int bedwarsqol$tabListFill(int vanilla) {
        return TabListLayout.listFill(bedwarsqol$tabBackground());
    }

    @Inject(method = "renderPlayerlist", at = @At("RETURN"))
    private void bedwarsqol$tabEnd(int width, Scoreboard scoreboard, ScoreObjective objective, CallbackInfo ci) {
        bedwarsqol$statsMemo.clear(); // don't hold NetworkPlayerInfo refs between frames
        if (!bedwarsqol$wrapped) GlStateManager.popMatrix();
        if (bedwarsqol$restoreHeaderFooter) {
            this.header = bedwarsqol$savedHeader;
            this.footer = bedwarsqol$savedFooter;
            bedwarsqol$savedHeader = null;
            bedwarsqol$savedFooter = null;
            bedwarsqol$restoreHeaderFooter = false;
        }
    }

    /**
     * Draws the BedWars stats overlay inline on the vanilla tab list. In an active Bedwars game the
     * meaningless 1ms ping is hidden entirely (cancelling vanilla's signal bars and reclaiming the width
     * for stats); otherwise vanilla's bar icon is left intact and the overlay is drawn to its left.
     * Everything here lives inside the right-hand zone that {@link #bedwarsqol$widenPingReserve} reserved,
     * so it never reaches the name.
     */
    @Inject(method = "drawPing", at = @At("HEAD"), cancellable = true)
    private void bedwarsqol$drawPingAndStats(int slotWidth, int x, int y, NetworkPlayerInfo info, CallbackInfo ci) {
        if (info == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer fr = mc.fontRendererObj;

        boolean hidePing = bedwarsqol$hidePing();
        int cellRight = x + slotWidth;
        // Left edge of the reserved zone; the stats overlay is right-aligned inside it.
        int rightEdge;
        if (hidePing) {
            // In-game: Hypixel masks ping to 1ms, so draw nothing and cancel vanilla's bars below. The
            // stats overlay reclaims the full right margin.
            rightEdge = cellRight - 1;
        } else {
            // Lobby: vanilla draws its bar icon at cellRight - 11 (10px wide); stop left of it.
            rightEdge = cellRight - 11 - 2;
        }

        // Stats / flags overlay, scaled down and right-aligned inside the reserved zone (left of the ping).
        // The reserve in bedwarsqol$widenPingReserve accounts for the vanilla sidebar-score column, so we
        // don't subtract it here — the score (when present) sits in its own column to the left of this zone.
        String stats = bedwarsqol$statsFor(info);
        if (!stats.isEmpty()) {
            float statsScale = bedwarsqol$statsScale();
            float scaledWidth = fr.getStringWidth(stats) * statsScale;
            float drawX = rightEdge - scaledWidth;
            float drawY = y + (9F - 9F * statsScale) / 2F;

            if (bedwarsqol$wrapped) {
                // Drawn after the list (bedwarsqol$drawPendingStats): Argentum's Font Batching records the
                // list's text and draws it after the method, without this translate.
                bedwarsqol$pendingStatsAt.add(new float[] {drawX, drawY, statsScale});
                bedwarsqol$pendingStats.add(stats);
            } else {
                bedwarsqol$drawStats(fr, stats, drawX, drawY, statsScale);
            }
        }

        // Suppress vanilla's bars in-game, where we've hidden the ping entirely and taken over the slot.
        if (hidePing) ci.cancel();
    }
}
