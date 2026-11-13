package cobdev.bridge;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/**
 * The named smoke probes (PLAN Solution 5): a fixed set of reads and writes instead of open
 * reflection on Minecraft. Every probe refuses unless the game is in singleplayer with the
 * integrated server running ({@link Policy#singleplayerWorld}). Minecraft names are MCP and
 * compile-checked; the one private method ({@code EntityRenderer.getFOVModifier}) is located by its
 * unique signature, so it resolves in dev and prod alike.
 */
final class Probes {

    static final String CLUTCH = "com.bedwarsqol.feature.ClutchPractice";
    static final String CLUTCH_FIREBALL = "com.bedwarsqol.feature.ClutchFireball";
    static final String CLUTCH_RULES = "com.bedwarsqol.feature.ClutchRules";

    private Probes() {
    }

    static Object run(String name, List<String> args) throws Exception {
        Refused.check(Policy.probeName(name));
        Bridge.onMain(() -> {
            Bridge.requireSingleplayerWorld();
            return null;
        });
        switch (name) {
            case "fov":
                return Bridge.onMain(Probes::fov);
            case "hurt":
                return Bridge.onMain(Probes::hurt);
            case "player":
                return player();
            case "block":
                return block(args);
            case "clutch-blast":
                return clutchBlast(args);
            case "use-item":
                return useItem(args);
            default:
                throw new Refused("unknown probe " + name);
        }
    }

    /** Read-only {@code EntityRenderer.getFOVModifier(partialTicks, true)}: what the frame uses, mixins included. */
    private static Map<String, Object> fov() throws Exception {
        Minecraft mc = Bridge.mc();
        Method m = unique(EntityRenderer.class, float.class, float.class, boolean.class);
        Map<String, Object> out = Bridge.ok();
        out.put("fov", call(m, mc.entityRenderer, 1.0f, true));
        out.put("fovSetting", mc.gameSettings.fovSetting);
        return out;
    }

    /** Starts the vanilla hurt-camera window: hurtTime = maxHurtTime = 10, damage from straight ahead. */
    private static Map<String, Object> hurt() {
        EntityPlayerSP p = Bridge.mc().thePlayer;
        p.maxHurtTime = 10;
        p.hurtTime = 10;
        p.attackedAtYaw = 0f;
        Map<String, Object> out = Bridge.ok();
        out.put("hurtTime", p.hurtTime);
        return out;
    }

    /** Read-only: the client player, and the integrated server's copy (authoritative for health). */
    private static Map<String, Object> player() throws Exception {
        Map<String, Object> out = Bridge.onMain(() -> {
            Map<String, Object> o = Bridge.ok();
            o.put("client", describe(Bridge.mc().thePlayer));
            return o;
        });
        out.put("server", onServer(() -> describe(serverPlayer())));
        return out;
    }

    /** {@code block dx dy dz [id]}: read, or place {@code id} (e.g. minecraft:wool), relative to the player's feet. */
    private static Map<String, Object> block(List<String> args) throws Exception {
        if (args.size() != 3 && args.size() != 4) throw new Refused("probe block DX DY DZ [BLOCK_ID]");
        int dx = Integer.parseInt(args.get(0));
        int dy = Integer.parseInt(args.get(1));
        int dz = Integer.parseInt(args.get(2));
        Refused.check(Policy.near(dx, dy, dz));
        String id = args.size() == 4 ? args.get(3) : null;
        return onServer(() -> {
            EntityPlayerMP p = serverPlayer();
            World w = p.worldObj;
            BlockPos pos = new BlockPos(MathHelper.floor_double(p.posX) + dx, MathHelper.floor_double(p.posY) + dy,
                    MathHelper.floor_double(p.posZ) + dz);
            Map<String, Object> out = Bridge.ok();
            out.put("pos", Arrays.asList(pos.getX(), pos.getY(), pos.getZ()));
            out.put("before", blockId(w, pos));
            if (id != null) {
                Block b = Block.getBlockFromName(id);
                if (b == null) throw new Refused("unknown block " + id);
                w.setBlockState(pos, b.getDefaultState());
            }
            out.put("block", blockId(w, pos));
            return out;
        });
    }

    /**
     * {@code clutch-blast dx dy dz}: Clutch Practice's own blast, run on the integrated server thread at
     * the player's position + offset. Calls Cobblify's package-private static
     * {@code ClutchPractice.explode(ClutchFireball)} with a {@code ClutchFireball(World, EntityLivingBase)}
     * that is never spawned (ornithe/src/main/java/com/bedwarsqol/feature).
     */
    private static Map<String, Object> clutchBlast(List<String> args) throws Exception {
        if (args.size() != 3) throw new Refused("probe clutch-blast DX DY DZ");
        double dx = Double.parseDouble(args.get(0));
        double dy = Double.parseDouble(args.get(1));
        double dz = Double.parseDouble(args.get(2));
        Refused.check(Policy.near((int) dx, (int) dy, (int) dz));
        Class<?> practice = cobblifyClass(CLUTCH, "clutch-blast");
        Class<?> fireballType = cobblifyClass(CLUTCH_FIREBALL, "clutch-blast");
        requireClutchReady(practice);
        Method explode = null;
        for (Method m : practice.getDeclaredMethods()) {
            if (m.getName().equals("explode") && Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isAssignableFrom(fireballType)) {
                explode = m;
            }
        }
        if (explode == null) {
            throw new Refused("clutch-blast expects static " + CLUTCH + ".explode(ClutchFireball); this build has none");
        }
        Method blast = explode;
        blast.setAccessible(true);
        return onServer(() -> {
            EntityPlayerMP p = serverPlayer();
            WorldServer w = (WorldServer) p.worldObj;
            Entity fireball = newFireball(fireballType, w, p);
            double x = p.posX + dx;
            double y = p.posY + dy;
            double z = p.posZ + dz;
            fireball.setPosition(x, y, z);
            Map<String, Object> out = Bridge.ok();
            out.put("blastAt", Arrays.asList(x, y, z));
            double ex = p.posX - x;
            double ey = p.posY - y;
            double ez = p.posZ - z;
            out.put("distance", Math.sqrt(ex * ex + ey * ey + ez * ez));
            expected(out, ex, ey, ez);
            out.put("before", describe(p));
            call(blast, null, fireball);
            out.put("after", describe(p));
            return out;
        });
    }

    /**
     * {@code use-item [item_id]}: the server-side right-click-air path with the held item (optionally set
     * first). On Ornithe, Cobblify's ItemInWorldManagerMixin hooks {@code tryUseItem} and hands a fire
     * charge to {@code ClutchPractice.onInteract}.
     */
    private static Map<String, Object> useItem(List<String> args) throws Exception {
        String id = args.isEmpty() ? null : args.get(0);
        Boolean clutchReady = null;
        try {
            Class<?> practice = Class.forName(CLUTCH, true, Probes.class.getClassLoader());
            clutchReady = practice.getMethod("get").invoke(null) != null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // no Clutch Practice in this build: the probe still reports the vanilla use
        }
        Boolean ready = clutchReady;
        return onServer(() -> {
            EntityPlayerMP p = serverPlayer();
            if (id != null) {
                Item item = Item.getByNameOrId(id);
                if (item == null) throw new Refused("unknown item " + id);
                p.inventory.setInventorySlotContents(p.inventory.currentItem, new ItemStack(item));
            }
            ItemStack held = p.getHeldItem();
            if (held == null) throw new Refused("the player holds nothing; pass an item id");
            Set<Integer> before = new HashSet<>();
            for (Entity e : p.worldObj.loadedEntityList) before.add(e.getEntityId());
            boolean used = p.theItemInWorldManager.tryUseItem(p, p.worldObj, held);
            List<Object> spawned = new ArrayList<>();
            for (Entity e : p.worldObj.loadedEntityList) {
                if (!before.contains(e.getEntityId())) spawned.add(e.getClass().getName());
            }
            Map<String, Object> out = Bridge.ok();
            out.put("item", Item.itemRegistry.getNameForObject(held.getItem()) + "");
            out.put("used", used);
            out.put("spawned", spawned);
            out.put("clutchReady", ready);
            return out;
        });
    }

    // ------------------------------------------------------------------------------------------

    private static Entity newFireball(Class<?> type, WorldServer w, EntityPlayerMP shooter) throws Exception {
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            Class<?>[] ps = c.getParameterTypes();
            if (ps.length == 2 && ps[0].isAssignableFrom(WorldServer.class) && ps[1].isAssignableFrom(EntityPlayerMP.class)) {
                c.setAccessible(true);
                return (Entity) c.newInstance(w, shooter);
            }
        }
        throw new Refused("clutch-blast expects a " + type.getName() + "(World, EntityLivingBase) constructor");
    }

    /** ClutchRules (common/) gives the expected hit, if this build has it. */
    private static void expected(Map<String, Object> out, double dx, double dy, double dz) {
        try {
            Class<?> rules = Class.forName(CLUTCH_RULES, true, Probes.class.getClassLoader());
            Object damage = rules.getMethod("damage", double.class).invoke(null, Math.sqrt(dx * dx + dy * dy + dz * dz));
            Object push = rules.getMethod("knockback", double.class, double.class, double.class).invoke(null, dx, dy, dz);
            out.put("expectedDamage", damage);
            out.put("expectedKnockback", Mods.plain(push));
        } catch (ReflectiveOperationException | LinkageError e) {
            out.put("expectedDamage", null);
            out.put("expectedNote", CLUTCH_RULES + " unavailable: " + e);
        }
    }

    /** {@code ClutchPractice.get()} stays null until Cobblify's READY hook enables it. */
    private static void requireClutchReady(Class<?> practice) throws Exception {
        Method get;
        try {
            get = practice.getMethod("get");
        } catch (NoSuchMethodException e) {
            return; // older shape without the instance accessor
        }
        if (get.invoke(null) == null) {
            throw new Refused("Clutch Practice is not enabled yet (ClutchPractice.get() is null until READY)");
        }
    }

    private static Class<?> cobblifyClass(String name, String probe) {
        try {
            return Class.forName(name, true, Probes.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            throw new Refused(probe + " needs " + name + ", which this build does not have yet");
        }
    }

    private static Map<String, Object> describe(net.minecraft.entity.EntityLivingBase e) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("health", e.getHealth());
        o.put("maxHealth", e.getMaxHealth());
        o.put("pos", Arrays.asList(e.posX, e.posY, e.posZ));
        o.put("motion", Arrays.asList(e.motionX, e.motionY, e.motionZ));
        o.put("hurtResistantTime", e.hurtResistantTime);
        o.put("hurtTime", e.hurtTime);
        o.put("onGround", e.onGround);
        return o;
    }

    private static String blockId(World w, BlockPos pos) {
        Object id = Block.blockRegistry.getNameForObject(w.getBlockState(pos).getBlock());
        return id == null ? null : id.toString();
    }

    private static EntityPlayerMP serverPlayer() {
        Minecraft mc = Minecraft.getMinecraft();
        IntegratedServer server = mc.getIntegratedServer();
        if (server == null || mc.thePlayer == null) throw new Refused("no integrated server is running");
        EntityPlayerMP p = server.getConfigurationManager().getPlayerByUUID(mc.thePlayer.getUniqueID());
        if (p == null) throw new Refused("the integrated server has no player for this client yet");
        return p;
    }

    /** Runs on the integrated server thread (not the client's), so the client keeps rendering meanwhile. */
    private static <T> T onServer(Callable<T> work) throws Exception {
        IntegratedServer server = Minecraft.getMinecraft().getIntegratedServer();
        if (server == null) throw new Refused("no integrated server is running");
        FutureTask<T> task = new FutureTask<>(work);
        server.addScheduledTask(task);
        return Bridge.await(task, 10_000, "the integrated server");
    }

    /** The one declared method on {@code owner} with this exact signature; fails if there are none or several. */
    static Method unique(Class<?> owner, Class<?> returns, Class<?>... params) {
        Method found = null;
        for (Method m : owner.getDeclaredMethods()) {
            if (m.getReturnType() == returns && Arrays.equals(m.getParameterTypes(), params)) {
                if (found != null) {
                    throw new IllegalStateException("more than one " + owner.getSimpleName() + " method matches "
                            + Arrays.toString(params) + "; the bridge needs a narrower lookup");
                }
                found = m;
            }
        }
        if (found == null) {
            throw new IllegalStateException("no " + owner.getSimpleName() + " method " + Arrays.toString(params)
                    + " -> " + returns + "; Minecraft changed?");
        }
        found.setAccessible(true);
        return found;
    }

    private static Object call(Method m, Object target, Object... args) throws Exception {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            if (c instanceof Error) throw (Error) c;
            throw e;
        }
    }
}
