package cobdev.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiConfirmOpenLink;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Probes for Cobblify's overlay items (the scoreboard, chat and tab list as HUD items; cycle
 * 2026-11-14-hud-overlays, Phase 6). Cobblify is reached by reflection on its public API
 * ({@code BedwarsHudRenderer.overlayBox}, {@code setHudAbsolutePosition}, Copy Chat's
 * {@code bedwarsqol$fullComponentAt}), so there is no product hook; Cobblify's names are not remapped,
 * so the same calls work in dev and prod. Every probe needs the singleplayer world ({@link Probes#run}).
 */
final class OverlayProbes {

    static final String RENDERER = "com.bedwarsqol.hud.BedwarsHudRenderer";
    static final String MOD = "com.bedwarsqol.BedwarsQol";
    static final String COPY_CHAT = "bedwarsqol$fullComponentAt";

    /** Vanilla's chat heights before the first {@code chat-heights}, restored by {@code chat-heights restore}. */
    private static float[] savedHeights;

    private OverlayProbes() {
    }

    static Object run(String name, List<String> args) throws Exception {
        switch (name) {
            case "overlay-boxes":
                return Bridge.onMain(OverlayProbes::boxes);
            case "chat-fixture":
                return chatFixture(args);
            case "chat-clear":
                return Bridge.onMain(() -> {
                    chat().clearChatMessages();
                    return Bridge.ok();
                });
            case "chat-open":
                return Bridge.onMain(() -> {
                    Bridge.requireLocal();
                    Bridge.mc().displayGuiScreen(new GuiChat());
                    return Bridge.ok();
                });
            case "chat-at":
                return chatAt(args);
            case "chat-click":
                return chatClick(args);
            case "tab-header-footer":
                return tabHeaderFooter(args);
            case "place-item":
                return placeItem(args);
            case "chat-heights":
                return chatHeights(args);
            default:
                throw new Refused("unknown probe " + name);
        }
    }

    /** {@code overlay-boxes}: the scoreboard, chat and tab list boxes (GUI px), the scale factor and GUI size. */
    private static Map<String, Object> boxes() throws Exception {
        Minecraft mc = Bridge.mc();
        ScaledResolution sr = new ScaledResolution(mc);
        Map<String, Object> out = Bridge.ok();
        out.put("scaleFactor", sr.getScaleFactor());
        out.put("gui", java.util.Arrays.asList(sr.getScaledWidth(), sr.getScaledHeight()));
        Map<String, Object> items = new LinkedHashMap<>();
        for (String id : Policy.OVERLAY_ITEMS) items.put(id, box(mc, id));
        out.put("items", items);
        return out;
    }

    /** One item's box as {@code {x, y, w, h, shown}}, or null when it has nothing to show. */
    private static Map<String, Object> box(Minecraft mc, String id) throws Exception {
        Object box = call(method(renderer(), "overlayBox", 3), null, mc, config(), id);
        if (box == null) return null;
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", id);
        o.put("x", box.getClass().getField("x").get(box));
        o.put("y", box.getClass().getField("y").get(box));
        o.put("w", box.getClass().getField("width").get(box));
        o.put("h", box.getClass().getField("height").get(box));
        o.put("shown", box.getClass().getField("shown").get(box));
        return o;
    }

    /** {@code place-item ID X Y}: puts the item's current box with its top-left at (X, Y), as Edit HUD does. */
    private static Map<String, Object> placeItem(List<String> args) throws Exception {
        if (args.size() != 3) throw new Refused("probe place-item scoreboard|chat|tablist X Y");
        String id = args.get(0);
        Refused.check(Policy.placeItem(id));
        float x, y;
        try {
            x = Float.parseFloat(args.get(1));
            y = Float.parseFloat(args.get(2));
        } catch (NumberFormatException e) {
            throw new Refused("place-item coordinates are numbers");
        }
        float px = x, py = y;
        return Bridge.onMain(() -> {
            Bridge.requireLocal();
            Minecraft mc = Bridge.mc();
            Map<String, Object> before = box(mc, id);
            if (before == null) throw new Refused(id + " has nothing to show, so no box to place");
            ScaledResolution sr = new ScaledResolution(mc);
            call(method(renderer(), "setHudAbsolutePosition", 8), null, config(), id, px, py,
                    ((Number) before.get("w")).floatValue(), ((Number) before.get("h")).floatValue(),
                    (float) sr.getScaledWidth(), (float) sr.getScaledHeight());
            Map<String, Object> out = Bridge.ok();
            out.put("before", before);
            out.put("after", box(mc, id));
            return out;
        });
    }

    /** {@code chat-fixture NAME}: prints the fixture's component JSON lines (bridge/resources/chat-NAME.json). */
    private static Map<String, Object> chatFixture(List<String> args) throws Exception {
        String name = args.isEmpty() ? null : args.get(0);
        Refused.check(Policy.chatFixture(name));
        JsonElement doc;
        try (InputStream in = OverlayProbes.class.getResourceAsStream("/chat-" + name + ".json")) {
            if (in == null) throw new Refused("chat fixture " + name + " is not in the bridge jar");
            doc = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<IChatComponent> lines = new java.util.ArrayList<>();
        for (JsonElement line : doc.getAsJsonObject().getAsJsonArray("lines")) {
            lines.add(IChatComponent.Serializer.jsonToComponent(line.toString()));
        }
        return Bridge.onMain(() -> {
            GuiNewChat chat = chat();
            for (IChatComponent c : lines) chat.printChatMessage(c);
            Map<String, Object> out = Bridge.ok();
            out.put("printed", lines.size());
            out.put("drawnRows", call(method(chat.getClass(), "bedwarsqol$drawnLineCount", 0), chat));
            return out;
        });
    }

    /**
     * {@code chat-at GX GY}: what vanilla's {@code getChatComponent} and Copy Chat answer for the GUI point,
     * through the bridge's own GUI-to-raw conversion. Read-only; never touches the clipboard.
     */
    private static Map<String, Object> chatAt(List<String> args) throws Exception {
        int[] p = Policy.chatPoint(args, false);
        return Bridge.onMain(() -> {
            int[] d = Bridge.toDisplay(p[0], p[1]);
            GuiNewChat chat = chat();
            Map<String, Object> out = Bridge.ok();
            out.put("raw", java.util.Arrays.asList(d[0], d[1]));
            out.put("chatOpen", chat.getChatOpen());
            out.put("component", describe(chat.getChatComponent(d[0], d[1])));
            out.put("copy", describe((IChatComponent) call(method(chat.getClass(), COPY_CHAT, 2), chat, d[0], d[1])));
            return out;
        });
    }

    /**
     * {@code chat-click GX GY [0]}: a left press and release in vanilla's chat screen (the only vanilla
     * screen input may reach, {@link Policy#chatClick}). {@code screenAfter} is semantic, so it reads the same
     * under intermediary names in prod.
     */
    private static Map<String, Object> chatClick(List<String> args) throws Exception {
        int[] p = Policy.chatPoint(args, true);
        int[] d = Bridge.onMain(() -> {
            Bridge.requireLocal();
            GuiScreen screen = Bridge.mc().currentScreen;
            Refused.check(Policy.chatClick(screen != null && screen.getClass() == GuiChat.class));
            // With Chat Links Prompt off, a link click opens the browser straight away: never do that.
            if (!Bridge.mc().gameSettings.chatLinksPrompt) {
                throw new Refused("Chat Links Prompt is off in Chat Settings: a link click would open the browser");
            }
            int[] at = Bridge.toDisplay(p[0], p[1]);
            Bridge.holdCursor(at);
            Bridge.inject(screen, at[0], at[1], p[2], true, 0);
            return at;
        });
        return Bridge.onMain(() -> {
            GuiScreen screen = Bridge.mc().currentScreen;
            // Release on the next frame, as a real click would, but only into the chat screen itself.
            if (screen != null && screen.getClass() == GuiChat.class) Bridge.inject(screen, d[0], d[1], p[2], false, 0);
            Map<String, Object> out = Bridge.ok();
            out.put("raw", java.util.Arrays.asList(d[0], d[1]));
            out.put("screenAfter", semantic(Bridge.mc().currentScreen));
            return out;
        });
    }

    private static String semantic(GuiScreen screen) {
        if (screen == null) return "none";
        if (screen.getClass() == GuiChat.class) return "chat";
        if (screen instanceof GuiConfirmOpenLink) return "confirm-open-link";
        return "other";
    }

    /** {@code tab-header-footer HEADER FOOTER} / {@code tab-header-footer clear}: the client tab list's header and footer. */
    private static Map<String, Object> tabHeaderFooter(List<String> args) throws Exception {
        String[] hf = Policy.tabHeaderFooter(args);
        return Bridge.onMain(() -> {
            GuiPlayerTabOverlay tab = Bridge.mc().ingameGUI.getTabList();
            if (hf == null) {
                tab.resetFooterHeader();
            } else {
                tab.setHeader(new ChatComponentText(hf[0]));
                tab.setFooter(new ChatComponentText(hf[1]));
            }
            return Bridge.ok();
        });
    }

    /**
     * {@code chat-heights FOCUSED UNFOCUSED} / {@code chat-heights restore}: vanilla's chat height sliders for
     * this session only (never written to options.txt by the probe).
     */
    private static Map<String, Object> chatHeights(List<String> args) throws Exception {
        float[] h = Policy.chatHeights(args);
        return Bridge.onMain(() -> {
            GameSettings gs = Bridge.mc().gameSettings;
            if (h == null) {
                if (savedHeights != null) {
                    gs.chatHeightFocused = savedHeights[0];
                    gs.chatHeightUnfocused = savedHeights[1];
                    savedHeights = null;
                }
            } else {
                if (savedHeights == null) savedHeights = new float[]{gs.chatHeightFocused, gs.chatHeightUnfocused};
                gs.chatHeightFocused = h[0];
                gs.chatHeightUnfocused = h[1];
            }
            chat().refreshChat();
            Map<String, Object> out = Bridge.ok();
            out.put("focused", gs.chatHeightFocused);
            out.put("unfocused", gs.chatHeightUnfocused);
            return out;
        });
    }

    // ------------------------------------------------------------------------------------------

    private static GuiNewChat chat() {
        Minecraft mc = Bridge.mc();
        if (mc.ingameGUI == null) throw new Refused("the in-game GUI is not up yet");
        return mc.ingameGUI.getChatGUI();
    }

    private static Map<String, Object> describe(IChatComponent c) {
        if (c == null) return null;
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("text", c.getUnformattedText());
        ChatStyle style = c.getChatStyle();
        ClickEvent click = style == null ? null : style.getChatClickEvent();
        HoverEvent hover = style == null ? null : style.getChatHoverEvent();
        if (click != null) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("action", click.getAction().getCanonicalName());
            e.put("value", click.getValue());
            o.put("click", e);
        }
        if (hover != null) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("action", hover.getAction().getCanonicalName());
            e.put("value", hover.getValue() == null ? null : hover.getValue().getUnformattedText());
            o.put("hover", e);
        }
        return o;
    }

    private static Class<?> renderer() {
        try {
            return Class.forName(RENDERER, true, OverlayProbes.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            throw new Refused("this build has no " + RENDERER);
        }
    }

    private static Object config() throws Exception {
        Object cfg = Class.forName(MOD, true, OverlayProbes.class.getClassLoader()).getField("config").get(null);
        if (cfg == null) throw new Refused("Cobblify's config is not loaded yet");
        return cfg;
    }

    /** The public static or instance method {@code name} with {@code params} parameters; refused when this build lacks it. */
    private static Method method(Class<?> owner, String name, int params) {
        for (Method m : owner.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == params) return m;
        }
        throw new Refused("this build has no " + owner.getName() + "." + name + " (overlay items not in it yet?)");
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
