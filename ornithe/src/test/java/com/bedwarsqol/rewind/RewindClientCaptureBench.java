package com.bedwarsqol.rewind;

import com.bedwarsqol.hud.TextModules;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ResourceLocation;
import io.netty.buffer.Unpooled;
import org.junit.After;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertTrue;

/**
 * Opt-in cost of the format v2 client-thread capture (A12, A13), through the production wiring: {@link Rewind}'s
 * sink over the TRACK producer of an open game, the real writer in step mode draining it between bursts (outside the
 * measured span). Reports the mean µs and thread-allocated bytes per tick (runTick head + end of tick), per frame
 * (view capture), per HUD frame (the painters' hand-offs with freshly built equal values), per outbound packet
 * (untagged, and C0E carrying a named item), and the named allocating cases: an INV write of a tagged stack (NBT),
 * a deep tag compare, a first-seen sound name and the pieces of vanilla's FOV read that allocate.
 *
 * <p>Skipped unless the test JVM runs with {@code -Dcobblify.bench=true} (pass it through {@code JAVA_TOOL_OPTIONS}).
 * The numbers mean something only on a quiet machine. What it cannot run without a client: the Minecraft field reads
 * of the static entry points and the full {@code EntityRenderer.getFOVModifier} call (its allocating pieces are
 * measured on their own: the eye-block lookup on a test world, Zoom's callback info and a boxed return value).
 */
public class RewindClientCaptureBench {

    private static final int BURST = 2_000;
    private static final int BURSTS = 50;
    private static final int WARM_BURSTS = 20;
    /** A13 budget. */
    private static final double TICK_BUDGET_US = 20.0;
    private static final double FRAME_BUDGET_US = 1.0;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @BeforeClass
    public static void bootstrap() {
        InventoryCaptureTest.bootstrap();
    }

    @After
    public void release() {
        RewindIntegrationTest.V2.reset();
    }

    private static final com.sun.management.ThreadMXBean MX =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private static long allocated() {
        return MX.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    interface Op {
        void run(int i);
    }

    static final class Result {
        final String name;
        final double us;
        final double bytes;

        Result(String name, double us, double bytes) {
            this.name = name;
            this.us = us;
            this.bytes = bytes;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%-44s %9.3f us/op %10.1f B/op", name, us, bytes);
        }
    }

    private final List<Result> results = new ArrayList<Result>();
    private RewindIntegrationTest.Harness h;
    private int counter;

    /** Mean over {@link #BURSTS} measured bursts after {@link #WARM_BURSTS}; the writer drains between bursts. */
    private Result measure(String name, Op op, int perBurst) {
        long ns = 0;
        long bytes = 0;
        long ops = 0;
        for (int b = 0; b < WARM_BURSTS + BURSTS; b++) {
            long a0 = allocated();
            long t0 = System.nanoTime();
            for (int i = 0; i < perBurst; i++) {
                op.run(counter++);
            }
            long t1 = System.nanoTime();
            long a1 = allocated();
            if (b >= WARM_BURSTS) {
                ns += t1 - t0;
                bytes += a1 - a0;
                ops += perBurst;
            }
            h.advance(50_000L * perBurst);
            h.step();
        }
        Result r = new Result(name, ns / 1000.0 / ops, bytes / (double) ops);
        results.add(r);
        return r;
    }

    @Test(timeout = 1_800_000)
    public void clientCaptureCostPerTickFrameAndPacket() throws Exception {
        Assume.assumeTrue("set -Dcobblify.bench=true to run", Boolean.getBoolean("cobblify.bench"));
        h = RewindIntegrationTest.openGame(tmp.newFolder("rewind"));
        final RewindIntegrationTest.V2 v = new RewindIntegrationTest.V2();
        v.rounds(h, 0, 3); // segment start: IDENTITY, SETTINGS, all of INV written
        for (int i = 9; i < 36; i += 3) {
            if (v.inv.mainInventory[i].getItem().isDamageable()) {
                v.inv.mainInventory[i].addEnchantment(Enchantment.unbreaking, 1); // tagged stacks to compare
            }
        }
        final BlockPos digAt = new BlockPos(5, 63, 5);

        // ---- per tick: runTick head (TICK_HEAD, HUD head) + end of tick (DIG, SETTINGS, INV, USE, CONT)
        final Rewind.TickCapture steady = new Rewind.TickCapture() {
            @Override
            public void endOfTick() {
                ActionCapture.dig(false, digAt, 1, 0f);
                v.snap.read(v.gs, 480, 270, 1920, 1080, v.cfg);
                StateCapture.tick(0, v.inv, v.own, v.own, v.snap, v.profile);
            }
        };
        Result tick = measure("tick, state unchanged (deep compare every 20)", new Op() {
            @Override
            public void run(int i) {
                h.rewind.captureTickHead();
                h.rewind.captureTick(steady);
            }
        }, BURST);
        Result headOnly = measure("tick head only (TICK_HEAD, HUD head)", new Op() {
            @Override
            public void run(int i) {
                h.rewind.captureTickHead();
            }
        }, BURST);
        final StateCapture every20 = new StateCapture(StateCapture.DEEP_COMPARE_TICKS);
        final StateCapture never = new StateCapture(0);
        final ClientTrackCaptureTest.Counting counted = new ClientTrackCaptureTest.Counting();
        every20.tick(counted, 0, v.inv, v.own, v.own, null, null);
        never.tick(counted, 0, v.inv, v.own, v.own, null, null);
        Result stateNoDeep = measure("end-of-tick state, deep compare off", new Op() {
            @Override
            public void run(int i) {
                never.tick(counted, 0, v.inv, v.own, v.own, null, null);
            }
        }, BURST);
        Result stateDeep20 = measure("end-of-tick state, deep compare 1 in 20", new Op() {
            @Override
            public void run(int i) {
                every20.tick(counted, 0, v.inv, v.own, v.own, null, null);
            }
        }, BURST);
        final int[] tickNo = new int[1];
        final Rewind.TickCapture changing = new Rewind.TickCapture() {
            @Override
            public void endOfTick() {
                int i = tickNo[0];
                ActionCapture.dig(true, digAt, 1, (i % 10) / 10f + 0.01f);
                v.inv.mainInventory[2].stackSize = 1 + i % 7;
                v.inv.mainInventory[1].setItemDamage(i % 50);
                v.snap.read(v.gs, 480, 270, 1920, 1080, v.cfg);
                StateCapture.tick(i % 40 == 0 ? 72000 : 0, v.inv, v.own, v.own, v.snap, v.profile);
            }
        };
        Result tickChanging = measure("tick, counts, damage, dig stage, use change", new Op() {
            @Override
            public void run(int i) {
                tickNo[0] = i;
                h.rewind.captureTickHead();
                h.rewind.captureTick(changing);
            }
        }, BURST);

        // ---- per frame: view capture (VIEW, FOV, INPUT; MOUSE and CHAT_INPUT only with a screen)
        final ViewCapture.Frame f = v.frame;
        final long[] ns = {5_000_000_000L};
        final Rewind.FrameCapture view = new Rewind.FrameCapture() {
            @Override
            public void frame(float partialTicks, long nanoTime) {
                f.nanoTime = nanoTime;
                ViewCapture.frame(f);
            }
        };
        f.perspective = 0;
        f.flags = 0;
        f.screenKind = RewindFormat.SCREEN_NONE;
        f.keys = 0;
        f.fovRead = true;
        f.fov = 70f;
        f.screenOpen = false;
        f.chatOpen = false;
        f.chatText = null;
        Result frame = measure("frame, view unchanged", new Op() {
            @Override
            public void run(int i) {
                ns[0] += 7_000_000L;
                h.rewind.captureFrame(view, 0.5f, ns[0]);
            }
        }, BURST);
        Result frameChanging = measure("frame, keys and FOV change", new Op() {
            @Override
            public void run(int i) {
                ns[0] += 7_000_000L;
                f.keys = i & 0x1F;
                f.fov = 70f + (i & 7);
                h.rewind.captureFrame(view, 0.5f, ns[0]);
            }
        }, BURST);
        f.screenOpen = true;
        f.screenKind = RewindFormat.SCREEN_CHEST;
        Result frameScreen = measure("frame, screen open, mouse moving", new Op() {
            @Override
            public void run(int i) {
                ns[0] += 7_000_000L;
                f.mouseDx = i & 255;
                f.mouseDy = -(i & 127);
                h.rewind.captureFrame(view, 0.5f, ns[0]);
            }
        }, BURST);
        f.screenOpen = false;
        f.screenKind = RewindFormat.SCREEN_NONE;

        // ---- HUD per frame: the painters' hand-offs, values freshly built and equal (the head runs every 3 frames)
        Result hud = measure("HUD hand-offs per frame, equal fresh values", new Op() {
            @Override
            public void run(int i) {
                HudCapture c = HudCapture.shared();
                if (i % 3 == 0) {
                    c.tickHead();
                }
                c.frame();
                c.offerTimer(RewindFormat.HUD_DIAMOND_TIMER, 21);
                c.offerShown(RewindFormat.HUD_ARMOR);
                c.offerRuns(RewindFormat.HUD_FPS, FPS_RUNS);
            }
        }, BURST);

        // ---- per outbound packet
        final List<Packet<?>> plain = new ArrayList<Packet<?>>();
        Packet<?> tagged = null;
        for (Packet<?> p : v.nine) {
            if (OutboundCaptureTest.serverboundId(p) == 0x0E) {
                tagged = p;
            } else {
                plain.add(p);
            }
        }
        final Packet<?> c0e = tagged;
        Result out = measure("outbound packet, no NBT (8 kinds)", new Op() {
            @Override
            public void run(int i) {
                ActionCapture.onSend(plain.get(i & 7));
            }
        }, BURST);
        Result outNbt = measure("outbound C0E carrying a named item (NBT)", new Op() {
            @Override
            public void run(int i) {
                ActionCapture.onSend(c0e);
            }
        }, BURST / 4);

        // ---- named allocating cases
        final ItemStack named = new ItemStack(Items.diamond_sword);
        named.setStackDisplayName("Named");
        v.inv.mainInventory[3] = named;
        final Rewind.TickCapture nbtChange = new Rewind.TickCapture() {
            @Override
            public void endOfTick() {
                named.setItemDamage(tickNo[0] % 100);
                v.snap.read(v.gs, 480, 270, 1920, 1080, v.cfg);
                StateCapture.tick(0, v.inv, v.own, v.own, v.snap, v.profile);
            }
        };
        Result tickNbt = measure("tick with a tagged stack changed (INV + NBT)", new Op() {
            @Override
            public void run(int i) {
                tickNo[0] = i;
                h.rewind.captureTickHead();
                h.rewind.captureTick(nbtChange);
            }
        }, BURST / 4);
        final StateCapture deepEvery = new StateCapture(1);
        final ClientTrackCaptureTest.Counting count = new ClientTrackCaptureTest.Counting();
        deepEvery.tick(count, 0, v.inv, v.own, v.own, null, null);
        Result deep = measure("deep tag compare tick (unchanged, tagged slots)", new Op() {
            @Override
            public void run(int i) {
                deepEvery.tick(count, 0, v.inv, v.own, v.own, null, null);
            }
        }, BURST);
        final ResourceLocation[] fresh = new ResourceLocation[(WARM_BURSTS + BURSTS) * 200];
        for (int i = 0; i < fresh.length; i++) {
            fresh[i] = new ResourceLocation("bench.sound" + i);
        }
        final int[] next = new int[1];
        Result firstSound = measure("first-seen sound name", new Op() {
            @Override
            public void run(int i) {
                ResourceLocation name = fresh[next[0]++];
                ActionCapture.enterLocal();
                ActionCapture.onSound(LocalSoundCaptureTest.sound(name, false, ISound.AttenuationType.LINEAR, 0, 0, 0));
                ActionCapture.exitLocal();
            }
        }, 200);
        final ISound known = LocalSoundCaptureTest.sound(new ResourceLocation("dig.stone"), false,
                ISound.AttenuationType.LINEAR, 0, 64, 0);
        Result knownSound = measure("known sound (own scope, sound object reused)", new Op() {
            @Override
            public void run(int i) {
                ActionCapture.enterLocal();
                ActionCapture.onSound(known);
                ActionCapture.exitLocal();
            }
        }, BURST);
        final EntityItem eye = new EntityItem(v.world, 0.5, 70.0, 0.5);
        final float[] sinkF = new float[1];
        Result fov = measure("FOV read pieces (eye block + Zoom CIR + box)", new Op() {
            @Override
            public void run(int i) {
                ActiveRenderInfo.getBlockAtEntityViewpoint(v.world, eye, 0.5f);
                CallbackInfoReturnable<Float> cir = new CallbackInfoReturnable<Float>("getFOVModifier", true, 70f);
                cir.setReturnValue(70f / (1 + (i & 3)));
                sinkF[0] += cir.getReturnValueF();
            }
        }, BURST);
        PacketBuffer nbtBuf = new PacketBuffer(Unpooled.buffer(256));
        final PacketBuffer nb = nbtBuf;
        Result nbtWrite = measure("vanilla writeItemStackToBuffer, named item", new Op() {
            @Override
            public void run(int i) {
                nb.clear();
                nb.writeItemStackToBuffer(named);
            }
        }, BURST);

        assertTrue("capture is still on (no failure during the bench)", h.rewind.capturing());
        StringBuilder sb = new StringBuilder("RewindClientCaptureBench (budget: tick <= ")
                .append(TICK_BUDGET_US).append(" us, frame <= ").append(FRAME_BUDGET_US).append(" us)\n");
        for (Result r : results) {
            sb.append("  ").append(r).append('\n');
        }
        sb.append(String.format(Locale.ROOT, "  FOV read (at most one per frame and one per 4 ms): %.1f B per read, "
                + "so at most %.1f KB/s (250 reads/s); %.1f B per frame up to 250 fps%n", fov.bytes,
                fov.bytes * 250 / 1024.0, fov.bytes));
        System.out.print(sb);
        assertTrue("tick over budget: " + tick, tick.us <= TICK_BUDGET_US);
        assertTrue("changing tick over budget: " + tickChanging, tickChanging.us <= TICK_BUDGET_US);
        assertTrue("frame over budget: " + frame, frame.us <= FRAME_BUDGET_US);
        assertTrue("changing frame over budget: " + frameChanging, frameChanging.us <= FRAME_BUDGET_US);
        // keep the remaining results referenced (printed above)
        assertTrue(headOnly.us >= 0 && stateNoDeep.us >= 0 && stateDeep20.us >= 0);
        assertTrue(frameScreen.us >= 0 && hud.us >= 0 && out.us >= 0 && outNbt.us >= 0 && tickNbt.us >= 0
                && deep.us >= 0 && firstSound.us >= 0 && knownSound.us >= 0 && nbtWrite.us >= 0 && sinkF[0] != 0);
    }

    private static final List<TextModules.Seg> FPS_RUNS = TextModules.fps(144);
}
