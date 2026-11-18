package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.RewindFormat;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The controller's game type per view (PLAN section 4.1, Phase 4c): your view follows the recorded game mode, from S01,
 * S07 and S2B reason 3 in their order of arrival (so the spectator bar shows after a final death); the free camera is
 * always SPECTATOR (no hand, hotbar or hearts); a view switch changes it at once; and a seek, which replays the feed,
 * lands on the mode recorded at the target. The recorded packets reach it through the packet policy and the feeder.
 */
public class ViewGameTypeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] join(int gameType, boolean hardcore) {
        byte[] b = RecordingFixture.joinGame(0).clone();
        b[5] = (byte) (gameType | (hardcore ? 8 : 0));
        return b;
    }

    private static byte[] respawn(int dimension, int gameType) {
        byte[] b = RecordingFixture.respawn(dimension).clone();
        b[6] = (byte) gameType;
        return b;
    }

    private static byte[] gameMode(float value) {
        byte[] b = new byte[6];
        b[0] = 0x2B;
        b[1] = 3;
        RewindFormat.putFloat(b, 2, value);
        return b;
    }

    @Test
    public void yourViewFollowsTheRecordedModeAndTheFreeCameraIsSpectator() {
        ViewGameType v = new ViewGameType();
        assertEquals("nothing recorded yet: survival", ViewGameType.SURVIVAL, v.forView(true));
        assertEquals(ViewGameType.SPECTATOR, v.forView(false));

        assertTrue(v.onPacket(join(ViewGameType.ADVENTURE, true), 0, 18));
        assertEquals("the hardcore bit is not a mode", ViewGameType.ADVENTURE, v.recorded());
        assertEquals(ViewGameType.ADVENTURE, v.forView(true));
        assertEquals(ViewGameType.SPECTATOR, v.forView(false));

        byte[] r = respawn(0, ViewGameType.CREATIVE);
        assertTrue(v.onPacket(r, 0, r.length));
        assertEquals(ViewGameType.CREATIVE, v.forView(true));

        assertTrue(v.onPacket(gameMode(0f), 0, 6));
        assertEquals(ViewGameType.SURVIVAL, v.forView(true));
        assertTrue(v.onPacket(gameMode(3f), 0, 6));
        assertEquals("a final death: the spectator bar", ViewGameType.SPECTATOR, v.forView(true));
        assertTrue(v.onPacket(gameMode(1.6f), 0, 6));
        assertEquals("vanilla rounds the float", ViewGameType.ADVENTURE, v.forView(true));

        // A view switch is just the other argument: it changes at once, with nothing to replay.
        assertEquals(ViewGameType.SPECTATOR, v.forView(false));
        assertEquals(ViewGameType.ADVENTURE, v.forView(true));

        // Other packets and other S2B reasons leave the mode alone.
        assertFalse(v.onPacket(new byte[]{0x2B, 1, 0, 0, 0, 0}, 0, 6));
        assertFalse(v.onPacket(new byte[]{0x02, 0, 0}, 0, 3));
        assertEquals(ViewGameType.ADVENTURE, v.recorded());

        v.reset();
        assertEquals(ViewGameType.NOT_SET, v.recorded());
    }

    @Test
    public void unknownModesReadAsSurvivalAsVanillaDoes() {
        ViewGameType v = new ViewGameType();
        byte[] r = respawn(0, 7);
        v.onPacket(r, 0, r.length);
        assertEquals(ViewGameType.SURVIVAL, v.recorded());
        v.onPacket(gameMode(-5f), 0, 6);
        assertEquals(ViewGameType.SURVIVAL, v.recorded());
    }

    /** A sink that keeps only the recorded mode, as the Ornithe sink does. */
    private static final class ModeSink implements PacketSink {
        final ViewGameType v = new ViewGameType();
        int hooks;

        @Override
        public boolean apply(int packetId, byte[] buf, int off, int len) {
            return true;
        }

        @Override
        public void boundary(int packetId, byte[] buf, int off, int len) {
            v.onPacket(buf, off, len);
        }

        @Override
        public boolean sessionHook(int packetId, byte[] buf, int off, int len) {
            hooks++;
            return v.onPacket(buf, off, len);
        }

        @Override
        public void sample(long tick, long timeUs) {
        }
    }

    @Test
    public void theFeedDeliversTheModesInOrderAndASeekRestoresTheModeAtTheTarget() throws Exception {
        long o = 3_000_000L;
        long s = 1_000_000L;
        TickFeederTest.Rec rec = new TickFeederTest.Rec()
                .segment(1, new byte[][]{join(ViewGameType.SURVIVAL, false)}, TickFeederTest.standardHeader1())
                .at(o, join(ViewGameType.SURVIVAL, false))
                .at(o + s, respawn(0, ViewGameType.ADVENTURE))
                .at(o + 2 * s, gameMode(ViewGameType.CREATIVE))
                .at(o + 3 * s, gameMode(ViewGameType.SPECTATOR))
                .at(o + 4 * s, TickFeederTest.p(0x02, 1));
        LoadedRecording r = rec.load(tmp.newFolder(), o, o + 5 * s);
        Timeline tl = r.timeline();
        int[] expected = new int[(int) tl.tickOf(o + 4 * s) + 1];
        for (int k = 0; k < expected.length; k++) {
            long t = tl.timeOfTick(k);
            expected[k] = t >= o + 3 * s ? ViewGameType.SPECTATOR : t >= o + 2 * s ? ViewGameType.CREATIVE
                    : t >= o + s ? ViewGameType.ADVENTURE : ViewGameType.SURVIVAL;
        }

        TickFeeder straight = new TickFeeder(r);
        ModeSink sink = new ModeSink();
        for (int k = 0; k < expected.length; k++) {
            straight.feedForTick(k, sink);
            assertEquals("tick " + k, expected[k], sink.v.forView(true));
            assertEquals(ViewGameType.SPECTATOR, sink.v.forView(false));
        }
        assertEquals("both S2B reason 3 records went to the session hook", 2, sink.hooks);

        // A backward seek: a fresh connection (a fresh sink) and a replay from the start to the target.
        for (int target : new int[]{0, (int) tl.tickOf(o + s), (int) tl.tickOf(o + 2 * s) + 3, expected.length - 1}) {
            TickFeeder again = new TickFeeder(r);
            ModeSink fresh = new ModeSink();
            for (int k = 0; k <= target; k++) {
                again.feedForTick(k, fresh);
            }
            assertEquals("seek to tick " + target, expected[target], fresh.v.forView(true));
        }
    }
}
