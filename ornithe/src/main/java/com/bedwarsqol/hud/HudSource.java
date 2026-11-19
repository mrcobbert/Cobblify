package com.bedwarsqol.hud;

import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.KeyFade;
import com.bedwarsqol.feature.TeamUpgradeRows;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

import java.util.List;

/**
 * Where the Cobblify HUD painters ({@link BedwarsHudRenderer}) get what they show, one method per data read they
 * make: the live game ({@link LiveHudSource}, every frame in play and in Edit HUD), or a recording (Rewind, through
 * {@link BedwarsHudRenderer#renderRecorded}). Sizes and draws go through the same source. Example data for Edit HUD
 * stays in the painters. Render thread only.
 */
public interface HudSource {

    /** Whether item {@code id} ({@code BedwarsHudRenderer.*_HUD}) may draw at all; its own settings still apply. */
    boolean shows(String id);

    /** An active Bed Wars game, for "In Game Only". */
    boolean inGame();

    /** Whether Map Info shows ({@code preview}: Edit HUD's preview, which ignores In Game Only). */
    boolean heightLimitVisible(ClientSettings cfg, boolean preview);

    /** Whether Session Stats shows ({@code preview} as above). */
    boolean sessionVisible(ClientSettings cfg, boolean preview);

    /** The active effects, as Potion rows. */
    List<BedwarsHudRenderer.PotionEntry> potionEntries(Minecraft mc);

    /** The worn leggings, or null. */
    ItemStack currentLeggings(Minecraft mc);

    /** The 27 storage slots of the inventory (main slots 9-35). */
    ItemStack[] miniInventorySlots(Minecraft mc);

    /** Seconds to the next diamond or emerald spawn, negative while unknown. */
    int timerValue(boolean diamond);

    /** Map Info's rows as shown. */
    List<String> heightLimitRows(Minecraft mc);

    /** Upgrades &amp; Traps rows in {@code layout}; empty outside an active game. */
    List<TeamUpgradeRows.Row> teamUpgradesRows(TeamUpgradeRows.Layout layout);

    /** Session Stats' lines. */
    List<BedwarsHudRenderer.Line> sessionLines();

    /** FPS, CPS or Ping ({@code id}): the runs before their Label and Brackets. */
    List<TextModules.Seg> textRuns(Minecraft mc, String id);

    /** Keystrokes' held state in cap order (W, A, S, D, Space, LMB, RMB). */
    boolean[] keyStates(Minecraft mc);

    /** Keystrokes cap {@code cap}'s fade. */
    KeyFade keyFade(int cap);

    /** The clock fades and blinks run on, in ms. */
    long now();
}
