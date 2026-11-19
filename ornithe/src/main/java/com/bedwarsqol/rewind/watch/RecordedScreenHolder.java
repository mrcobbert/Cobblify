package com.bedwarsqol.rewind.watch;

import net.minecraft.entity.IMerchant;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.inventory.IInventory;
import net.minecraft.world.IInteractionObject;

/**
 * Where the camera's {@code displayGUI*} and close calls go while a recording plays (PLAN "Recorded screens"; Phase 9
 * implements it as {@code RecordedScreens} and installs it with {@link RewindCamera#setScreenHolder}). Until then
 * {@link #NONE} is installed: nothing opens, and window packets never reach vanilla.
 *
 * <p><b>Window packets</b> ({@code PacketPolicy.Decision.WINDOW}: S2D, S2E, and S2F/S30 for every window but the
 * inventory, S2F's cursor window -1 included) are processed by vanilla's handlers only when
 * {@link #takesWindows()} is true. Contract for the holder that says yes, from {@code NetHandlerPlayClient}'s
 * bytecode:
 * <ul>
 *   <li>{@code handleOpenWindow} calls one of the {@code display*} methods below and then, in <b>every</b> branch
 *       (container, villager, horse, slotless block GUI, other), writes the window id to
 *       {@code thePlayer.openContainer}. So each {@code display*} call must leave a container other than the camera's
 *       {@code inventoryContainer} in {@code camera.openContainer} before it returns (a real one for the windows you
 *       show; a throwaway one for those you do not), or the inventory container gets the window's id and the next
 *       S30 for that window overwrites the hotbar.</li>
 *   <li>Build the container on the camera's own {@code inventory} (vanilla's {@code GuiChest} does), in every view:
 *       then an S2F or S30 the server sends through the window's id for a player slot (the shop updating your
 *       hotbar) lands in the camera's inventory even when the window is not drawn (free camera, another player's
 *       view, clean view): coordinator amendment 2.</li>
 *   <li>{@link #close} ({@code handleCloseWindow} and the camera's close calls) puts {@code inventoryContainer} back
 *       in {@code openContainer}.</li>
 *   <li>Nothing here may become {@code mc.currentScreen} or send (PLAN: drawn, never opened).</li>
 * </ul>
 */
public interface RecordedScreenHolder {

    /** No recorded screens (until Phase 9): every call does nothing and window packets are not processed. */
    RecordedScreenHolder NONE = new RecordedScreenHolder() {
    };

    /** True if window packets are handed to vanilla's handlers (the holder keeps {@code openContainer} right). */
    default boolean takesWindows() {
        return false;
    }

    /** {@code displayGUIChest}: a chest, a shop or another container window ({@code handleOpenWindow}). */
    default void displayChest(RewindCamera camera, IInventory inventory) {
    }

    /** {@code displayGui}: a slotless block window (crafting table, enchanting, anvil). */
    default void displayGui(RewindCamera camera, IInteractionObject object) {
    }

    /** {@code displayGUIHorse}. */
    default void displayHorse(RewindCamera camera, EntityHorse horse, IInventory inventory) {
    }

    /** {@code displayVillagerTradeGui}. */
    default void displayMerchant(RewindCamera camera, IMerchant merchant) {
    }

    /** {@code closeScreen} / {@code closeScreenAndDropStack} (S2E): the window closed. */
    default void close(RewindCamera camera) {
    }
}
