package com.bedwarsqol.rewind;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Assume;
import org.junit.Test;

import static com.bedwarsqol.rewind.CaptureSessionTest.other;
import static com.bedwarsqol.rewind.CaptureSessionTest.s01;
import static org.junit.Assert.assertFalse;

/**
 * Opt-in timing of the capture handler (PLAN "Overhead", measured by (2)): 1M mixed-size heap frames
 * through the vanilla-named pipeline with the handler on and off, a writer stand-in freeing chunks on
 * another thread, and the mean ns/packet of each. Skipped unless the test JVM runs with
 * {@code -Dcobblify.bench=true}; the build does not forward Gradle's own {@code -D} flags to the test
 * JVM, so pass it through {@code JAVA_TOOL_OPTIONS}. The number is only meaningful on an idle machine.
 */
public class RewindCaptureBench {

    private static final int FRAMES = 1_000_000;

    @Test(timeout = 600_000)
    public void handlerOnVersusOff() throws Exception {
        Assume.assumeTrue("set -Dcobblify.bench=true to run", Boolean.getBoolean("cobblify.bench"));
        ByteBuf[] pool = new ByteBuf[4096];
        for (int i = 0; i < pool.length; i++) {
            int size = i % 16 == 0 ? 4000 + (i * 131) % 12000 : 6 + (i * 7919) % 400;
            pool[i] = Unpooled.wrappedBuffer(other(RewindCaptureTest.plainId(i), size));
        }
        RecordChunk.Producer p = new RecordChunk.Producer(RewindFormat.CHUNK_CAPACITY, 2, 6, 0);
        CaptureSession s = RewindCaptureTest.session(p);
        EmbeddedChannel on = RewindCaptureTest.remotePipeline(new RewindCaptureTest.Counting());
        EmbeddedChannel off = RewindCaptureTest.remotePipeline(new RewindCaptureTest.Counting());
        RewindCapture.install(on, false, s);
        on.pipeline().fireChannelRead(Unpooled.wrappedBuffer(s01(1, 0)));
        RewindCaptureTest.Consumer consumer = new RewindCaptureTest.Consumer(p);
        consumer.start();
        try {
            for (int warm = 0; warm < 3; warm++) {
                time(off, pool);
                time(on, pool);
            }
            long bestOn = Long.MAX_VALUE;
            long bestOff = Long.MAX_VALUE;
            for (int round = 0; round < 5; round++) {
                bestOff = Math.min(bestOff, time(off, pool));
                bestOn = Math.min(bestOn, time(on, pool));
            }
            assertFalse(s.failed());
            System.out.printf("RewindCaptureBench: %d frames, handler on %.1f ns/packet, off %.1f ns/packet, "
                            + "capture cost %.1f ns/packet%n", FRAMES, bestOn / (double) FRAMES,
                    bestOff / (double) FRAMES, (bestOn - bestOff) / (double) FRAMES);
        } finally {
            consumer.stop();
        }
    }

    private static long time(EmbeddedChannel ch, ByteBuf[] pool) {
        ChannelPipeline pipeline = ch.pipeline();
        long t0 = System.nanoTime();
        for (int i = 0; i < FRAMES; i++) {
            pipeline.fireChannelRead(pool[i & pool.length - 1]);
        }
        return System.nanoTime() - t0;
    }
}
