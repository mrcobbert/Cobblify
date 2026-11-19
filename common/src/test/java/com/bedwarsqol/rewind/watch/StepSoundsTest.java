package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import com.bedwarsqol.rewind.SelfTrackEncoder;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Derived footsteps (PLAN Phase 8, B7): vanilla's step rule from {@code Entity.moveEntity} (javap @1442-1905) over
 * the recorded player's per-tick movement. The step distance grows by {@code sqrt(dx^2 + dy^2 + dz^2) x 0.6} (dy only
 * when the block under the feet is a ladder), not while sneaking on the ground, riding or without collision
 * (spectator); a step plays when it passes {@code nextStepDistance} (1 at first, then {@code (int) distance + 1}) and
 * the block at {@code (floor x, floor(y - 0.2), floor z)} is not air (a fence, wall or gate under an air block
 * counts). A recording with stored SOUND records (format v2) carries its real footsteps, so nothing is derived.
 */
public class StepSoundsTest {

    private static final long T = 1_000_000L;
    private static final long TICK = Timeline.TICK_US;
    private static final int GROUND = RewindFormat.FLAG_ON_GROUND;

    /** A flat floor: solid at y = 63, air everywhere else. */
    private static final StepSounds.Blocks FLOOR = (x, y, z) -> y == 63 ? StepSounds.SOLID : StepSounds.AIR;
    private static final StepSounds.Blocks NOTHING = (x, y, z) -> StepSounds.AIR;

    /**
     * Moves 1..count by {@code (dx, dy, dz)} each from {@code (x0, y0, z0)} (the first call only sets the start) and
     * returns the move numbers that stepped.
     */
    private static List<Integer> walk(StepSounds s, double x0, double y0, double z0, double dx, double dy, double dz,
                                      int count, int flags, boolean noClip, StepSounds.Blocks world) {
        List<Integer> steps = new ArrayList<Integer>();
        s.move(x0, y0, z0, flags, noClip, world);
        for (int i = 1; i <= count; i++) {
            if (s.move(x0 + dx * i, y0 + dy * i, z0 + dz * i, flags, noClip, world)) {
                steps.add(i);
            }
        }
        return steps;
    }

    /** 0.3 blocks per tick adds 0.18 per tick: it passes 1, 2, ..., 8 at these moves (0.18 x n is never whole). */
    private static final List<Integer> WALK_STEPS = Arrays.asList(6, 12, 17, 23, 28, 34, 39, 45);

    /** The recorded track: one TICK every 50 ms walking +0.3 x on the floor, flags as given. */
    private static SelfTrack track(int ticks, int flags) {
        SelfTrackEncoder enc = new SelfTrackEncoder();
        SelfTrack.Builder b = new SelfTrack.Builder().segment(1);
        for (int i = 0; i < ticks; i++) {
            int n = enc.tick(0.5 + 0.3 * i, 64, 0.5, 10f, 5f, 10f, flags, 0, -1, 0);
            assertTrue(n > 0);
            b.record(T + i * TICK, RewindFormat.TAG_TICK, enc.buffer(), 0, n);
        }
        return b.build();
    }

    private static List<Integer> playTrack(StepSounds s, SelfTrack track, ExtRecords ext, int ticks) {
        TickTable table = TickTable.forRecording(T - 1_000, track, ext);
        List<Integer> steps = new ArrayList<Integer>();
        for (int k = 0; k < ticks; k++) {
            if (s.tick(track, table, k, false, FLOOR)) {
                steps.add(k);
            }
        }
        return steps;
    }

    private static ExtRecords withSound() {
        ExtCodec c = new ExtCodec();
        int n = c.sound("minecraft:step.stone", 0.5f, 64f, 0.5f, 0.15f, 1f);
        return new ExtRecords.Builder().addEncoded(0, T + 3 * TICK, RewindFormat.TAG_SOUND, c.buffer(), n).build();
    }

    private static ExtRecords withHeadsOnly() {
        ExtCodec c = new ExtCodec();
        ExtRecords.Builder b = new ExtRecords.Builder();
        for (int i = 0; i < 60; i++) {
            int n = c.tickHead(1);
            b.addEncoded(0, T - 500 + i * TICK, RewindFormat.TAG_TICK_HEAD, c.buffer(), n);
        }
        return b.build();
    }

    @Test
    public void walkingARecordedTrackStepsWhereVanillaWould() {
        StepSounds s = new StepSounds();
        s.forRecording(ExtRecords.EMPTY);
        assertEquals("a step every 1/0.6 blocks, from the recorded positions", WALK_STEPS,
                playTrack(s, track(50, GROUND), ExtRecords.EMPTY, 50));
    }

    @Test
    public void theStepSoundComesFromTheBlockUnderTheFeet() {
        StepSounds s = new StepSounds();
        assertEquals(WALK_STEPS.subList(0, 1), walk(s, 0.5, 64, 0.5, 0.3, 0, 0, 6, GROUND, false, FLOOR));
        assertEquals("floor(x)", 2, s.blockX()); // 0.5 + 1.8
        assertEquals("floor(y - 0.2)", 63, s.blockY());
        assertEquals("floor(z)", 0, s.blockZ());
    }

    @Test
    public void sneakingOnTheGroundAddsNoDistance() {
        StepSounds s = new StepSounds();
        assertEquals(new ArrayList<Integer>(),
                walk(s, 0.5, 64, 0.5, 0.3, 0, 0, 50, GROUND | RewindFormat.FLAG_SNEAKING, false, FLOOR));
        // Standing up again: the distance starts from where it was (0), so the first step is 6 moves later.
        assertEquals(WALK_STEPS.subList(0, 2), walk(s, 15.5, 64, 0.5, 0.3, 0, 0, 12, GROUND, false, FLOOR));
    }

    @Test
    public void theSneakGateReadsTheGroundFlagBeforeTheMove() {
        // Vanilla tests onGround before moveEntity moves: landing while sneaking still counts that tick.
        StepSounds s = new StepSounds();
        s.move(0.5, 64, 0.5, RewindFormat.FLAG_SNEAKING, false, FLOOR);
        for (int i = 1; i <= 5; i++) {
            assertFalse(s.move(0.5 + 0.3 * i, 64, 0.5, RewindFormat.FLAG_SNEAKING, false, FLOOR)); // 0.9
        }
        assertTrue("in the air before this move: counts (1.08)",
                s.move(2.3, 64, 0.5, GROUND | RewindFormat.FLAG_SNEAKING, false, FLOOR));
        assertEquals("on the ground before the move and sneaking: nothing more", new ArrayList<Integer>(),
                walk(s, 2.3, 64, 0.5, 0.3, 0, 0, 20, GROUND | RewindFormat.FLAG_SNEAKING, false, FLOOR));
    }

    @Test
    public void sneakingInTheAirStillCountsAsVanillaOnlyGatesSneakingOnTheGround() {
        StepSounds s = new StepSounds();
        assertEquals(WALK_STEPS, walk(s, 0.5, 64, 0.5, 0.3, 0, 0, 50, RewindFormat.FLAG_SNEAKING, false, FLOOR));
    }

    @Test
    public void airBelowPlaysNothingAndTheFirstBlockAfterItStepsAtOnce() {
        StepSounds s = new StepSounds();
        assertEquals("air below: none", new ArrayList<Integer>(),
                walk(s, 0.5, 64, 0.5, 0.3, 0, 0, 20, GROUND, false, NOTHING));
        // 20 x 0.18 = 3.6 > 1 already: the first move over a block steps (3.78), then the next is at > 4.
        assertTrue("the first move over a block steps at once", s.move(6.8, 64, 0.5, GROUND, false, FLOOR));
        assertEquals("next step at > 4 (3.78 + 0.18 n)", Arrays.asList(2),
                walk(s, 6.8, 64, 0.5, 0.3, 0, 0, 3, GROUND, false, FLOOR));
    }

    @Test
    public void aFenceUnderAnAirBlockCountsAndItIsTheBlockThatSounds() {
        // Standing on a fence top (1.5 high) at y = 63.5: y - 0.2 is in the air block at 63, the fence is at 62.
        StepSounds.Blocks fence = (x, y, z) -> y == 62 ? StepSounds.FENCE : StepSounds.AIR;
        StepSounds s = new StepSounds();
        assertEquals(WALK_STEPS.subList(0, 1), walk(s, 0.5, 63.5, 0.5, 0.3, 0, 0, 6, GROUND, false, fence));
        assertEquals(62, s.blockY());
        // A solid block under an air block does not count.
        StepSounds.Blocks low = (x, y, z) -> y == 62 ? StepSounds.SOLID : StepSounds.AIR;
        assertEquals(new ArrayList<Integer>(),
                walk(new StepSounds(), 0.5, 63.5, 0.5, 0.3, 0, 0, 20, GROUND, false, low));
    }

    @Test
    public void onALadderTheVerticalDistanceCounts() {
        StepSounds.Blocks ladder = (x, y, z) -> StepSounds.LADDER;
        StepSounds.Blocks wall = (x, y, z) -> StepSounds.SOLID;
        // Climbing 0.2 per tick adds 0.12: passes 1 at move 9 (1.08), 2 at 17 (2.04).
        assertEquals(Arrays.asList(9, 17),
                walk(new StepSounds(), 0.5, 64, 0.5, 0, 0.2, 0, 20, 0, false, ladder));
        assertEquals("vertical movement elsewhere adds nothing", new ArrayList<Integer>(),
                walk(new StepSounds(), 0.5, 64, 0.5, 0, 0.2, 0, 20, 0, false, wall));
    }

    @Test
    public void ridingAndSpectatorPlayNothing() {
        assertEquals(new ArrayList<Integer>(),
                walk(new StepSounds(), 0.5, 64, 0.5, 0.3, 0, 0, 50, GROUND | RewindFormat.FLAG_RIDING, false, FLOOR));
        assertEquals("no collision (spectator): vanilla returns before the step rule", new ArrayList<Integer>(),
                walk(new StepSounds(), 0.5, 64, 0.5, 0.3, 0, 0, 50, GROUND, true, FLOOR));
    }

    @Test
    public void aTeleportIsNotAWalk() {
        StepSounds s = new StepSounds();
        assertEquals(new ArrayList<Integer>(), walk(s, 0.5, 64, 0.5, 0.3, 0, 0, 5, GROUND, false, FLOOR)); // 0.9
        assertFalse("30 blocks in one tick (S08, respawn) adds nothing", s.move(32, 64, 0.5, GROUND, false, FLOOR));
        assertEquals("the walk goes on from 0.9", Arrays.asList(1),
                walk(s, 32, 64, 0.5, 0.3, 0, 0, 1, GROUND, false, FLOOR));
    }

    @Test
    public void resetStartsAgainLikeANewPlayer() {
        StepSounds s = new StepSounds();
        walk(s, 0.5, 64, 0.5, 0.3, 0, 0, 5, GROUND, false, FLOOR);
        s.reset();
        assertFalse("the first move after a reset only sets the start", s.move(100, 64, 0.5, GROUND, false, FLOOR));
        assertEquals(WALK_STEPS.subList(0, 1), walk(s, 100, 64, 0.5, 0.3, 0, 0, 6, GROUND, false, FLOOR));
    }

    @Test
    public void storedSoundsTurnDerivationOff() {
        assertFalse(StepSounds.storedSounds(ExtRecords.EMPTY));
        assertFalse(StepSounds.storedSounds(null));
        assertFalse("v2 without SOUND records", StepSounds.storedSounds(withHeadsOnly()));
        assertTrue(StepSounds.storedSounds(withSound()));

        StepSounds s = new StepSounds();
        s.forRecording(withSound());
        assertFalse(s.enabled());
        assertEquals("v2 with stored footsteps: never both", new ArrayList<Integer>(),
                playTrack(s, track(50, GROUND), ExtRecords.EMPTY, 50));
        assertEquals(new ArrayList<Integer>(), walk(s, 0.5, 64, 0.5, 0.3, 0, 0, 50, GROUND, false, FLOOR));

        s.forRecording(ExtRecords.EMPTY);
        assertTrue(s.enabled());
        assertEquals("v1 again: derived", WALK_STEPS, playTrack(s, track(50, GROUND), ExtRecords.EMPTY, 50));
    }

    @Test
    public void aV2TableWithoutSoundsStillDerives() {
        StepSounds s = new StepSounds();
        ExtRecords ext = withHeadsOnly();
        s.forRecording(ext);
        assertTrue(s.enabled());
        List<Integer> steps = playTrack(s, track(50, GROUND), ext, 50);
        assertEquals(WALK_STEPS.size(), steps.size());
    }
}
