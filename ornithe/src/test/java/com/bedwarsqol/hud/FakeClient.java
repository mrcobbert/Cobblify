package com.bedwarsqol.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.settings.GameSettings;

import java.lang.reflect.Field;

/**
 * Just enough of a client for the HUD font's measuring paths to run in a unit test: a {@link Minecraft} with a window
 * size, a GUI scale and a font whose glyphs are all 6 px wide, installed as {@code Minecraft.getMinecraft()}. Built
 * without running any constructor (no window, no GL). {@link #close} puts back what was there.
 */
final class FakeClient implements AutoCloseable {

    /** Every character 6 font px wide, as most of vanilla's ASCII glyphs are. */
    static final int GLYPH = 6;

    static final class Mc extends Minecraft {
        Mc() {
            super(null); // never run: instances come from allocateInstance
        }

        @Override
        public boolean isUnicode() {
            return false;
        }
    }

    static final class Font extends FontRenderer {
        Font() {
            super(null, null, null, false); // never run
        }

        @Override
        public int getStringWidth(String text) {
            return text == null ? 0 : text.length() * GLYPH;
        }

        @Override
        public int getCharWidth(char c) {
            return GLYPH;
        }
    }

    private final Field instance;
    private final Object previous;
    final Minecraft mc;

    /** A {@code width} x {@code height} window at GUI scale {@code guiScale}. */
    FakeClient(int width, int height, int guiScale) throws Exception {
        mc = (Minecraft) allocate(Mc.class);
        mc.displayWidth = width;
        mc.displayHeight = height;
        mc.gameSettings = (GameSettings) allocate(GameSettings.class);
        mc.gameSettings.guiScale = guiScale;
        mc.fontRendererObj = (FontRenderer) allocate(Font.class);
        instance = Minecraft.class.getDeclaredField("theMinecraft");
        instance.setAccessible(true);
        previous = instance.get(null);
        instance.set(null, mc);
    }

    @Override
    public void close() throws Exception {
        instance.set(null, previous);
    }

    /** An instance of {@code type} with no constructor run ({@code sun.misc.Unsafe.allocateInstance}). */
    private static Object allocate(Class<?> type) throws Exception {
        Class<?> u = Class.forName("sun.misc.Unsafe");
        Field f = u.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return u.getMethod("allocateInstance", Class.class).invoke(f.get(null), type);
    }
}
