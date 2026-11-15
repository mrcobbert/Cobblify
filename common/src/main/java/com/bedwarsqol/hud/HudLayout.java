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

    /** A module's drawn size ({width, height}, panel included) at a given scale. */
    public interface Sizer {
        float[] size(float scale);
    }

    /** One place a module may go: where it starts and which way it slides when blocked. */
    public static final class Spot {
        final Horizontal h;
        final Vertical v;
        final Slide slide;
        /** A fixed top-left corner (NaN for a screen-relative spot): {@link #fit}'s free-place search. */
        final float x;
        final float y;

        public Spot(Horizontal h, Vertical v, Slide slide) {
            this(h, v, slide, Float.NaN, Float.NaN);
        }

        private Spot(Horizontal h, Vertical v, Slide slide, float x, float y) {
            this.h = h;
            this.v = v;
            this.slide = slide;
            this.x = x;
            this.y = y;
        }

        static Spot at(float x, float y) {
            return new Spot(Horizontal.LEFT, Vertical.TOP, Slide.NONE, x, y);
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
     * for the ones after it. Returns each item's drawn box as {x, y, width, height}, by id.
     */
    public Map<String, float[]> place(List<Item> items, List<Box> obstacles) {
        List<Box> taken = new ArrayList<Box>(obstacles);
        Map<String, float[]> out = new LinkedHashMap<String, float[]>();
        for (Item item : items) {
            float[] at = placeOne(item, taken);
            out.put(item.id, new float[]{at[0], at[1], item.width, item.height});
            taken.add(new Box(at[0], at[1], item.width, item.height));
        }
        return out;
    }

    /**
     * The largest of {@code scale}, {@code scale - step}, ..., {@code min} at which the module
     * {@code id}, {@code sizer}'s size at that scale, placed after {@code before} (in order, around
     * {@code obstacles}), finds one of {@code spots} free and fully on screen. {@code scale} when it already
     * does; the lowest step tried when nothing does.
     */
    public float fitScale(List<Item> before, List<Box> obstacles, String id, float scale, float step, float min,
                          Sizer sizer, Spot... spots) {
        List<Box> taken = placed(before, obstacles);
        float s = scale;
        while (true) {
            float[] size = sizer.size(s);
            if (placeOne(new Item(id, size[0], size[1], spots), taken)[2] > 0f) return s;
            if (s <= min + 1e-4f) return s;
            s = Math.max(min, s - step); // a step past the minimum tries the minimum itself
        }
    }

    /** The scale a module fits at and the spots to place it with ({@link #fit}). */
    public static final class Fit {
        public final float scale;
        public final Spot[] spots;
        /** False when no spot is free even so: the module takes the least-covered place. */
        public final boolean free;

        Fit(float scale, Spot[] spots, boolean free) {
            this.scale = scale;
            this.spots = spots;
            this.free = free;
        }
    }

    /**
     * The largest of {@code scale}, {@code scale - step}, ..., {@code min} at which module {@code id} finds one of
     * {@code spots} or, after them, one of the {@code crowded} spots free, and the spots that find it. Both are tried
     * at each size before the next size down, so a full edge moves the module aside at full size rather than shrinking
     * it (HUD tweaks: Team Upgrades drew at half size beside free room). Only when nothing is free even at {@code min}
     * does it take any free place on screen at that size, nearest the first spot's start (review code-2 I1): a place
     * out in the open, possibly by the crosshair, stays the last resort. With none, {@link Fit#free} is false.
     */
    public Fit fit(List<Item> before, List<Box> obstacles, String id, float scale, float step, float min,
                   Sizer sizer, Spot[] spots, Spot[] crowded) {
        List<Box> taken = placed(before, obstacles);
        Spot[] withCrowded = Arrays.copyOf(spots, spots.length + crowded.length);
        System.arraycopy(crowded, 0, withCrowded, spots.length, crowded.length);
        float s = scale;
        while (true) {
            float[] size = sizer.size(s);
            if (placeOne(new Item(id, size[0], size[1], spots), taken)[2] > 0f) return new Fit(s, spots, true);
            if (placeOne(new Item(id, size[0], size[1], withCrowded), taken)[2] > 0f) return new Fit(s, withCrowded, true);
            if (s <= min + 1e-4f) break;
            s = Math.max(min, s - step); // a step past the minimum tries the minimum itself
        }
        float[] size = sizer.size(s);
        Item item = new Item(id, size[0], size[1], withCrowded);
        float[] free = freePlace(item, taken, spots.length > 0 ? start(item, spots[0]) : new float[]{edge, edge});
        if (free == null) return new Fit(s, withCrowded, false);
        Spot[] all = Arrays.copyOf(withCrowded, withCrowded.length + 1);
        all[all.length - 1] = Spot.at(free[0], free[1]);
        return new Fit(s, all, true);
    }

    /** {@code obstacles} plus {@code before} placed around them, in order. */
    private List<Box> placed(List<Item> before, List<Box> obstacles) {
        List<Box> taken = new ArrayList<Box>(obstacles);
        for (Item item : before) {
            float[] at = placeOne(item, taken);
            taken.add(new Box(at[0], at[1], item.width, item.height));
        }
        return taken;
    }

    /**
     * The free, fully on-screen place for {@code item} nearest {@code near}; null when there is none. A free
     * place can always slide left and up until a screen edge or a box (plus the gap) stops it, so trying
     * each such left edge with each such top edge (and their right- and bottom-aligned twins) finds one
     * whenever one exists.
     */
    private float[] freePlace(Item item, List<Box> taken, float[] near) {
        List<Float> xs = new ArrayList<Float>();
        List<Float> ys = new ArrayList<Float>();
        xs.add(edge);
        xs.add(screenWidth - edge - item.width);
        ys.add(edge);
        ys.add(screenHeight - edge - item.height);
        for (Box b : taken) {
            xs.add(b.right() + gap);
            xs.add(b.x - gap - item.width);
            ys.add(b.bottom() + gap);
            ys.add(b.y - gap - item.height);
        }
        float[] best = null;
        float bestDistance = Float.MAX_VALUE;
        for (float x : xs) {
            for (float y : ys) {
                if (!onScreen(x, y, item) || nearestHit(x, y, item, taken, Slide.NONE) != null) continue;
                float dx = x - near[0];
                float dy = y - near[1];
                float distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new float[]{x, y};
                }
            }
        }
        return best;
    }

    /** {x, y, free}: the first free spot (free = 1), else where it covers the least (free = 0). */
    private float[] placeOne(Item item, List<Box> taken) {
        float[] fallback = null;
        float leastCovered = Float.MAX_VALUE;
        for (Spot spot : item.spots) {
            float[] at = start(item, spot);
            for (int i = 0; i <= MAX_JUMPS; i++) {
                boolean onScreen = onScreen(at[0], at[1], item);
                Box hit = nearestHit(at[0], at[1], item, taken, spot.slide);
                if (onScreen && hit == null) return new float[]{at[0], at[1], 1f};
                float[] kept = clampToScreen(at, item);
                float covered = covered(kept, item, taken);
                if (covered < leastCovered) {
                    leastCovered = covered;
                    fallback = kept;
                }
                if (!onScreen || hit == null || !jump(at, item, hit, spot.slide)) break;
            }
        }
        return fallback != null ? new float[]{fallback[0], fallback[1], 0f} : new float[]{edge, edge, 0f};
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
        if (!Float.isNaN(spot.x)) return new float[]{spot.x, spot.y};
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
