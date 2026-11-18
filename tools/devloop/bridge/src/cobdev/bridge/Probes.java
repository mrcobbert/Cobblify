package cobdev.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
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
 * The named smoke probes (PLAN Solution 5, plus Team Upgrades' {@code open-shop} and {@code hud-boxes}): a fixed set of reads and writes instead of open
 * reflection on Minecraft. Every probe refuses unless the game is in singleplayer with the
 * integrated server running ({@link Policy#singleplayerWorld}). Minecraft names are MCP and
 * compile-checked; the one private method ({@code EntityRenderer.getFOVModifier}) is located by its
 * unique signature, so it resolves in dev and prod alike.
 */
final class Probes {

    static final String CLUTCH = "com.bedwarsqol.feature.ClutchPractice";
    static final String CLUTCH_FIREBALL = "com.bedwarsqol.feature.ClutchFireball";
    static final String CLUTCH_RULES = "com.bedwarsqol.feature.ClutchRules";
    static final String CONFIG_HOLDER = "com.bedwarsqol.BedwarsQol";
    static final String HUD_RENDERER = "com.bedwarsqol.hud.BedwarsHudRenderer";
    static final String HUD_MODULES = "com.bedwarsqol.hud.HudModules";
    static final String EDIT_HUD = "com.bedwarsqol.gui.EditHudGui";
    static final String SETTINGS_GUI = "com.bedwarsqol.gui.SettingsGui";
    /** HudBox fields as the probe reports them (key, Java field). */
    private static final String[][] BOX_FIELDS = {{"id", "id"}, {"x", "x"}, {"y", "y"}, {"w", "width"},
            {"h", "height"}, {"pad", "pad"}, {"shown", "shown"}};
    /** HudModuleState fields; one a build does not have yet reads as null. */
    private static final String[] STATE_FIELDS = {"x", "y", "fracX", "fracY", "anchor", "scale", "enabled", "background"};

    private Probes() {
    }

    static Object run(String name, List<String> args) throws Exception {
        Refused.check(Policy.probeName(name));
        if (Policy.REWIND_PROBES.contains(name)) {
            // Read after leaving the world: no world needed, but never on a multiplayer server.
            Bridge.onMain(() -> {
                Bridge.requireLocal();
                return null;
            });
            return "rewind-latest".equals(name) ? rewindLatest() : rewindClear();
        }
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
            case "hud-boxes":
                return Bridge.onMain(Probes::hudBoxes);
            case "sheet-node":
                return Bridge.onMain(() -> sheetNode(args));
            case "open-shop":
                return openShop(args);
            default:
                if (Policy.OVERLAY_PROBES.contains(name)) return OverlayProbes.run(name, args);
                if (Policy.MOTION_BLUR_PROBES.contains(name)) return MotionBlurProbes.run(name, args);
                throw new Refused("unknown probe " + name);
        }
    }

    // ------------------------------------------------------------------ Rewind

    static final String REWIND_DIR = "com.bedwarsqol.rewind.RewindDir";
    static final String REWIND_CHECK = "com.bedwarsqol.rewind.RewindCheck";

    /** The folder Rewind writes to in this game ({@code RewindDir.get()}, so cobdev's override when set). */
    private static java.io.File rewindDir() throws Exception {
        Class<?> c = Class.forName(REWIND_DIR, true, Probes.class.getClassLoader());
        return (java.io.File) c.getMethod("get").invoke(null);
    }

    /** Closed recordings (their {@code .json} says complete), oldest first by id (a timestamp). Never the active one. */
    private static List<java.io.File> closedRecordings(java.io.File dir) {
        List<java.io.File> out = new ArrayList<>();
        String[] names = dir.list();
        if (names == null) return out;
        Arrays.sort(names);
        for (String n : names) {
            if (!n.endsWith(".cbr") || !Policy.rewindFile(n)) continue;
            java.io.File cbr = new java.io.File(dir, n);
            java.io.File json = new java.io.File(dir, n.substring(0, n.length() - 4) + ".json");
            if (!java.nio.file.Files.isRegularFile(cbr.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || !java.nio.file.Files.isRegularFile(json.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (Reader r = new InputStreamReader(new java.io.FileInputStream(json), StandardCharsets.UTF_8)) {
                JsonElement e = new JsonParser().parse(r);
                JsonElement complete = e.isJsonObject() ? e.getAsJsonObject().get("complete") : null;
                if (complete != null && complete.isJsonPrimitive() && complete.getAsBoolean()) out.add(cbr);
            } catch (Exception ignored) {
                // a .json being written or broken: not a closed recording
            }
        }
        return out;
    }

    /**
     * {@code rewind-latest}: {@code RewindCheck.summarize} of the newest closed recording, counts only (the summary
     * line, its exit code and how many closed recordings there are); no path, id, name or chat.
     */
    private static Map<String, Object> rewindLatest() throws Exception {
        List<java.io.File> closed = closedRecordings(rewindDir());
        Map<String, Object> out = Bridge.ok();
        out.put("closed", closed.size());
        out.put("found", !closed.isEmpty());
        if (closed.isEmpty()) return out;
        Class<?> check = Class.forName(REWIND_CHECK, true, Probes.class.getClassLoader());
        Object summary = check.getMethod("summarize", java.io.File.class).invoke(null, closed.get(closed.size() - 1));
        int exit = (Integer) summary.getClass().getMethod("exitCode").invoke(summary);
        out.put("line", summary.getClass().getMethod("line").invoke(summary));
        out.put("exit", exit);
        out.put("pass", exit == 0);
        return out;
    }

    /** {@code rewind-clear}: deletes the closed recordings of cobdev's own Rewind folder only (never ~/.cobblify). */
    private static Map<String, Object> rewindClear() throws Exception {
        java.io.File dir = rewindDir();
        Refused.check(Policy.rewindClear(System.getProperty(Policy.REWIND_DIR_PROPERTY), dir.getAbsolutePath(),
                System.getProperty("user.home")));
        int deleted = 0;
        for (java.io.File cbr : closedRecordings(dir)) {
            String id = cbr.getName().substring(0, cbr.getName().length() - 4);
            for (String n : new String[]{id + ".cbr", id + ".json", id + ".stats.json"}) {
                java.io.File f = new java.io.File(dir, n);
                if (Policy.rewindFile(n) && java.nio.file.Files.deleteIfExists(f.toPath())) deleted++;
            }
        }
        Map<String, Object> out = Bridge.ok();
        out.put("deleted", deleted);
        out.put("left", closedRecordings(dir).size());
        return out;
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
            Minecraft mc = Bridge.mc();
            Map<String, Object> client = describe(mc.thePlayer);
            client.put("sprinting", mc.thePlayer.isSprinting());
            client.put("sprintKeyDown", mc.gameSettings.keyBindSprint.isKeyDown());
            o.put("client", client);
            return o;
        });
        out.put("server", onServer(() -> describe(serverPlayer())));
        return out;
    }

    /**
     * {@code block dx dy dz [id]} relative to the player's feet, or {@code block at x y z [id]} absolute:
     * read, or place {@code id} (e.g. minecraft:wool). The reply's {@code pos} is always absolute.
     */
    private static Map<String, Object> block(List<String> args) throws Exception {
        String id = Policy.blockId(args);
        return onServer(() -> {
            EntityPlayerMP p = serverPlayer();
            World w = p.worldObj;
            int[] t = Policy.blockTarget(args, MathHelper.floor_double(p.posX), MathHelper.floor_double(p.posY),
                    MathHelper.floor_double(p.posZ));
            BlockPos pos = new BlockPos(t[0], t[1], t[2]);
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

    /**
     * {@code hud-boxes}, read-only: every Edit HUD box ({@code BedwarsHudRenderer.getEditorBoxes}: content
     * box and pad, GUI px), every module's stored state ({@code HudModules.get}) and, while an
     * {@code EditHudGui} is open, its selection, menu, mode and the menu rows' GUI rects. All of it by
     * reflection on Cobblify's own (unremapped) names, so it works in dev and prod and against any
     * build: a field or method a build does not have yet reads as null.
     */
    private static Map<String, Object> hudBoxes() throws Exception {
        Minecraft mc = Bridge.mc();
        Class<?> renderer = cobblifyClass(HUD_RENDERER, "hud-boxes");
        Class<?> modules = cobblifyClass(HUD_MODULES, "hud-boxes");
        Field cfgField = declaredField(cobblifyClass(CONFIG_HOLDER, "hud-boxes"), "config");
        Object cfg = cfgField == null ? null : cfgField.get(null);
        if (cfg == null) throw new Refused("hud-boxes: BedwarsQol.config is not loaded yet");
        ScaledResolution sr = new ScaledResolution(mc);
        Map<String, Object> out = Bridge.ok();
        out.put("scale", sr.getScaleFactor());
        out.put("sw", sr.getScaledWidth());
        out.put("sh", sr.getScaledHeight());
        // Team Upgrades' smoke reads the GUI size and the scale factor under these names
        out.put("gui", Arrays.asList(sr.getScaledWidth(), sr.getScaledHeight()));
        out.put("scaleFactor", sr.getScaleFactor());

        List<?> boxes = (List<?>) call(method(renderer, "getEditorBoxes", 2, true), null, mc, cfg);
        List<Object> boxList = new ArrayList<>();
        for (Object b : boxes) boxList.add(read(b, BOX_FIELDS));
        out.put("boxes", boxList);

        // HudModules.allIds() once the scoreboard, chat and tab list join it; ids() before.
        Method ids = method(modules, "allIds", 0, false);
        if (ids == null) ids = method(modules, "ids", 0, true);
        Method get = method(modules, "get", 2, true);
        List<Object> states = new ArrayList<>();
        for (Object id : (List<?>) call(ids, null)) {
            Object st = call(get, null, cfg, id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            if (st != null) {
                for (String f : STATE_FIELDS) m.put(f, fieldValue(st, f));
            }
            states.add(m);
        }
        out.put("states", states);
        out.put("editor", editor(mc, boxes));
        return out;
    }

    /**
     * {@code sheet-node ID}, read-only: where the open settings sheet draws its interactive node {@code ID} (the
     * footer's Reset Positions, a module's switch, a row's control), in GUI px and display px, with the GUI-px centre
     * a {@code click} can use, and whether a body node is scrolled into view. Reads {@code SettingsGui}'s laid-out
     * frame, sheet unit, origin and scroll by reflection.
     */
    private static Map<String, Object> sheetNode(List<String> args) throws Exception {
        if (args.size() != 1 && args.size() != 2) throw new Refused("probe sheet-node ID [TYPE]");
        String id = args.get(0);
        // A module's header (OPEN) shares its id with its switch: without TYPE the control wins.
        String type = args.size() == 2 ? args.get(1) : null;
        Minecraft mc = Bridge.mc();
        GuiScreen screen = mc.currentScreen;
        if (screen == null || !screen.getClass().getName().equals(SETTINGS_GUI)) {
            throw new Refused("sheet-node needs the settings sheet open (cobdev open settings)");
        }
        Object frame = raw(screen, "frame");
        if (frame == null) throw new Refused("the sheet has not drawn a frame yet");
        float unit = ((Number) raw(screen, "unit")).floatValue();
        float originX = ((Number) raw(screen, "originX")).floatValue();
        Object paint = raw(screen, "paint");
        float scroll = paint == null ? 0f : ((Number) raw(paint, "scroll")).floatValue();
        float bodyTop = ((Number) raw(frame, "bodyTop")).floatValue();
        float bodyHeight = ((Number) raw(frame, "bodyHeight")).floatValue();
        Object node = null, header = null;
        boolean inBody = false, headerInBody = false;
        for (String part : new String[]{"chrome", "body"}) {
            for (Object n : (List<?>) raw(frame, part)) {
                Method interactive = method(n.getClass(), "interactive", 0, true);
                if (!id.equals(raw(n, "id")) || !Boolean.TRUE.equals(call(interactive, n))) continue;
                String t = String.valueOf(raw(n, "type"));
                if (type != null ? !type.equals(t) : "OPEN".equals(t)) {
                    if (type == null && header == null) {
                        header = n;
                        headerInBody = part.equals("body");
                    }
                    continue;
                }
                node = n;
                inBody = part.equals("body");
                break;
            }
            if (node != null) break;
        }
        if (node == null && header != null) {
            node = header;
            inBody = headerInBody;
        }
        if (node == null) throw new Refused("no interactive sheet node " + id + " on this page");
        float nx = ((Number) raw(node, "x")).floatValue(), ny = ((Number) raw(node, "y")).floatValue();
        float nw = ((Number) raw(node, "w")).floatValue(), nh = ((Number) raw(node, "h")).floatValue();
        float sy = inBody ? bodyTop + ny - scroll : ny;
        float dx = originX + nx * unit, dy = sy * unit, dw = nw * unit, dh = nh * unit;
        int sf = new ScaledResolution(mc).getScaleFactor();
        Map<String, Object> out = Bridge.ok();
        out.put("id", id);
        out.put("type", String.valueOf(raw(node, "type")));
        out.put("inBody", inBody);
        out.put("contentY", finite(ny));
        out.put("visible", !inBody || (sy >= bodyTop && sy + nh <= bodyTop + bodyHeight));
        out.put("gui", Arrays.asList(finite(dx / sf), finite(dy / sf), finite(dw / sf), finite(dh / sf)));
        out.put("device", Arrays.asList(finite(dx), finite(dy), finite(dw), finite(dh)));
        out.put("centre", Arrays.asList((int) ((dx + dw / 2f) / sf), (int) ((dy + dh / 2f) / sf)));
        return out;
    }

    /** A declared field's raw value anywhere up the hierarchy; null when there is no such field. */
    private static Object raw(Object o, String name) throws Exception {
        Field f = declaredField(o.getClass(), name);
        return f == null ? null : f.get(o);
    }

    /** The open Edit HUD's state, or null when no {@code EditHudGui} is open. */
    private static Map<String, Object> editor(Minecraft mc, List<?> boxes) throws Exception {
        GuiScreen screen = mc.currentScreen;
        if (screen == null || !screen.getClass().getName().equals(EDIT_HUD)) return null;
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("selectedId", fieldValue(screen, "selectedId"));
        e.put("menuId", fieldValue(screen, "menuId"));
        e.put("mode", fieldValue(screen, "mode"));
        e.put("tabHeld", tabHeld(screen));
        e.put("playerListPressed", mc.gameSettings.keyBindPlayerList.isKeyDown());
        // visibleBoxes() once the editor filters what it shows (Tab held); until then it shows every box.
        Method visible = method(screen.getClass(), "visibleBoxes", 0, false);
        List<?> shown = visible == null ? boxes : (List<?>) call(visible, screen);
        List<Object> visibleIds = new ArrayList<>();
        for (Object b : shown) visibleIds.add(fieldValue(b, "id"));
        e.put("visibleIds", visibleIds);
        e.put("menu", menu(screen));
        return e;
    }

    /** A boolean field {@code tabHeld}, or a {@code tabHold} object with {@code held()}; null before either exists. */
    private static Object tabHeld(Object screen) throws Exception {
        Object v = fieldValue(screen, "tabHeld");
        if (v instanceof Boolean) return v;
        Field hold = declaredField(screen.getClass(), "tabHold");
        Object h = hold == null ? null : hold.get(screen);
        Method held = h == null ? null : method(h.getClass(), "held", 0, false);
        return held == null ? null : call(held, h);
    }

    /** The right-click menu: its rect and one GUI rect per row ({@code menuItems/menuRect/menuRowHeight}). */
    private static Map<String, Object> menu(Object screen) throws Exception {
        if (fieldValue(screen, "menuId") == null) return null;
        Method items = method(screen.getClass(), "menuItems", 0, false);
        Method rect = method(screen.getClass(), "menuRect", 1, false);
        Method rowHeight = method(screen.getClass(), "menuRowHeight", 0, false);
        if (items == null || rect == null || rowHeight == null) return null;
        List<?> labels = (List<?>) call(items, screen);
        float[] r = (float[]) call(rect, screen, labels);
        float rowH = ((Number) call(rowHeight, screen)).floatValue();
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", String.valueOf(labels.get(i)));
            row.put("x", finite(r[0]));
            row.put("y", finite(r[1] + rowH * (i + 1))); // row 0 is the title
            row.put("w", finite(r[2] - r[0]));
            row.put("h", finite(rowH));
            rows.add(row);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rect", Arrays.asList(finite(r[0]), finite(r[1]), finite(r[2]), finite(r[3])));
        m.put("rows", rows);
        return m;
    }

    private static Map<String, Object> read(Object o, String[][] fields) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String[] f : fields) m.put(f[0], fieldValue(o, f[1]));
        return m;
    }

    /** A declared field's value anywhere up the hierarchy as JSON-safe data; null when there is no such field. */
    private static Object fieldValue(Object o, String name) throws Exception {
        Field f = declaredField(o.getClass(), name);
        if (f == null) return null;
        Object v = f.get(o);
        if (v instanceof Float || v instanceof Double) return finite(((Number) v).doubleValue());
        return Mods.plain(v);
    }

    private static Field declaredField(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            }
        }
        return null;
    }

    /** The one declared method {@code name} with {@code params} parameters, or null (or a Refused if required). */
    private static Method method(Class<?> c, String name, int params, boolean required) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == params) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        if (required) throw new Refused("hud-boxes expects " + c.getName() + "." + name + " with " + params + " parameters");
        return null;
    }

    /** JSON has no NaN or infinity. */
    private static Object finite(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? null : (Object) (float) v;
    }

    /**
     * {@code open-shop FIXTURE}: opens a server-side chest window filled from the bridge resource
     * {@code team-upgrades-FIXTURE.json} ({@code title}, {@code size}, {@code items[]} of {@code slot},
     * {@code id}, {@code damage}, {@code count}, {@code name}, {@code lore[]}), the way Hypixel's
     * {@code Upgrades & Traps} window arrives: an open-window packet plus the slot contents. Close it
     * with {@code cobdev close}.
     */
    private static Map<String, Object> openShop(List<String> args) throws Exception {
        Refused.check(Policy.shopFixture(args));
        String resource = "/team-upgrades-" + args.get(0) + ".json";
        JsonObject fixture;
        try (InputStream in = Probes.class.getResourceAsStream(resource)) {
            if (in == null) throw new Refused("no fixture " + resource + " in the bridge");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                fixture = new JsonParser().parse(r).getAsJsonObject();
            }
        }
        String title = fixture.get("title").getAsString();
        int size = fixture.has("size") ? fixture.get("size").getAsInt() : 54;
        if (size <= 0 || size > 54 || size % 9 != 0) throw new Refused(resource + ": size must be 9-54 in rows of 9");
        InventoryBasic window = new InventoryBasic(title, true, size);
        int filled = 0;
        for (JsonElement e : fixture.getAsJsonArray("items")) {
            JsonObject o = e.getAsJsonObject();
            int slot = o.get("slot").getAsInt();
            if (slot < 0 || slot >= size) throw new Refused(resource + ": slot " + slot + " is outside the window");
            String id = o.get("id").getAsString();
            Item item = Item.getByNameOrId(id);
            if (item == null) throw new Refused(resource + ": unknown item " + id);
            int count = o.has("count") ? o.get("count").getAsInt() : 1;
            int damage = o.has("damage") ? o.get("damage").getAsInt() : 0;
            ItemStack stack = new ItemStack(item, count, damage);
            NBTTagCompound display = new NBTTagCompound();
            if (o.has("name")) display.setString("Name", o.get("name").getAsString());
            if (o.has("lore")) {
                NBTTagList lore = new NBTTagList();
                JsonArray lines = o.getAsJsonArray("lore");
                for (JsonElement line : lines) lore.appendTag(new NBTTagString(line.getAsString()));
                display.setTag("Lore", lore);
            }
            NBTTagCompound tag = new NBTTagCompound();
            tag.setTag("display", display);
            stack.setTagCompound(tag);
            window.setInventorySlotContents(slot, stack);
            filled++;
        }
        int items = filled;
        return onServer(() -> {
            serverPlayer().displayGUIChest(window);
            Map<String, Object> out = Bridge.ok();
            out.put("fixture", args.get(0));
            out.put("title", title);
            out.put("size", size);
            out.put("items", items);
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
