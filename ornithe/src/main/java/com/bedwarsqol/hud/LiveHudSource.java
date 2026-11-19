package com.bedwarsqol.hud;

import com.bedwarsqol.bedwars.GeneratorTracker;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.HeightLimitCore;
import com.bedwarsqol.feature.HeightLimitWatch;
import com.bedwarsqol.feature.KeyFade;
import com.bedwarsqol.feature.SessionStats;
import com.bedwarsqol.feature.SessionStatsWatch;
import com.bedwarsqol.feature.TeamUpgradeCatalog;
import com.bedwarsqol.feature.TeamUpgradeLifecycle;
import com.bedwarsqol.feature.TeamUpgradeRows;
import com.bedwarsqol.feature.TeamUpgradesWatch;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** The live game as the HUD's {@link HudSource}: the reads the painters made before the seam, moved here unchanged. */
public final class LiveHudSource implements HudSource {

    public static final LiveHudSource INSTANCE = new LiveHudSource();

    private LiveHudSource() {
    }

    @Override
    public boolean shows(String id) {
        return true;
    }

    @Override
    public boolean inGame() {
        return HypixelContext.isInActiveBedwarsGame();
    }

    @Override
    public boolean heightLimitVisible(ClientSettings cfg, boolean preview) {
        return HeightLimitWatch.visible(cfg, preview);
    }

    @Override
    public boolean sessionVisible(ClientSettings cfg, boolean preview) {
        return SessionStatsWatch.visible(cfg, preview);
    }

    @Override
    public List<BedwarsHudRenderer.PotionEntry> potionEntries(Minecraft mc) {
        Collection<PotionEffect> active = mc.thePlayer.getActivePotionEffects();
        if (active.isEmpty()) return Collections.emptyList();

        List<BedwarsHudRenderer.PotionEntry> entries = new ArrayList<>(active.size());
        for (PotionEffect eff : active) {
            Potion potion = potionFor(eff);
            if (potion == null) continue;
            entries.add(new BedwarsHudRenderer.PotionEntry(potion, eff.getAmplifier(), eff.getDuration(),
                    eff.getIsPotionDurationMax()));
        }
        return entries;
    }

    private static Potion potionFor(PotionEffect eff) {
        int id = eff.getPotionID();
        if (id < 0 || id >= Potion.potionTypes.length) return null;
        return Potion.potionTypes[id];
    }

    @Override
    public ItemStack currentLeggings(Minecraft mc) {
        return mc.thePlayer.inventory.armorInventory[1];
    }

    @Override
    public ItemStack[] miniInventorySlots(Minecraft mc) {
        ItemStack[] out = new ItemStack[BedwarsHudRenderer.MINI_INVENTORY_SLOTS];
        ItemStack[] main = mc.thePlayer.inventory.mainInventory;
        for (int i = 0; i < out.length; i++) {
            out[i] = main[9 + i]; // skip the 9 hotbar slots
        }
        return out;
    }

    @Override
    public int timerValue(boolean diamond) {
        return diamond ? GeneratorTracker.diamondSeconds() : GeneratorTracker.emeraldSeconds();
    }

    @Override
    public List<String> heightLimitRows(Minecraft mc) {
        List<String> out = new ArrayList<>(3);
        HeightLimitCore core = HeightLimitWatch.core();
        String map = core.map();
        int limit = core.limit();
        int distance = mc.thePlayer == null ? -1 : core.distance(mc.thePlayer.posY);
        out.add("Map: " + (map == null ? "?" : map));
        out.add("Height Limit: " + (limit < 0 ? "?" : Integer.toString(limit)));
        out.add("Distance: " + (distance < 0 ? "?" : Integer.toString(distance)));
        return out;
    }

    @Override
    public List<TeamUpgradeRows.Row> teamUpgradesRows(TeamUpgradeRows.Layout layout) {
        TeamUpgradeLifecycle lifecycle = TeamUpgradesWatch.lifecycle();
        return TeamUpgradeRows.rows(lifecycle.visible(), layout,
                TeamUpgradeCatalog.bracket(lifecycle.mode()), System.currentTimeMillis());
    }

    @Override
    public List<BedwarsHudRenderer.Line> sessionLines() {
        SessionStats s = SessionStatsWatch.core();
        return BedwarsHudRenderer.sessionLines(s.gameFinals(), s.gameBeds(), s.gameKills(),
                s.finalKills() + " / FKDR: " + SessionStats.formatRatio(s.fkdr()),
                s.beds() + " / BBLR: " + SessionStats.formatRatio(s.bblr()),
                s.kills() + " / KDR: " + SessionStats.formatRatio(s.kdr()),
                s.wins() + " / WLR: " + SessionStats.formatRatio(s.wlr()),
                s.winstreak(), s.games(), s.elapsed(System.currentTimeMillis()));
    }

    @Override
    public List<TextModules.Seg> textRuns(Minecraft mc, String id) {
        if (BedwarsHudRenderer.FPS_HUD.equals(id)) return TextModules.fps(Minecraft.getDebugFPS());
        if (BedwarsHudRenderer.CPS_HUD.equals(id)) {
            long now = Minecraft.getSystemTime();
            return TextModules.cps(BedwarsHudRenderer.LEFT_CLICKS.cps(now), BedwarsHudRenderer.RIGHT_CLICKS.cps(now));
        }
        return TextModules.ping(pingValue(mc));
    }

    /** The player's own latency as the tab list shows it (only read: no packet is sent), or "--". */
    private static String pingValue(Minecraft mc) {
        NetHandlerPlayClient net = mc.getNetHandler();
        NetworkPlayerInfo info = net == null || mc.thePlayer == null ? null : net.getPlayerInfo(mc.thePlayer.getUniqueID());
        return TextModules.pingText(mc.isSingleplayer(), info != null, info == null ? 0 : info.getResponseTime());
    }

    @Override
    public boolean[] keyStates(Minecraft mc) {
        boolean inGame = mc.currentScreen == null;
        return new boolean[]{
                isDown(mc.gameSettings.keyBindForward),
                isDown(mc.gameSettings.keyBindLeft),
                isDown(mc.gameSettings.keyBindBack),
                isDown(mc.gameSettings.keyBindRight),
                isDown(mc.gameSettings.keyBindJump),
                inGame && Mouse.isButtonDown(0),
                inGame && Mouse.isButtonDown(1)};
    }

    private static boolean isDown(KeyBinding kb) {
        return kb != null && kb.isKeyDown();
    }

    @Override
    public KeyFade keyFade(int cap) {
        return BedwarsHudRenderer.KS_FADES[cap];
    }

    @Override
    public long now() {
        return Minecraft.getSystemTime();
    }
}
