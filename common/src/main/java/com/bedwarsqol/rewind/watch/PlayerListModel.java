package com.bedwarsqol.rewind.watch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The players another player's view can ride (PLAN "R3 Watch full"; TASK D1, D4): the client fills it from the
 * playback world (players that have a tab entry, minus the camera and the recorded player's puppet), and it keeps them
 * sorted by team, then name (case-insensitive), players with no team last, each with the team's colour: the first
 * colour code of their name tag ({@code ScorePlayerTeam.formatPlayerName}), as Cobblify's team colours read it.
 *
 * <ul>
 *   <li>{@link #cycle}: key 3 rides the next player, Shift+3 the previous, wrapping around; from your view or the free
 *       camera key 3 starts at the first and Shift+3 at the last.</li>
 *   <li>{@link #follow} / {@link #absent}: the ridden view's rule each frame (D4). While the ridden player is in the
 *       world the view follows them and the last pose is kept; once they are gone and playback is settled (not
 *       rebuilding or catching up after a seek, when the player may simply not be spawned yet) the view becomes the
 *       free camera at that last pose ({@link #lastPose}).</li>
 * </ul>
 *
 * Pure; the client thread.
 */
public final class PlayerListModel {

    public static final int WHITE = 0xFFFFFFFF;
    /** Minecraft's sixteen text colours, codes 0-9 and a-f. */
    private static final int[] CODE_RGB = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00,
            0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    private static final Comparator<Entry> ORDER = new Comparator<Entry>() {
        @Override
        public int compare(Entry a, Entry b) {
            if (a.team == null || b.team == null) {
                if (a.team != b.team) return a.team == null ? 1 : -1;
            } else {
                int t = a.team.compareTo(b.team);
                if (t != 0) return t;
            }
            int n = a.name.compareToIgnoreCase(b.name);
            if (n != 0) return n;
            n = a.name.compareTo(b.name);
            return n != 0 ? n : Integer.compare(a.entityId, b.entityId);
        }
    };

    /** One player: entity id, name, team (null for none) and the team's colour (ARGB). */
    public static final class Entry {
        public final int entityId;
        public final String name;
        public final String team;
        public final int colour;

        Entry(int entityId, String name, String team, int colour) {
            this.entityId = entityId;
            this.name = name;
            this.team = team;
            this.colour = colour;
        }
    }

    /** What the ridden view does this frame. */
    public enum Step {
        /** Not riding. */
        NONE,
        /** The ridden player is there: show them. */
        FOLLOW,
        /** Gone while playback is not settled (a seek's rebuild or catch-up): keep the view, wait. */
        WAIT,
        /** Gone: the view becomes the free camera at {@link #lastPose}. */
        LEAVE
    }

    private final List<Entry> entries = new ArrayList<Entry>();
    private boolean sorted = true;
    private int poseId = -1;
    private boolean poseValid;
    private final double[] pose = new double[5];

    /** Empties the list before a refill. */
    public void clear() {
        entries.clear();
        sorted = true;
    }

    /**
     * Adds a player.
     *
     * @param team          the team's name, or null for none
     * @param formattedName the name as its name tag shows it (team prefix and suffix), or null
     */
    public void add(int entityId, String name, String team, String formattedName) {
        entries.add(new Entry(entityId, name == null ? "" : name, team, colour(formattedName)));
        sorted = false;
    }

    private List<Entry> list() {
        if (!sorted) {
            Collections.sort(entries, ORDER);
            sorted = true;
        }
        return entries;
    }

    public int size() {
        return entries.size();
    }

    /** Player {@code i} in menu order. */
    public Entry get(int i) {
        return list().get(i);
    }

    /** The menu index of the player with entity id {@code entityId}, or -1. */
    public int indexOf(int entityId) {
        List<Entry> l = list();
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).entityId == entityId) return i;
        }
        return -1;
    }

    public boolean contains(int entityId) {
        return indexOf(entityId) >= 0;
    }

    /**
     * Key 3 ({@code direction} > 0) or Shift+3 (< 0) from the ridden player {@code riddenId} (-1 when not riding).
     *
     * @return the entity id to ride, or -1 when nobody is listed
     */
    public int cycle(int riddenId, int direction) {
        int n = size();
        if (n == 0) return -1;
        int i = indexOf(riddenId);
        int step = direction < 0 ? -1 : 1;
        int next = i < 0 ? (step > 0 ? 0 : n - 1) : Math.floorMod(i + step, n);
        return get(next).entityId;
    }

    /**
     * The ridden view's frame (D4). {@code present}: the ridden player is in the world, at the given eye pose;
     * {@code settled}: playback is watching, not rebuilding or catching up.
     */
    public Step follow(int riddenId, boolean present, boolean settled, double x, double eyeY, double z, float yaw,
                       float pitch) {
        if (riddenId < 0) {
            poseId = -1;
            poseValid = false;
            return Step.NONE;
        }
        if (riddenId != poseId) {
            poseId = riddenId;
            poseValid = false;
        }
        if (present) {
            pose[0] = x;
            pose[1] = eyeY;
            pose[2] = z;
            pose[3] = yaw;
            pose[4] = pitch;
            poseValid = true;
            return Step.FOLLOW;
        }
        return settled ? Step.LEAVE : Step.WAIT;
    }

    /** {@link #follow} for a frame in which the ridden player is not in the world. */
    public Step absent(int riddenId, boolean settled) {
        return follow(riddenId, false, settled, 0, 0, 0, 0, 0);
    }

    /**
     * The last pose the view showed of {@code riddenId}: {x, eyeY, z, yaw, pitch} into {@code out}.
     *
     * @return false if that player was never shown since it was ridden (then the camera stays where it is)
     */
    public boolean lastPose(int riddenId, double[] out) {
        if (!poseValid || riddenId != poseId) return false;
        System.arraycopy(pose, 0, out, 0, 5);
        return true;
    }

    /** The first colour code ({@code §0}-{@code §f}) of a formatted name as ARGB; {@link #WHITE} for none. */
    public static int colour(String formattedName) {
        if (formattedName == null) return WHITE;
        for (int i = 0; i + 1 < formattedName.length(); i++) {
            if (formattedName.charAt(i) == '§') {
                int c = Character.digit(formattedName.charAt(i + 1), 16);
                if (c >= 0) return 0xFF000000 | CODE_RGB[c];
            }
        }
        return WHITE;
    }
}
