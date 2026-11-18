package com.bedwarsqol.rewind.watch;

import net.minecraft.client.multiplayer.PlayerControllerMP;
import org.junit.Test;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Phase 7 hard-rule gap (TASK A13): vanilla's {@code PlayerControllerMP.syncCurrentPlayItem} (called by
 * {@code updateController} every world tick) sends {@code C09PacketHeldItemChange} whenever the client player's
 * selected slot differs from the last one sent. During playback the camera's slot follows the recording, so it sent
 * C09 into the playback connection. {@code PlayerControllerMPRewindMixin} cancels the method at HEAD while a recording
 * plays (javap of the mapped jar: {@code private void syncCurrentPlayItem()}, its only send is
 * {@code netClientHandler.addToSendQueue(new C09PacketHeldItemChange(currentPlayerItem))}).
 */
public class HeldItemSyncCancelTest {

    private static final String MIXIN = "com.bedwarsqol.mixin.PlayerControllerMPRewindMixin";

    @Test
    public void theVanillaTargetExistsWithTheInjectedShape() throws Exception {
        Method m = PlayerControllerMP.class.getDeclaredMethod("syncCurrentPlayItem");
        assertEquals(void.class, m.getReturnType());
        assertTrue("private in 1.8.9", Modifier.isPrivate(m.getModifiers()));
    }

    @Test
    public void theMixinCancelsTheSyncAtHeadWithRequireOne() throws Exception {
        Class<?> mixin = Class.forName(MIXIN);
        List<Method> found = new ArrayList<Method>();
        for (Method m : mixin.getDeclaredMethods()) {
            Inject inject = m.getAnnotation(Inject.class);
            if (inject == null) continue;
            for (String target : inject.method()) {
                if (target.equals("syncCurrentPlayItem") || target.equals("syncCurrentPlayItem()V")) {
                    found.add(m);
                    At[] at = inject.at();
                    assertEquals(1, at.length);
                    assertEquals("HEAD", at[0].value());
                    assertTrue("cancellable", inject.cancellable());
                    assertEquals("require = 1", 1, inject.require());
                    assertEquals(1, m.getParameterTypes().length);
                    assertEquals(CallbackInfo.class, m.getParameterTypes()[0]);
                }
            }
        }
        assertEquals("one injector on syncCurrentPlayItem", 1, found.size());

        // Its body: cancel when a recording plays, and nothing else.
        File src = new File(PlaybackIsolationScanTest.mainRoot(), "com/bedwarsqol/mixin/PlayerControllerMPRewindMixin.java");
        String text = PlaybackIsolationScanTest.strip(PlaybackIsolationScanTest.read(src));
        Matcher decl = Pattern.compile("void\\s+" + Pattern.quote(found.get(0).getName()) + "\\s*\\(").matcher(text);
        assertTrue(decl.find());
        String body = PlaybackIsolationScanTest.block(text, text.indexOf('{', decl.end()));
        assertTrue("reads the session: " + body, body.contains("RewindPlayback.active()"));
        assertTrue("cancels: " + body, body.matches("(?s).*\\.cancel\\s*\\(\\s*\\).*"));
    }

    @Test
    public void noRecordingPlaysInAUnitTestSoVanillaRuns() {
        assertFalse(RewindPlayback.active());
    }
}
