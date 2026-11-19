package cobdev.bridge;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.world.WorldServer;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;

/**
 * {@code rewind-watch-actions} (cycle 2026-11-18-rewind-parity, Phase 16): what step RW does while it records, so
 * step RWW can watch every new kind of record. In the singleplayer world, after {@code start}:
 * <ol>
 *   <li>the integrated server puts a stone block two blocks east of the player, a double chest two blocks south
 *       (54 diamonds), a diamond pickaxe in hotbar slot 3 and wool in slot 4, remembering what was there;</li>
 *   <li>the client digs the stone through vanilla's own {@code sendClickBlockToController} (so Rewind's local scope,
 *       crack, hit particles and break are the live ones), places wool on the block below it through
 *       {@code rightClickMouse}, shows the inventory screen for 10 ticks, F5 for 10 ticks, then opens the chest the
 *       same way (the server sends the window and its 90 stacks back to back) and closes it after 10 ticks.</li>
 * </ol>
 * {@code status} reports the progress (flags and counts only); {@code cleanup} puts the three blocks, the player's
 * inventory and any dropped item back, so the smoke world is as before. The private Minecraft methods are reached by
 * their MCP names, so this needs the dev client (the step runs there); a production layout refuses.
 */
final class RewindActions {

    private enum Phase { SETUP, DIG, PLACE, INVENTORY, PERSPECTIVE, CHEST, DONE, FAILED }

    private static final int WAIT_TICKS = 10;
    private static final int DIG_TIMEOUT_TICKS = 100;

    private static volatile Run run;
    private static volatile boolean hookRegistered;

    private RewindActions() {
    }

    /** One scripted run (client thread, from the tick hook). */
    private static final class Run {
        volatile Phase phase = Phase.SETUP;
        volatile String failure;
        volatile boolean dug, placed, inventoryShown, perspectiveShown, chestOpened;
        volatile int chestSlots, chestFilled;
        FutureTask<Object> setup;
        int ticks;
        BlockPos stone, chest, chest2;
        IBlockState[] before;
        ItemStack[] mainBefore, armorBefore;
    }

    static Object run(List<String> args) throws Exception {
        String what = Policy.rewindActions(args);
        if ("status".equals(what)) return status();
        Bridge.onMain(() -> {
            Bridge.requireSingleplayerWorld();
            return null;
        });
        return "cleanup".equals(what) ? cleanup() : start();
    }

    private static Map<String, Object> status() {
        Run r = run;
        Map<String, Object> out = Bridge.ok();
        out.put("phase", r == null ? "NONE" : r.phase.name());
        out.put("done", r != null && (r.phase == Phase.DONE || r.phase == Phase.FAILED));
        if (r != null) {
            out.put("failure", r.failure);
            out.put("dug", r.dug);
            out.put("placed", r.placed);
            out.put("inventoryShown", r.inventoryShown);
            out.put("perspectiveShown", r.perspectiveShown);
            out.put("chestOpened", r.chestOpened);
            out.put("chestSlots", r.chestSlots);
            out.put("chestFilled", r.chestFilled);
        }
        return out;
    }

    private static Map<String, Object> start() throws Exception {
        Run old = run;
        if (old != null && old.phase != Phase.DONE && old.phase != Phase.FAILED) {
            throw new Refused("rewind-watch-actions is already running");
        }
        if (old != null && old.before != null) {
            throw new Refused("rewind-watch-actions cleanup first");
        }
        clickMethod("sendClickBlockToController", boolean.class); // refuse up front outside the dev client
        clickMethod("rightClickMouse");
        Minecraft mc = Bridge.mc();
        IntegratedServer server = mc.getIntegratedServer();
        Run r = new Run();
        java.util.UUID id = Bridge.onMain(() -> mc.thePlayer.getUniqueID());
        r.setup = new FutureTask<>(() -> {
            EntityPlayerMP p = server.getConfigurationManager().getPlayerByUUID(id);
            if (p == null) throw new Refused("the integrated server has no player for this client yet");
            WorldServer w = p.getServerForPlayer();
            BlockPos feet = new BlockPos(p.posX, p.posY, p.posZ);
            r.stone = feet.east(2);
            r.chest = feet.south(2);
            r.chest2 = r.chest.east();
            r.before = new IBlockState[]{w.getBlockState(r.stone), w.getBlockState(r.chest), w.getBlockState(r.chest2)};
            r.mainBefore = copy(p.inventory.mainInventory);
            r.armorBefore = copy(p.inventory.armorInventory);
            w.setBlockState(r.stone, Blocks.stone.getDefaultState());
            w.setBlockState(r.chest, Blocks.chest.getDefaultState());
            w.setBlockState(r.chest2, Blocks.chest.getDefaultState());
            for (BlockPos c : new BlockPos[]{r.chest, r.chest2}) {
                TileEntity t = w.getTileEntity(c);
                if (!(t instanceof TileEntityChest)) throw new Refused("no chest tile entity");
                for (int i = 0; i < ((TileEntityChest) t).getSizeInventory(); i++) {
                    ((TileEntityChest) t).setInventorySlotContents(i, new ItemStack(Items.diamond, 1 + i % 64));
                }
            }
            p.inventory.setInventorySlotContents(2, new ItemStack(Items.diamond_pickaxe));
            p.inventory.setInventorySlotContents(3, new ItemStack(Blocks.wool, 16));
            p.inventoryContainer.detectAndSendChanges();
            return null;
        });
        run = r;
        registerHook();
        server.addScheduledTask(r.setup);
        return Bridge.ok();
    }

    /** Puts the blocks, the player's inventory and the dropped items back (the integrated server's thread). */
    private static Map<String, Object> cleanup() throws Exception {
        Run r = run;
        Map<String, Object> out = Bridge.ok();
        if (r == null || r.before == null) {
            out.put("restored", false);
            return out;
        }
        if (r.phase != Phase.DONE && r.phase != Phase.FAILED) {
            r.phase = Phase.FAILED; // stop the script first
            r.failure = "cleaned up before the end";
        }
        Minecraft mc = Bridge.mc();
        IntegratedServer server = mc.getIntegratedServer();
        java.util.UUID id = Bridge.onMain(() -> {
            if (mc.thePlayer != null && mc.currentScreen != null) mc.thePlayer.closeScreen();
            mc.gameSettings.thirdPersonView = 0;
            return mc.thePlayer.getUniqueID();
        });
        FutureTask<Object> undo = new FutureTask<>(() -> {
            EntityPlayerMP p = server.getConfigurationManager().getPlayerByUUID(id);
            if (p == null) throw new Refused("the integrated server has no player for this client");
            WorldServer w = p.getServerForPlayer();
            for (BlockPos c : new BlockPos[]{r.chest, r.chest2}) {
                TileEntity t = w.getTileEntity(c);
                if (t instanceof TileEntityChest) ((TileEntityChest) t).clear(); // breaking it would drop them
            }
            w.setBlockState(r.stone, r.before[0]);
            w.setBlockState(r.chest, r.before[1]);
            w.setBlockState(r.chest2, r.before[2]);
            AxisAlignedBB around = new AxisAlignedBB(r.stone.add(-6, -3, -6), r.stone.add(6, 4, 6));
            for (EntityItem e : w.getEntitiesWithinAABB(EntityItem.class, around)) e.setDead();
            System.arraycopy(copy(r.mainBefore), 0, p.inventory.mainInventory, 0, r.mainBefore.length);
            System.arraycopy(copy(r.armorBefore), 0, p.inventory.armorInventory, 0, r.armorBefore.length);
            p.inventory.setItemStack(null);
            p.inventoryContainer.detectAndSendChanges();
            return null;
        });
        server.addScheduledTask(undo);
        Bridge.await(undo, 10_000, "the integrated server");
        r.before = null;
        out.put("restored", true);
        return out;
    }

    private static ItemStack[] copy(ItemStack[] stacks) {
        ItemStack[] out = new ItemStack[stacks.length];
        for (int i = 0; i < stacks.length; i++) out[i] = stacks[i] == null ? null : stacks[i].copy();
        return out;
    }

    // ------------------------------------------------------------------ the script (client thread, every tick)

    private static synchronized void registerHook() {
        if (hookRegistered) return;
        try {
            net.ornithemc.osl.lifecycle.api.client.MinecraftClientEvents.TICK_END.register(mc -> tick());
        } catch (LinkageError e) {
            throw new Refused("rewind-watch-actions needs OSL's client tick event: " + e);
        }
        hookRegistered = true;
    }

    private static void tick() {
        Run r = run;
        if (r == null || r.phase == Phase.DONE || r.phase == Phase.FAILED) return;
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc.thePlayer;
        if (p == null || mc.theWorld == null) {
            fail(r, "left the world");
            return;
        }
        try {
            step(mc, p, r);
        } catch (Throwable t) {
            fail(r, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void step(Minecraft mc, EntityPlayerSP p, Run r) throws Exception {
        r.ticks++;
        switch (r.phase) {
            case SETUP:
                if (!r.setup.isDone()) {
                    if (r.ticks > 200) fail(r, "the server did not set up the blocks");
                    return;
                }
                r.setup.get(); // rethrows the server's failure
                if (r.ticks >= WAIT_TICKS && mc.theWorld.getBlockState(r.stone).getBlock() == Blocks.stone) {
                    next(r, Phase.DIG);
                }
                return;
            case DIG:
                p.inventory.currentItem = 2;
                aim(mc, p, r.stone.getX() + 0.5, r.stone.getY() + 0.5, r.stone.getZ() + 0.5);
                click(mc, "sendClickBlockToController", true);
                if (mc.theWorld.isAirBlock(r.stone)) {
                    click(mc, "sendClickBlockToController", false);
                    r.dug = true;
                    next(r, Phase.PLACE);
                } else if (r.ticks > DIG_TIMEOUT_TICKS) {
                    fail(r, "the stone did not break");
                }
                return;
            case PLACE:
                if (r.ticks == 4) {
                    p.inventory.currentItem = 3;
                    BlockPos below = r.stone.down();
                    aim(mc, p, below.getX() + 0.5, below.getY() + 1.0, below.getZ() + 0.5);
                    click(mc, "rightClickMouse");
                }
                if (r.ticks == 8) {
                    r.placed = !mc.theWorld.isAirBlock(r.stone);
                    next(r, Phase.INVENTORY);
                }
                return;
            case INVENTORY:
                if (r.ticks == 1) {
                    mc.displayGuiScreen(new GuiInventory(p));
                    r.inventoryShown = mc.currentScreen instanceof GuiInventory;
                }
                if (r.ticks == WAIT_TICKS) {
                    p.closeScreen();
                    next(r, Phase.PERSPECTIVE);
                }
                return;
            case PERSPECTIVE:
                if (r.ticks == 1) {
                    mc.gameSettings.thirdPersonView = 1;
                    r.perspectiveShown = true;
                }
                if (r.ticks == WAIT_TICKS) {
                    mc.gameSettings.thirdPersonView = 0;
                    next(r, Phase.CHEST);
                }
                return;
            case CHEST:
                if (r.ticks == 1) {
                    p.inventory.currentItem = 0;
                    aim(mc, p, r.chest.getX() + 0.5, r.chest.getY() + 0.5, r.chest.getZ() + 0.5);
                    click(mc, "rightClickMouse");
                }
                if (!r.chestOpened && mc.currentScreen instanceof GuiChest) {
                    r.chestOpened = true;
                    Container c = p.openContainer;
                    r.chestSlots = c.inventorySlots.size();
                    int own = Math.max(0, r.chestSlots - 36);
                    for (int i = 0; i < own; i++) if (c.getSlot(i).getHasStack()) r.chestFilled++;
                    r.ticks = 1; // keep it open WAIT_TICKS from here
                }
                if (r.chestOpened && r.ticks >= WAIT_TICKS) {
                    p.closeScreen();
                    next(r, Phase.DONE);
                } else if (!r.chestOpened && r.ticks > 40) {
                    fail(r, "the chest did not open");
                }
                return;
            default:
        }
    }

    private static void next(Run r, Phase phase) {
        r.phase = phase;
        r.ticks = 0;
    }

    private static void fail(Run r, String why) {
        r.failure = why;
        r.phase = Phase.FAILED;
    }

    /** Looks at (x, y, z) from the player's eye and lets vanilla pick what is under the crosshair. */
    private static void aim(Minecraft mc, EntityPlayerSP p, double x, double y, double z) {
        double dx = x - p.posX;
        double dy = y - (p.posY + p.getEyeHeight());
        double dz = z - p.posZ;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        p.rotationYaw = p.prevRotationYaw = p.rotationYawHead = yaw;
        p.rotationPitch = p.prevRotationPitch = pitch;
        mc.entityRenderer.getMouseOver(1.0F);
    }

    private static Method clickMethod(String name, Class<?>... types) {
        try {
            Method m = Minecraft.class.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new Refused("rewind-watch-actions needs the dev client (Minecraft." + name + " by its MCP name)");
        }
    }

    private static void click(Minecraft mc, String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) types[i] = args[i] instanceof Boolean ? boolean.class : args[i].getClass();
        clickMethod(name, types).invoke(mc, args);
    }
}
