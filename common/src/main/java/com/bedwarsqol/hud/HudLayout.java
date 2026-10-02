package com.bedwarsqol.hud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The default HUD layout: places modules the player has not moved so that none of them overlap
 * each other, a module the player did move, or the vanilla hotbar.
 *
 * <p>Modules are placed one at a time in the order given. Each tries its spots in turn: a spot is
 * a screen corner, edge or centre plus a direction to slide in. When the box hits something it
 * jumps past it in that direction and tries again. The first spot that ends free and fully on screen
 * wins. When none does (the screen is simply too full), the module goes wherever along those paths
 * it covers the least of what is already placed. All boxes are drawn bounds (panel or margin
 * included), in scaled GUI px.
 */
public final class HudLayout {

    public enum Horizontal { LEFT, CENTRE, RIGHT }

    public enum Vertical { TOP, MIDDLE, BOTTOM }

    public enum Slide { NONE, UP, DOWN, LEFT, RIGHT }

    /** One place a module may go: where it starts and which way it slides when blocked. */
    public static final class Spot {
        final Horizontal h;
        final Vertical v;
        final Slide slide;

        public Spot(Horizontal h, Vertical v, Slide slide) {
            this.h = h;
            this.v = v;
            this.slide = slide;
        }
    }

    /** A module to place: its drawn size and the spots it prefers, best first. */
    public static final class Item {
        final String id;
        final float width;
        final float height;
        final List<Spot> spots;

        public Item(String id, float width, float height, Spot... spots) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.spots = Arrays.asList(spots);
        }
    }

    /** A box already on screen that placed modules must keep clear of. */
    public static final class Box {
        public final float x;
        public final float y;
        public final float width;
        public final float height;

        public Box(float x, float y, float width, float height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        float right() {
            return x + width;
        }

        float bottom() {
            return y + height;
        }
    }

    private static final int MAX_JUMPS = 16;

    private final float screenWidth;
    private final float screenHeight;
    private final float edge;
    private final float gap;

    /**
     * @param edge distance kept from the screen edges
     * @param gap  distance kept between boxes
     */
    public HudLayout(float screenWidth, float screenHeight, float edge, float gap) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.edge = edge;
        this.gap = gap;
    }

    /**
     * Places {@code items} in order around {@code obstacles}. Each placed item becomes an obstacle
     * for the ones after it. Returns the top-left corner of each item's drawn box, by id.
     */
    public Map<String, float[]> place(List<Item> items, List<Box> obstacles) {
        List<Box> taken = new ArrayList<Box>(obstacles);
        Map<String, float[]> out = new LinkedHashMap<String, float[]>();
        for (Item item : items) {
            float[] at = placeOne(item, taken);
            out.put(item.id, at);
            taken.add(new Box(at[0], at[1], item.width, item.height));
        }
        return out;
    }

    private float[] placeOne(Item item, List<Box> taken) {
        float[] fallback = null;
        float leastCovered = Float.MAX_VALUE;
        for (Spot spot : item.spots) {
            float[] at = start(item, spot);
            for (int i = 0; i <= MAX_JUMPS; i++) {
                boolean onScreen = onScreen(at[0], at[1], item);
                Box hit = nearestHit(at[0], at[1], item, taken, spot.slide);
                if (onScreen && hit == null) return at;
                float[] kept = clampToScreen(at, item);
                float covered = covered(kept, item, taken);
                if (covered < leastCovered) {
                    leastCovered = covered;
                    fallback = kept;
                }
                if (!onScreen || hit == null || !jump(at, item, hit, spot.slide)) break;
            }
        }
        return fallback != null ? fallback : new float[]{edge, edge};
    }

    /** Moves {@code at} past {@code hit} in direction {@code slide}; false when it does not slide. */
    private boolean jump(float[] at, Item item, Box hit, Slide slide) {
        switch (slide) {
            case DOWN:
                at[1] = hit.bottom() + gap;
                return true;
            case UP:
                at[1] = hit.y - gap - item.height;
                return true;
            case LEFT:
                at[0] = hit.x - gap - item.width;
                return true;
            case RIGHT:
                at[0] = hit.right() + gap;
                return true;
            default:
                return false;
        }
    }

    private float[] clampToScreen(float[] at, Item item) {
        float x = Math.max(edge, Math.min(at[0], screenWidth - edge - item.width));
        float y = Math.max(edge, Math.min(at[1], screenHeight - edge - item.height));
        return new float[]{x, y};
    }

    /** How much of the already placed boxes the item would cover at {@code at}. */
    private static float covered(float[] at, Item item, List<Box> taken) {
        float sum = 0f;
        for (Box b : taken) {
            float w = Math.min(at[0] + item.width, b.right()) - Math.max(at[0], b.x);
            float h = Math.min(at[1] + item.height, b.bottom()) - Math.max(at[1], b.y);
            if (w > 0f && h > 0f) sum += w * h;
        }
        return sum;
    }

    private float[] start(Item item, Spot spot) {
        float x = spot.h == Horizontal.LEFT ? edge
                : spot.h == Horizontal.RIGHT ? screenWidth - edge - item.width
                : (screenWidth - item.width) / 2f;
        float y = spot.v == Vertical.TOP ? edge
                : spot.v == Vertical.BOTTOM ? screenHeight - edge - item.height
                : (screenHeight - item.height) / 2f;
        return new float[]{x, y};
    }

    private boolean onScreen(float x, float y, Item item) {
        return x >= edge - 0.01f && y >= edge - 0.01f
                && x + item.width <= screenWidth - edge + 0.01f
                && y + item.height <= screenHeight - edge + 0.01f;
    }

    /**
     * Of the boxes closer than {@code gap} to the item at (x, y), the one that moves it least when it
     * jumps past in direction {@code slide}; null when nothing is in the way.
     */
    private Box nearestHit(float x, float y, Item item, List<Box> taken, Slide slide) {
        Box best = null;
        for (Box b : taken) {
            if (!(x < b.right() + gap && b.x < x + item.width + gap
                    && y < b.bottom() + gap && b.y < y + item.height + gap)) continue;
            if (best == null
                    || slide == Slide.DOWN && b.bottom() < best.bottom()
                    || slide == Slide.UP && b.y > best.y
                    || slide == Slide.LEFT && b.x > best.x
                    || slide == Slide.RIGHT && b.right() < best.right()) best = b;
        }
        return best;
    }
}
