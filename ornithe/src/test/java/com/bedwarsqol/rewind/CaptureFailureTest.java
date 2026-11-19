package com.bedwarsqol.rewind;

import com.bedwarsqol.rewind.watch.PlaybackGate;
import net.minecraft.client.audio.ISound;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ResourceLocation;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A14, the action half: an exception in a capture hook (here, from the sink) ends action capture for the game, is
 * reported once, and the entry point returns normally, so the hooked vanilla call (send, sound, block change, tick)
 * goes on unchanged. A sink that has no room stops capture without a second report. The next segment re-arms it, and
 * the handled-order ring keeps draining throughout.
 */
public class CaptureFailureTest {

    private static final int A = 0x23;

    private RecordingCaptureSink sink;
    private HandledRing ring;
    private DigCaptureTest.MapWorld world;

    @BeforeClass
    public static void bootstrap() {
        Bootstrap.register();
    }

    @Before
    public void setUp() {
        sink = new RecordingCaptureSink();
        ring = new HandledRing(null);
        ActionCapture.bind(sink, ring, null);
        ActionCapture.startSegment(1);
        world = new DigCaptureTest.MapWorld(true);
    }

    @After
    public void tearDown() {
        ActionCapture.unbind();
        if (PlaybackGate.global().isMuted()) {
            PlaybackGate.global().exit();
        }
    }

    private static final ISound STEP = LocalSoundCaptureTest.sound(new ResourceLocation("step.stone"), false,
            ISound.AttenuationType.LINEAR, 1, 2, 3);

    /** Every capture entry point once; none may throw. */
    private void everyHook(int n) {
        ActionCapture.onRunTickHead();
        ActionCapture.onSend(new C0APacketAnimation());
        ActionCapture.enterLocal();
        ActionCapture.onSound(STEP);
        world.change(new BlockPos(0, 64 + n, 0), Blocks.stone.getDefaultState());
        ActionCapture.exitLocal();
        ActionCapture.dig(true, new BlockPos(0, 60, 0), 1, 0.05f * n);
    }

    @Test
    public void aThrowingSinkIsReportedOnceAndEveryHookReturnsNormally() {
        IllegalStateException boom = new IllegalStateException("sink failure");
        sink.throwOnAppend = boom;
        for (int i = 0; i < 5; i++) {
            everyHook(i);
        }
        assertEquals(1, sink.failures);
        assertSame(boom, sink.lastFailure);
        assertEquals(0, sink.appends);

        // the block change still happened: the hook never changes the hooked call
        assertSame(Blocks.stone.getDefaultState(), world.getBlockState(new BlockPos(0, 64, 0)));

        // the next segment re-arms capture
        sink.throwOnAppend = null;
        ActionCapture.startSegment(2);
        everyHook(9);
        assertEquals(1, sink.failures);
        assertTrue("records again after the re-arm: " + sink.appends, sink.appends >= 5);
    }

    @Test
    public void aThrowFromRecordingItselfIsCaughtToo() {
        ActionCapture.bind(new RecordingCaptureSink() {
            @Override
            public boolean recording() {
                throw new IllegalArgumentException("recording()");
            }
        }, ring, null);
        ActionCapture.startSegment(1);
        everyHook(1); // must not throw
    }

    @Test
    public void aFullSinkStopsCaptureWithoutASecondReport() {
        sink.accept = false;
        everyHook(1);
        assertEquals("only the first append was tried", 1, sink.appends);
        assertEquals("the sink reported its own full producer", 0, sink.failures);
        sink.accept = true;
        ActionCapture.startSegment(2);
        everyHook(2);
        assertTrue(sink.appends > 1);
    }

    @Test
    public void theRingKeepsDrainingAfterAFailure() {
        sink.throwOnAppend = new IllegalStateException("x");
        ActionCapture.onRunTickHead();
        assertEquals(1, sink.failures);
        ring.push(1, 0x01, true, 1);
        ring.push(1, A, true, 2);
        ActionCapture.onPacketRun(0x01);
        ActionCapture.onPacketRun(A);
        assertEquals(0, ring.size());
        assertEquals(2, ring.handled());
        assertEquals(0, ring.resyncs());
    }
}
