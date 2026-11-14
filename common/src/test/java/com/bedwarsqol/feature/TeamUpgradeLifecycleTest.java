package com.bedwarsqol.feature;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins which game the Team Upgrades state belongs to (PLAN "TeamUpgradeLifecycle", rev 3). Every
 * sequence is driven through the same observation calls the Ornithe watcher makes: world loads,
 * the queue/active flags each tick, Mod API locations as they arrive, titles, and the gated
 * ingestion calls. The HUD must never show a previous game's upgrades (A4).
 */
public class TeamUpgradeLifecycleTest {

    private static final String SOLO = "BEDWARS_EIGHT_ONE";
    private TeamUpgradeLifecycle lc;

    @Before
    public void setUp() {
        lc = new TeamUpgradeLifecycle();
    }

    private void buy(String item, long now) {
        lc.chat(TeamUpgradeChat.classify("Steve purchased " + item, "§aSteve purchased §6" + item, null), now);
    }

    private void gameLocation(String server, long now) {
        lc.location(server, SOLO, "Lighthouse", null, now);
    }

    private void lobbyLocation(long now) {
        lc.location("lobby7", "LOBBY", null, "bedwarslobby7", now);
    }

    private void active(long now) {
        lc.tick(false, true, now);
    }

    private void idle(long now) {
        lc.tick(false, false, now);
    }

    private void queue(long now) {
        lc.tick(true, false, now);
    }

    private int tier(String key) {
        TeamUpgradeState s = lc.visible();
        return s == null ? -1 : s.tier(key);
    }

    private void assertEmptyOrHidden() {
        TeamUpgradeState s = lc.visible();
        assertTrue("expected nothing, got " + (s == null ? null : s.keys() + " " + s.traps()),
                s == null || s.isEmpty());
    }

    /** World load, pregame queue on {@code server}, game starts; Heal Pool bought at {@code t + 3000}. */
    private void playGameOn(String server, long t) {
        lc.worldLoad(t);
        gameLocation(server, t + 100);
        queue(t + 200);
        active(t + 1000);
        buy("Heal Pool", t + 3000);
        assertEquals(1, tier("Heal Pool"));
    }

    @Test
    public void aNormalGameDecidesOnItsLocationAndPrices() {
        playGameOn("mini1A", 0);
        assertFalse(lc.pending());
        assertEquals(SOLO, lc.mode());
        assertTrue(lc.ingestionAllowed());
    }

    @Test
    public void nothingIsIngestedOutsideAnActiveGame() {
        lc.worldLoad(0);
        idle(100);
        assertFalse(lc.ingestionAllowed());
        buy("Heal Pool", 200);
        assertNull(lc.visible());
        active(300);
        assertEmptyOrHidden();
        queue(400);
        buy("Heal Pool", 500);
        assertNull(lc.visible());
    }

    @Test
    public void newGameAfterLobbyAndQueueIsCleared() {
        playGameOn("mini1A", 0);
        lc.worldLoad(10000);             // back to the lobby
        lobbyLocation(10200);
        idle(10300);
        assertNull(lc.visible());        // never drawn outside a game
        lc.worldLoad(20000);
        gameLocation("mini2B", 20100);
        queue(20200);
        active(30000);
        assertEmptyOrHidden();
        buy("Iron Forge", 31000);
        assertEquals(1, tier("Forge"));
        assertEquals(0, tier("Heal Pool"));
    }

    @Test
    public void aQueueClearsAKeptCopyEvenOnTheSameServer() {
        playGameOn("mini1A", 0);
        lc.worldLoad(10000);
        lobbyLocation(10100);
        idle(10200);
        lc.worldLoad(20000);
        gameLocation("mini1A", 20100);   // same server name as the kept copy
        queue(20200);
        active(25000);
        assertEmptyOrHidden();
        active(40000);
        assertEmptyOrHidden();
    }

    @Test
    public void rejoinWithTheLocationAfterTheWorldLoadKeepsTheGame() {
        playGameOn("mini1A", 0);
        buy("Blindness Trap", 4000);
        lc.worldLoad(10000);
        lobbyLocation(10100);
        idle(10200);
        lc.worldLoad(20000);              // /rejoin
        active(20100);
        assertTrue(lc.pending());
        assertEmptyOrHidden();            // nothing from before shows while pending
        buy("Reinforced Armor I", 20200); // logged while pending, shown on the empty state
        assertEquals(1, tier("Reinforced Armor"));
        assertEquals(0, tier("Heal Pool"));
        gameLocation("mini1A", 20500);
        assertFalse(lc.pending());
        assertEquals(1, tier("Heal Pool"));
        assertEquals(1, tier("Reinforced Armor"));
        assertEquals(1, lc.visible().traps().size());
        assertEquals(SOLO, lc.mode());
    }

    @Test
    public void rejoinWithTheLocationBeforeTheWorldLoadKeepsTheGameAfterTenSeconds() {
        playGameOn("mini1A", 0);
        lc.worldLoad(10000);
        lobbyLocation(10100);
        idle(10200);
        gameLocation("mini1A", 19900);    // handled before the world load
        lc.worldLoad(20000);
        active(20100);
        buy("Reinforced Armor I", 20200);
        active(25000);
        assertTrue(lc.pending());
        assertEquals(0, tier("Heal Pool"));
        active(30099);
        assertTrue(lc.pending());
        active(30100);
        assertFalse(lc.pending());
        assertEquals(1, tier("Heal Pool"));
        assertEquals(1, tier("Reinforced Armor"));
        assertEquals(SOLO, lc.mode());
    }

    @Test
    public void anotherGamesLocationClearsTheKeptCopy() {
        playGameOn("mini1A", 0);
        lc.worldLoad(10000);
        lobbyLocation(10100);
        idle(10200);
        lc.worldLoad(20000);
        active(20100);
        buy("Reinforced Armor I", 20200);
        gameLocation("mini9Z", 20500);
        assertFalse(lc.pending());
        assertEquals(0, tier("Heal Pool"));
        assertEquals(1, tier("Reinforced Armor"));
        // and the dropped copy never comes back
        lc.worldLoad(30000);
        gameLocation("mini1A", 30100);
        active(30200);
        assertEmptyOrHidden();
    }

    @Test
    public void noLocationAtAllClearsAfterTenSecondsKeepingThisGamesEvents() {
        lc.worldLoad(0);
        active(1000);
        buy("Heal Pool", 2000);
        active(12000);
        assertFalse(lc.pending());
        assertEquals(1, tier("Heal Pool"));
        lc.worldLoad(20000);
        active(20100);
        buy("Reinforced Armor I", 21000);
        assertEquals(0, tier("Heal Pool"));
        active(30099);
        assertTrue(lc.pending());
        active(30100);
        assertFalse(lc.pending());
        assertEquals(0, tier("Heal Pool"));
        assertEquals(1, tier("Reinforced Armor"));
        assertNull(lc.mode());
    }

    @Test
    public void endTitleClearsAndGearInTheSameWorldStaysEmpty() {
        playGameOn("mini1A", 0);
        lc.title("§6§lVICTORY!", 50000);
        assertEmptyOrHidden();
        assertFalse(lc.ingestionAllowed());
        active(50050);
        lc.gear(new int[]{2, 2, 2, 2}, 0, -1, 0, 0, 51000); // Protection II after the game ended
        buy("Heal Pool", 51100);
        assertEmptyOrHidden();
        idle(52000);                       // the flag flickering after the end changes nothing
        active(52100);
        lc.gear(new int[]{2, 2, 2, 2}, 0, -1, 0, 0, 53000);
        active(70000);
        assertEmptyOrHidden();
    }

    @Test
    public void gameOverTitleAlsoEndsAndAWorldLoadReEnables() {
        playGameOn("mini1A", 0);
        lc.title("GAME OVER!", 50000);
        assertEmptyOrHidden();
        lc.worldLoad(60000);
        gameLocation("mini2B", 60100);
        active(61000);
        assertTrue(lc.ingestionAllowed());
        buy("Iron Forge", 62000);
        assertEquals(1, tier("Forge"));
        assertEquals(0, tier("Heal Pool"));
    }

    @Test
    public void endTitleDropsTheKeptCopyToo() {
        playGameOn("mini1A", 0);
        lc.worldLoad(10000);              // left: kept copy exists
        lobbyLocation(10100);
        idle(10200);
        lc.worldLoad(20000);
        gameLocation("mini1A", 20100);
        active(20200);
        assertEquals(1, tier("Heal Pool")); // rejoined
        lc.title("VICTORY!", 30000);
        lc.worldLoad(40000);
        gameLocation("mini1A", 40100);
        active(40200);
        assertEmptyOrHidden();
    }

    @Test
    public void otherTitlesDoNotEndTheGame() {
        playGameOn("mini1A", 0);
        lc.title("§cTRAP TRIGGERED!", 5000);
        lc.title("§c§lBED DESTRUCTION!", 6000);
        assertEquals(1, tier("Heal Pool"));
        assertTrue(lc.ingestionAllowed());
    }

    @Test
    public void deathAndRespawnInTheSameWorldKeepTheState() {
        playGameOn("mini1A", 0);
        buy("Blindness Trap", 4000);
        for (long t = 5000; t <= 20000; t += 50) active(t); // dead, spectating, respawned
        assertEquals(1, tier("Heal Pool"));
        assertEquals(1, lc.visible().traps().size());
    }

    @Test
    public void activeFlagFlickerWithoutAWorldLoadKeepsTheState() {
        playGameOn("mini1A", 0);
        idle(5000);
        assertNull(lc.visible());
        assertFalse(lc.ingestionAllowed());
        buy("Iron Forge", 5100);          // not ingested while the flag is off
        active(5200);
        assertEquals(1, tier("Heal Pool"));
        assertEquals(0, tier("Forge"));
        assertTrue(lc.ingestionAllowed());
    }

    @Test
    public void flagOffBeforeTheWorldLoadStillKeepsTheCopyForARejoin() {
        playGameOn("mini1A", 0);
        idle(9000);                       // sidebar cleared before the transfer
        lc.worldLoad(10000);
        lobbyLocation(10100);
        lc.worldLoad(20000);
        gameLocation("mini1A", 20100);
        active(20200);
        assertEquals(1, tier("Heal Pool"));
    }

    @Test
    public void aLobbyLocationNeverDecidesAGame() {
        playGameOn("mini1A", 0);
        lc.worldLoad(10000);
        active(10100);
        lc.location("mini1A", SOLO, "Lighthouse", "bedwarslobby1", 10200); // has a lobbyName
        lobbyLocation(10300);
        assertTrue(lc.pending());
        assertEquals(0, tier("Heal Pool"));
    }

    @Test
    public void aTrapFiredWhilePendingIsReplayedOntoTheKeptCopy() {
        playGameOn("mini1A", 0);
        buy("Blindness Trap", 3500);
        buy("Reveal Trap", 3600);
        lc.worldLoad(10000);
        lobbyLocation(10100);
        idle(10200);
        lc.worldLoad(20000);
        active(20100);
        lc.chat(TeamUpgradeChat.classifyTitle("TRAP TRIGGERED!"), 20200);
        gameLocation("mini1A", 20500);
        assertEquals(1, tier("Heal Pool"));
        assertEquals(java.util.Collections.singletonList("Reveal Trap"), lc.visible().traps());
    }

    @Test
    public void aSnapshotWhilePendingIsReplayedAndThenBlocksGear() {
        playGameOn("mini1A", 0);
        buy("Reveal Trap", 3500);
        lc.worldLoad(10000);
        lobbyLocation(10100);
        idle(10200);
        lc.worldLoad(20000);
        active(20100);
        lc.shop(TeamUpgradeShop.parse("Upgrades & Traps", TeamUpgradeShopTest.fullWindow()), 20300);
        assertEquals(2, tier("Reinforced Armor"));  // shown at once, on the pending state
        gameLocation("mini1A", 20500);
        assertEquals(2, tier("Reinforced Armor"));
        assertEquals(1, tier("Heal Pool"));
        assertEquals(java.util.Arrays.asList("Blindness Trap", "Miner Fatigue Trap"), lc.visible().traps());
        lc.gear(new int[]{4, 4, 4, 4}, 2, 1, 72000, 32767, 21000);
        assertEquals(2, tier("Reinforced Armor"));
        assertEquals(0, tier("Maniac Miner"));
    }

    @Test
    public void gearRaisesTiersInAStandardModeGame() {
        playGameOn("mini1A", 0);
        lc.gear(new int[]{0, 2, 0, 0}, 1, 0, 72000, 0, 5000);
        assertEquals(2, tier("Reinforced Armor"));
        assertEquals(1, tier("Cushioned Boots"));
        assertEquals(1, tier("Maniac Miner"));
        assertEquals(0, tier("Sharpened Swords"));
    }

    @Test
    public void gearNeedsAStandardModeLocation() {
        lc.worldLoad(0);
        active(1000);
        active(12000);                     // decided with no location: no mode
        lc.gear(new int[]{3, 3, 3, 3}, 0, -1, 0, 0, 13000);
        assertEquals(0, tier("Reinforced Armor"));

        lc.worldLoad(20000);
        lc.location("mini3C", "BEDWARS_EIGHT_TWO_RUSH", "Lighthouse", null, 20100);
        active(20200);
        lc.gear(new int[]{3, 3, 3, 3}, 0, -1, 0, 0, 21000);
        assertEquals(0, tier("Reinforced Armor"));
    }

    @Test
    public void aWorldLoadWhilePendingKeepsTheEarlierCopy() {
        playGameOn("mini1A", 0);
        lc.worldLoad(10000);
        lobbyLocation(10100);
        idle(10200);
        lc.worldLoad(20000);
        active(20100);                     // pending, nothing decides yet
        lc.worldLoad(20500);               // a second world load of the same transfer
        gameLocation("mini1A", 20600);
        active(20700);
        assertEquals(1, tier("Heal Pool"));
    }

    @Test
    public void endTitleRecognition() {
        assertTrue(TeamUpgradeLifecycle.isGameEndTitle("VICTORY!"));
        assertTrue(TeamUpgradeLifecycle.isGameEndTitle("§6§lVICTORY!"));
        assertTrue(TeamUpgradeLifecycle.isGameEndTitle("§c§lGAME OVER!"));
        assertFalse(TeamUpgradeLifecycle.isGameEndTitle("TRAP TRIGGERED!"));
        assertFalse(TeamUpgradeLifecycle.isGameEndTitle(null));
    }

    // ----- the open shop window, read every few ticks (review code-1 I1) -----

    /** The upgrades window with Heal Pool owned and {@code queue} as the trap slots. */
    private static TeamUpgradeShop.Snapshot window(String... queue) {
        List<TeamUpgradeShop.Slot> slots = new ArrayList<TeamUpgradeShop.Slot>();
        slots.add(new TeamUpgradeShop.Slot("§aHeal Pool", Arrays.asList("§7Cost: §b1 Diamond", "§aUNLOCKED"), 1));
        for (int i = 0; i < queue.length; i++) {
            slots.add(new TeamUpgradeShop.Slot("§aTrap #" + (i + 1) + ": " + queue[i], Collections.<String>emptyList(), 1));
        }
        return TeamUpgradeShop.parse("Upgrades & Traps", slots);
    }

    private List<String> traps() {
        TeamUpgradeState s = lc.visible();
        return s == null ? null : s.traps();
    }

    private void fired(String subtitle, long now) {
        lc.chat(TeamUpgradeChat.classifyTitle(subtitle), now);
    }

    @Test
    public void rereadingAnUnchangedWindowDoesNotBringBackAFiredTrap() {
        playGameOn("mini1A", 0);
        TeamUpgradeShop.Snapshot open = window("Blindness Trap", "Reveal Trap");
        lc.shopWindow(7, open, 5000);
        assertEquals(Arrays.asList("Blindness Trap", "Reveal Trap"), traps());
        fired("Your Blindness Trap has been set off!", 6000);
        assertEquals(Arrays.asList("Reveal Trap"), traps());
        lc.shopWindow(7, open, 6250); // the same window, read again five ticks later
        fired("Your Blindness Trap has been set off!", 6300); // a duplicate, deduped
        assertEquals(Arrays.asList("Reveal Trap"), traps());
        lc.shopClosed();
        assertEquals(Arrays.asList("Reveal Trap"), traps());
    }

    @Test
    public void rereadingAnUnchangedWindowDoesNotUndoAPurchase() {
        playGameOn("mini1A", 0);
        TeamUpgradeShop.Snapshot open = window();
        lc.shopWindow(7, open, 5000);
        buy("Reinforced Armor II", 7000);
        lc.shopWindow(7, open, 7250);
        assertEquals(2, tier("Reinforced Armor"));
    }

    @Test
    public void aNewWindowOrChangedContentsAreApplied() {
        playGameOn("mini1A", 0);
        lc.shopWindow(7, window("Blindness Trap"), 5000);
        buy("Reinforced Armor II", 6000);
        // Hypixel reopens the window after a purchase: a new window, the server's word
        lc.shopWindow(8, window("Blindness Trap"), 6200);
        assertEquals(0, tier("Reinforced Armor"));
        // the same window updated in place
        lc.shopWindow(8, window("Blindness Trap", "Reveal Trap"), 6400);
        assertEquals(Arrays.asList("Blindness Trap", "Reveal Trap"), traps());
    }

    @Test
    public void closingOrAWorldLoadMakesTheNextLookNews() {
        playGameOn("mini1A", 0);
        TeamUpgradeShop.Snapshot open = window("Blindness Trap");
        lc.shopWindow(7, open, 5000);
        fired("Your Blindness Trap has been set off!", 6000);
        lc.shopClosed();
        lc.shopWindow(7, open, 7000); // reopened, even with the same id
        assertEquals(Arrays.asList("Blindness Trap"), traps());

        fired("Your Blindness Trap has been set off!", 20000);
        assertEquals(Collections.<String>emptyList(), traps());
        lc.worldLoad(21000);
        active(21100);
        lc.shopWindow(7, open, 21200);
        assertEquals(Arrays.asList("Blindness Trap"), traps());
    }

    @Test
    public void aWindowOutsideAGameIsNotRememberedAsApplied() {
        TeamUpgradeShop.Snapshot open = window("Blindness Trap");
        lc.shopWindow(7, open, 100); // no game yet: ignored
        lc.worldLoad(200);
        active(300);
        lc.shopWindow(7, open, 400);
        assertEquals(Arrays.asList("Blindness Trap"), traps());
    }
}
