package com.bedwarsqol.feature;

import com.bedwarsqol.feature.TeamUpgradeChat.Event;
import com.bedwarsqol.feature.TeamUpgradeChat.Kind;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Pins the chat and title reads for Team Upgrades (research-hypixel.md §3). Only Hypixel's own
 * broadcast may change the state: a line with a sender, a line with {@code ": "}, or a purchase whose
 * item is not gold is refused, so nothing a player types can spoof it (A5).
 */
public class TeamUpgradeChatTest {

    // --- purchases ---

    @Test
    public void teammatePurchaseWithGoldItem() {
        assertEvent(Kind.PURCHASE, "Heal Pool",
                TeamUpgradeChat.classify("Steve purchased Heal Pool", "§aSteve purchased §6Heal Pool", null));
    }

    @Test
    public void forgeFormattedTextWithResetsBetweenRuns() {
        // getFormattedText() puts §r between components (HBWHelper matched §r§6Heal Pool§r).
        assertEvent(Kind.PURCHASE, "Reinforced Armor II",
                TeamUpgradeChat.classify("Steve purchased Reinforced Armor II",
                        "§r§aSteve purchased §r§6Reinforced Armor II§r", null));
    }

    @Test
    public void otherCodesBetweenGoldAndTheItemAreAllowed() {
        assertEvent(Kind.PURCHASE, "Iron Forge",
                TeamUpgradeChat.classify("Steve purchased Iron Forge", "§aSteve purchased §6§rIron Forge", null));
    }

    @Test
    public void youPurchasedAnUpgrade() {
        assertEvent(Kind.PURCHASE, "Maniac Miner I",
                TeamUpgradeChat.classify("You purchased Maniac Miner I", "§aYou purchased §6Maniac Miner I", null));
    }

    @Test
    public void trapPurchaseIsAPurchaseOfTheTrap() {
        assertEvent(Kind.PURCHASE, "Counter-Offensive Trap",
                TeamUpgradeChat.classify("Steve purchased Counter-Offensive Trap",
                        "§aSteve purchased §6Counter-Offensive Trap", null));
    }

    @Test
    public void legacyNamesAreAcceptedAndTrapsCanonicalised() {
        assertEvent(Kind.PURCHASE, "Blindness Trap",
                TeamUpgradeChat.classify("Steve purchased It's a trap!", "§aSteve purchased §6It's a trap!", null));
        assertEvent(Kind.PURCHASE, "Reveal Trap",
                TeamUpgradeChat.classify("Steve purchased Alarm Trap", "§aSteve purchased §6Alarm Trap", null));
        assertEvent(Kind.PURCHASE, "Dragon Buff",
                TeamUpgradeChat.classify("Steve purchased Dragon Buff", "§aSteve purchased §6Dragon Buff", null));
    }

    @Test
    public void itemShopWoolIsNotATeamUpgrade() {
        assertNull(TeamUpgradeChat.classify("You purchased Wool", "§aYou purchased §6Wool", null));
    }

    @Test
    public void unknownItemIsRefused() {
        assertNull(TeamUpgradeChat.classify("Steve purchased DeadShot I", "§aSteve purchased §6DeadShot I", null));
    }

    @Test
    public void teamChatSpoofIsRefused() {
        assertNull(TeamUpgradeChat.classify("[TEAM] Steve: Steve purchased Heal Pool",
                "§a[TEAM] §7Steve§f: Steve purchased Heal Pool", "Steve"));
        // even if the sender parser missed it, ": " refuses it
        assertNull(TeamUpgradeChat.classify("[TEAM] Steve: Steve purchased Heal Pool",
                "§a[TEAM] §7Steve§f: Steve purchased §6Heal Pool", null));
    }

    @Test
    public void directMessageSpoofIsRefused() {
        assertNull(TeamUpgradeChat.classify("From [VIP] Steve: Steve purchased Heal Pool",
                "§dFrom §a[VIP] Steve§7: §7Steve purchased Heal Pool", null));
    }

    @Test
    public void aSenderAloneRefusesTheLine() {
        assertNull(TeamUpgradeChat.classify("Steve purchased Heal Pool", "§aSteve purchased §6Heal Pool", "Steve"));
    }

    @Test
    public void nonGoldItemIsRefused() {
        assertNull(TeamUpgradeChat.classify("Steve purchased Heal Pool", "§aSteve purchased §aHeal Pool", null));
        assertNull(TeamUpgradeChat.classify("Steve purchased Heal Pool", "§7Steve purchased Heal Pool", null));
        assertNull(TeamUpgradeChat.classify("Steve purchased Heal Pool", "Steve purchased Heal Pool", null));
        assertNull(TeamUpgradeChat.classify("Steve purchased Heal Pool", null, null));
    }

    @Test
    public void goldEarlierInTheLineDoesNotCount() {
        assertNull(TeamUpgradeChat.classify("Steve purchased Heal Pool", "§6Steve purchased §aHeal Pool", null));
    }

    @Test
    public void nullAndBlankLines() {
        assertNull(TeamUpgradeChat.classify(null, null, null));
        assertNull(TeamUpgradeChat.classify("", "", null));
    }

    // --- set-off ---

    @Test
    public void trapSetOffLine() {
        assertEvent(Kind.TRAP_FIRED, "Miner Fatigue Trap",
                TeamUpgradeChat.classify("Miner Fatigue Trap was set off!", "§c§lMiner Fatigue Trap was set off!", null));
        assertEvent(Kind.TRAP_FIRED, "Blindness Trap",
                TeamUpgradeChat.classify("Blindness Trap was set off!", "§c§lBlindness Trap was set off!", null));
    }

    @Test
    public void legacySetOffLineIsCanonicalised() {
        assertEvent(Kind.TRAP_FIRED, "Blindness Trap",
                TeamUpgradeChat.classify("It's a trap! was set off!", "§c§lIt's a trap! was set off!", null));
    }

    @Test
    public void unknownTrapSetOffIsIgnored() {
        assertNull(TeamUpgradeChat.classify("Bed was set off!", "§c§lBed was set off!", null));
    }

    @Test
    public void revealSetOffLine() {
        assertEvent(Kind.TRAP_FIRED, "Reveal Trap",
                TeamUpgradeChat.classify("Reveal trap set off by Steve from Blue team!",
                        "§c§lReveal trap set off by §7§lSteve §c§lfrom Blue §c§lteam!", null));
        assertEvent(Kind.TRAP_FIRED, "Reveal Trap",
                TeamUpgradeChat.classify("Alarm trap set off by Steve from Blue team!",
                        "§cAlarm trap set off by Steve from Blue team!", null));
    }

    @Test
    public void castleRevealSetOffLineNamesTheBed() {
        assertEvent(Kind.TRAP_FIRED, "Reveal Trap",
                TeamUpgradeChat.classify("Red Bed: Reveal trap set off by Steve from Blue team!",
                        "§eRed Bed§f: §c§lReveal trap set off by §7Steve §cfrom Blue §cteam!", null));
    }

    @Test
    public void typedSetOffLinesAreRefused() {
        assertNull(TeamUpgradeChat.classify("[TEAM] Steve: Blindness Trap was set off!",
                "§a[TEAM] Steve: Blindness Trap was set off!", null));
        assertNull(TeamUpgradeChat.classify("Blindness Trap was set off!", "§fBlindness Trap was set off!", "Steve"));
        assertNull(TeamUpgradeChat.classify("[MVP+] Steve: Reveal trap set off by Alex from Blue team!",
                "§b[MVP+] Steve: Reveal trap set off by Alex from Blue team!", null));
        // the Castle prefix is the only ": " a Reveal line may hold
        assertNull(TeamUpgradeChat.classify("Reveal trap set off by Alex from Blue: lol team!",
                "§cReveal trap set off by Alex from Blue: lol team!", null));
        assertNull(TeamUpgradeChat.classify("Red Bed: Reveal trap set off by Alex from Blue: lol team!",
                "§cRed Bed: Reveal trap set off by Alex from Blue: lol team!", null));
    }

    // --- removal ---

    @Test
    public void removalLine() {
        assertEvent(Kind.TRAP_REMOVED, "Counter-Offensive Trap",
                TeamUpgradeChat.classify("Removed Counter-Offensive Trap from the queue!",
                        "§cRemoved §6Counter-Offensive Trap §cfrom the queue!", null));
    }

    @Test
    public void removalOfUnknownNameOrTypedIsIgnored() {
        assertNull(TeamUpgradeChat.classify("Removed Wool from the queue!", "§cRemoved §6Wool §cfrom the queue!", null));
        assertNull(TeamUpgradeChat.classify("Removed Blindness Trap from the queue!",
                "Removed Blindness Trap from the queue!", "Steve"));
    }

    @Test
    public void generatorUpgradeIsNotATeamUpgrade() {
        assertNull(TeamUpgradeChat.classify("Diamond Generators have been upgraded to Tier II",
                "§bDiamond Generators §ehave been upgraded to Tier §cII", null));
    }

    // --- titles ---

    @Test
    public void trapTitlesFireTheHead() {
        assertEvent(Kind.TRAP_FIRED, null, TeamUpgradeChat.classifyTitle("§cTRAP TRIGGERED!"));
        assertEvent(Kind.TRAP_FIRED, null, TeamUpgradeChat.classifyTitle("§c§lALARM!!!"));
    }

    @Test
    public void trapSubtitleNamesTheTrap() {
        assertEvent(Kind.TRAP_FIRED, "Blindness Trap",
                TeamUpgradeChat.classifyTitle("Your Blindness Trap has been set off!"));
        assertEvent(Kind.TRAP_FIRED, "Miner Fatigue Trap",
                TeamUpgradeChat.classifyTitle("§fYour §fMiner Fatigue Trap §fhas been set off!"));
        assertEvent(Kind.TRAP_FIRED, "Counter-Offensive Trap",
                TeamUpgradeChat.classifyTitle("Your §eRed Bed§f Counter-Offensive Trap has been set off!"));
    }

    @Test
    public void otherTitlesAreNothing() {
        assertNull(TeamUpgradeChat.classifyTitle("VICTORY!"));
        assertNull(TeamUpgradeChat.classifyTitle("§cBED DESTRUCTION!"));
        assertNull(TeamUpgradeChat.classifyTitle(""));
        assertNull(TeamUpgradeChat.classifyTitle(null));
    }

    private static void assertEvent(Kind kind, String item, Event e) {
        if (e == null) throw new AssertionError("expected " + kind + " " + item + ", got null");
        assertEquals(kind, e.kind);
        assertEquals(item, e.item);
    }
}
