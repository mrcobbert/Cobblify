package com.bedwarsqol.rewind.export;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RegionRenderCacheBuilder;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderChunk;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Queue;
import java.util.Set;

/**
 * The vanilla renderer's terrain-readiness signal (Rewind Export PLAN Q1 answer a, check K10 step 1). From the 1.8.9
 * bytecode ({@code javap -c -p}): {@code ChunkRenderDispatcher.getDebugInfo()} prints {@code "pC: %03d, pU: %1d, aB:
 * %1d"} from {@code queueChunkUpdates.size()}, {@code queueChunkUploads.size()} and {@code queueFreeRenderBuilders.size()};
 * the constructor puts {@value #BUILDERS} builders in the free queue (2 worker threads and the main thread take one per
 * task); {@code RenderGlobal.chunksToUpdate} holds the visible chunks marked dirty that were not handed to the
 * dispatcher yet. Ready = all three queues and that set empty, every builder free.
 *
 * <p>The fields are private, so they are found by their declared generic types (the same in MCP-named dev runs and
 * intermediary production). With another renderer in place (Argentum replaces terrain rendering) these queues are not
 * where meshes are built and {@link #ready} is not a signal: callers check {@link #vanillaRenderer()} first. Game
 * thread.
 */
final class VanillaChunkQueues {

    /** Free builders when idle ({@code ChunkRenderDispatcher.<init>}: {@code for (j = 0; j < 5; ++j)}). */
    static final int BUILDERS = 5;

    private static boolean looked;
    private static Field toUpdate;
    private static Field dispatcher;
    private static Field updates;
    private static Field uploads;
    private static Field free;

    private VanillaChunkQueues() {
    }

    /** No mod known to replace vanilla's chunk building is loaded. */
    static boolean vanillaRenderer() {
        try {
            return !net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("argentum");
        } catch (Throwable t) {
            return true; // no loader API: vanilla
        }
    }

    /** True when ready, false when building, null when the fields cannot be read. */
    static Boolean ready(Minecraft mc) {
        int[] c = counts(mc);
        return c == null ? null : c[0] == 0 && c[1] == 0 && c[2] == 0 && c[3] == BUILDERS;
    }

    /** {chunksToUpdate, pC, pU, aB}, or null. */
    static int[] counts(Minecraft mc) {
        try {
            find();
            RenderGlobal rg = mc.renderGlobal;
            if (rg == null || toUpdate == null || dispatcher == null) {
                return null;
            }
            Object d = dispatcher.get(rg);
            Object set = toUpdate.get(rg);
            if (d == null || set == null) {
                return null;
            }
            return new int[]{((Collection<?>) set).size(), ((Collection<?>) updates.get(d)).size(),
                    ((Collection<?>) uploads.get(d)).size(), ((Collection<?>) free.get(d)).size()};
        } catch (Throwable t) {
            return null;
        }
    }

    private static synchronized void find() {
        if (looked) {
            return;
        }
        looked = true;
        for (Field f : RenderGlobal.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (f.getType() == ChunkRenderDispatcher.class) {
                dispatcher = open(f);
            } else if (Set.class.isAssignableFrom(f.getType()) && arg(f) == RenderChunk.class) {
                toUpdate = open(f);
            }
        }
        for (Field f : ChunkRenderDispatcher.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || !Queue.class.isAssignableFrom(f.getType())) {
                continue;
            }
            Type a = arg(f);
            if (a == ChunkCompileTaskGenerator.class) {
                updates = open(f);
            } else if (a == RegionRenderCacheBuilder.class) {
                free = open(f);
            } else {
                uploads = open(f); // Queue<ListenableFutureTask<?>>
            }
        }
        if (updates == null || free == null || uploads == null) {
            dispatcher = null;
        }
    }

    private static Type arg(Field f) {
        Type t = f.getGenericType();
        if (t instanceof ParameterizedType) {
            Type[] a = ((ParameterizedType) t).getActualTypeArguments();
            if (a.length == 1) {
                Type x = a[0];
                return x instanceof ParameterizedType ? ((ParameterizedType) x).getRawType() : x;
            }
        }
        return null;
    }

    private static Field open(Field f) {
        f.setAccessible(true);
        return f;
    }
}
