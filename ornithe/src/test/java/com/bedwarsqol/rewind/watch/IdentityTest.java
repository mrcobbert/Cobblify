package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.util.ResourceLocation;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Whose body and arm a recording shows (Phase 13, TASK C8; review round 2 I3). Recorded on profile A with a slim skin
 * (an IDENTITY record), watched with session profile B:
 * <ul>
 *   <li>the playback connection is opened with A's profile (so vanilla makes the camera, whose arm the first-person
 *       hand draws, as A), and the puppet is made with A's profile;</li>
 *   <li>the camera and the puppet ask {@link Identity} for their player info: one {@link NetworkPlayerInfo} of A,
 *       shared by both, whose skin type and skin location key vanilla's own texture callback makes A's (slim,
 *       {@code skins/<A's texture hash>}); with a recorded tab entry for A, that entry;</li>
 *   <li>a v1 recording (no IDENTITY) gives B: the session profile, and no answer, so vanilla's own lookup runs.</li>
 * </ul>
 * The skin pipeline below the callback (texture download, cache) is vanilla's and is not run here: a stand-in skin
 * manager decodes the textures property locally, as {@code SkinManager.loadProfileTextures} does, and names the
 * texture as {@code SkinManager.loadSkin} does. No network, no real accounts.
 */
public class IdentityTest {

    private static final UUID A = new UUID(0x0123456789ABCDEFL, 0x1122334455667788L);
    private static final UUID B = new UUID(0x7EDCBA9876543210L, 0x0807060504030201L);
    private static final String HASH_A = "a1b2c3d4e5f6";

    private static String texturesValue(UUID id, String name, String hash, boolean slim) {
        String json = "{\"timestamp\":0,\"profileId\":\"" + id.toString().replace("-", "") + "\",\"profileName\":\""
                + name + "\",\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\""
                + (slim ? ",\"metadata\":{\"model\":\"slim\"}" : "") + "}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** A v2 recording's extension records holding IDENTITY for {@code id}, {@code name} (and a later segment's). */
    private static ExtTrack identity(UUID id, String name, String skin, String sig) {
        ExtCodec c = new ExtCodec();
        ExtRecords.Builder b = new ExtRecords.Builder();
        int n = c.tickHead(0);
        b.addEncoded(0, 1_000L, RewindFormat.TAG_TICK_HEAD, c.buffer(), n);
        n = c.identity(id.getMostSignificantBits(), id.getLeastSignificantBits(), name, skin, sig);
        b.addEncoded(0, 2_000L, RewindFormat.TAG_IDENTITY, c.buffer(), n);
        b.addEncoded(1, 9_000_000L, RewindFormat.TAG_IDENTITY, c.buffer(), n);
        return ExtTrack.of(b.build());
    }

    private static ExtTrack recordedA() {
        return identity(A, "RecordedA", texturesValue(A, "RecordedA", HASH_A, true), "sigA");
    }

    private static final GameProfile SESSION_B = new GameProfile(B, "WatcherB");

    /** What the part reads: the recording's records and the playback connection's tab list. */
    private static final class Fake implements Identity.Source {
        final ExtTrack ext;
        final Map<UUID, NetworkPlayerInfo> tab = new HashMap<UUID, NetworkPlayerInfo>();

        Fake(ExtTrack ext) {
            this.ext = ext;
        }

        @Override
        public ExtTrack ext() {
            return ext;
        }

        @Override
        public NetworkPlayerInfo tab(UUID id) {
            return tab.get(id);
        }
    }

    /** An instance of {@code type} with no constructor run ({@code sun.misc.Unsafe.allocateInstance}). */
    @SuppressWarnings("unchecked")
    static <T> T allocate(Class<T> type) throws Exception {
        Class<?> u = Class.forName("sun.misc.Unsafe");
        Field f = u.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (T) u.getMethod("allocateInstance", Class.class).invoke(f.get(null), type);
    }

    /**
     * A skin manager that does what {@code SkinManager.loadProfileTextures} and {@code loadSkin} do with a profile's
     * signed textures property, minus the thread pool and the download: decode it, then call back with the texture's
     * location {@code skins/<hash>}.
     */
    static final class LocalSkins extends SkinManager {
        static MinecraftSessionService decoder;

        LocalSkins() {
            super(null, null, null); // never run: instances come from allocateInstance
        }

        @Override
        public void loadProfileTextures(GameProfile profile, SkinAvailableCallback callback, boolean requireSecure) {
            Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> map = decoder.getTextures(profile, false);
            MinecraftProfileTexture skin = map.get(MinecraftProfileTexture.Type.SKIN);
            if (skin != null) {
                callback.skinAvailable(MinecraftProfileTexture.Type.SKIN,
                        new ResourceLocation("skins/" + skin.getHash()), skin);
            }
        }
    }

    /** Runs {@code body} with a client whose skin manager is {@link LocalSkins}; puts the old client back. */
    private static void withLocalSkins(Runnable body) throws Exception {
        LocalSkins.decoder = new YggdrasilAuthenticationService(Proxy.NO_PROXY, "").createMinecraftSessionService();
        Minecraft mc = allocate(Minecraft.class);
        Field skins = Minecraft.class.getDeclaredField("skinManager");
        skins.setAccessible(true);
        skins.set(mc, allocate(LocalSkins.class));
        Field instance = Minecraft.class.getDeclaredField("theMinecraft");
        instance.setAccessible(true);
        Object previous = instance.get(null);
        instance.set(null, mc);
        try {
            body.run();
        } finally {
            instance.set(null, previous);
        }
    }

    @Test
    public void recordedOnAWatchedFromBTheConnectionCameraAndPuppetAreA() throws Exception {
        ExtTrack ext = recordedA();
        GameProfile p = Identity.connectionProfile(ext, SESSION_B);
        assertEquals(A, p.getId());
        assertEquals("RecordedA", p.getName());
        Property tex = p.getProperties().get("textures").iterator().next();
        assertEquals(texturesValue(A, "RecordedA", HASH_A, true), tex.getValue());
        assertEquals("sigA", tex.getSignature());
        PlaybackConnection c = PlaybackConnection.open(null, p);
        try {
            assertSame("the camera vanilla makes takes the handler's profile", p, c.handler().getGameProfile());
        } finally {
            c.close();
        }
        assertSame("one recorded profile per recording", p, Identity.connectionProfile(ext, SESSION_B));
        assertEquals("the puppet is made as A", A,
                Identity.puppetProfile(ext, id -> null, SESSION_B).getId());

        Fake src = new Fake(ext);
        Identity part = new Identity(src);
        RewindCamera camera = allocate(RewindCamera.class);
        RewindSelfPuppet puppet = allocate(RewindSelfPuppet.class);
        final NetworkPlayerInfo cam = part.playerInfo(camera);
        NetworkPlayerInfo pup = part.playerInfo(puppet);
        assertNotNull("the camera's arm", cam);
        assertSame("one player info for both bodies", cam, pup);
        assertEquals(A, cam.getGameProfile().getId());
        assertEquals("RecordedA", cam.getGameProfile().getName());
        withLocalSkins(new Runnable() {
            @Override
            public void run() {
                assertEquals("skins/" + HASH_A, cam.getLocationSkin().getResourcePath());
                assertEquals("A's arm model", "slim", cam.getSkinType());
            }
        });
    }

    @Test
    public void withARecordedTabEntryForAThatEntryIsUsed() throws Exception {
        ExtTrack ext = recordedA();
        Fake src = new Fake(ext);
        GameProfile tabProfile = new GameProfile(A, "RecordedA");
        NetworkPlayerInfo entry = new NetworkPlayerInfo(tabProfile);
        src.tab.put(A, entry);
        src.tab.put(B, new NetworkPlayerInfo(new GameProfile(B, "WatcherB")));
        Identity part = new Identity(src);
        assertSame(entry, part.playerInfo(allocate(RewindCamera.class)));
        assertSame(entry, part.playerInfo(allocate(RewindSelfPuppet.class)));
        assertSame("the puppet takes the tab entry's profile", tabProfile,
                Identity.puppetProfile(ext, src::tab, SESSION_B));
    }

    @Test
    public void aV1RecordingGivesTheWatchingAccount() throws Exception {
        assertSame(SESSION_B, Identity.connectionProfile(ExtTrack.EMPTY, SESSION_B));
        assertNull(Identity.recorded(ExtTrack.EMPTY));
        Fake src = new Fake(ExtTrack.EMPTY);
        GameProfile tabB = new GameProfile(B, "WatcherB");
        src.tab.put(B, new NetworkPlayerInfo(tabB));
        src.tab.put(A, new NetworkPlayerInfo(new GameProfile(A, "RecordedA")));
        Identity part = new Identity(src);
        assertNull("vanilla's own lookup (B's)", part.playerInfo(allocate(RewindCamera.class)));
        assertNull(part.playerInfo(allocate(RewindSelfPuppet.class)));
        assertSame("B's tab entry profile, as before Phase 13", tabB,
                Identity.puppetProfile(ExtTrack.EMPTY, src::tab, SESSION_B));
        assertSame(SESSION_B, Identity.puppetProfile(ExtTrack.EMPTY, id -> null, SESSION_B));
    }

    /**
     * Without a signed textures property (vanilla's own player profile has none) there is no stand-in entry: vanilla
     * would look the textures up from the session for its own account, a lookup playback does not add. The profile is
     * still A's.
     */
    @Test
    public void anUnsignedOrEmptySkinGivesNoStandInEntry() throws Exception {
        ExtTrack empty = identity(A, "RecordedA", "", "");
        GameProfile p = Identity.connectionProfile(empty, SESSION_B);
        assertEquals(A, p.getId());
        assertFalse(p.getProperties().containsKey("textures"));
        assertNull(new Identity(new Fake(empty)).playerInfo(allocate(RewindCamera.class)));
        ExtTrack unsigned = identity(A, "RecordedA", texturesValue(A, "RecordedA", HASH_A, false), "");
        assertNull(new Identity(new Fake(unsigned)).playerInfo(allocate(RewindSelfPuppet.class)));
        assertTrue(Identity.connectionProfile(unsigned, SESSION_B).getProperties().containsKey("textures"));
    }

    @Test
    public void otherPlayersAreNotAnswered() throws Exception {
        Identity part = new Identity(new Fake(recordedA()));
        AbstractClientPlayer other = allocate(EntityOtherPlayerMP.class);
        assertNull(part.playerInfo(other));
    }

    /** The call sites: the connection is opened with Identity's profile, the puppet is made with Identity's. */
    @Test
    public void theConnectionAndThePuppetAreMadeWithIdentitysProfiles() throws Exception {
        String playback = source("com/bedwarsqol/rewind/watch/RewindPlayback.java");
        assertTrue(playback.contains("PlaybackConnection.open(mc, Identity.connectionProfile(mc.getSession().getProfile()))"));
        String sink = source("com/bedwarsqol/rewind/watch/PlaybackSink.java");
        assertTrue(sink.contains("Identity.puppetProfile(handler, mc.getSession().getProfile())"));
        assertFalse("moved to Identity", sink.contains("puppetProfile()"));
    }

    private static String source(String path) throws Exception {
        File f = new File(PlaybackIsolationScanTest.mainRoot(), path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
