package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.rewind.ExtCodec;
import com.bedwarsqol.rewind.RewindFormat;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;

import java.util.UUID;
import java.util.function.Function;

/**
 * Whose body and arm a recording shows (PLAN "C. Playback of v2 data", Identity; Phase 13, TASK C8): one answer for
 * both bodies, from the recording's IDENTITY record (the recording player's UUID, name and {@code textures} property).
 *
 * <ul>
 *   <li><b>The playback connection</b> is opened with that profile ({@link #connectionProfile(GameProfile)}), so the
 *       camera vanilla makes from the handler's profile ({@code EntityPlayerSP.<init>} @2-6) is the recorded player,
 *       and the first-person arm ({@code ItemRenderer.renderPlayerArm}: the camera's {@code getLocationSkin} and
 *       {@code getSkinType}) is theirs.</li>
 *   <li><b>The puppet</b> (the F5 stand-in) is made with it too ({@link #puppetProfile(NetHandlerPlayClient,
 *       GameProfile)}, which was {@code PlaybackSink.puppetProfile}): the recorded tab entry's profile for that UUID,
 *       else the recorded profile.</li>
 *   <li><b>Player info</b> ({@link #playerInfo}, the question the camera's and the puppet's {@code getPlayerInfo}
 *       ask): the recorded tab entry of the recorded UUID when the tab list has one; else one
 *       {@link NetworkPlayerInfo} built from the recorded profile and shared by both bodies, from which vanilla takes
 *       the skin, the slim or classic arm model and the cape. That stand-in exists only for a signed {@code textures}
 *       property: an unsigned or missing one would make vanilla's skin manager fetch the session account's textures
 *       from Mojang (a lookup playback does not add), so then vanilla's own lookup answers (the default skin when the
 *       tab list has no entry).</li>
 *   <li><b>A v1 recording</b> (no IDENTITY): the watching account's session profile and no answer, as before.</li>
 * </ul>
 * The skin itself loads as in live play (vanilla's skin cache, Mojang's skin server; R2 Q8). Nothing here logs or
 * stores the identity. Register once with {@link #register()} (Phase 16 calls it from {@code BedwarsQol}).
 */
public final class Identity implements PlaybackPart {

    /** What the part reads. */
    interface Source {
        /** The playing recording's extension records ({@link ExtTrack#EMPTY} when none). */
        ExtTrack ext();

        /** The playback connection's tab entry for {@code id}, or null. */
        NetworkPlayerInfo tab(UUID id);
    }

    private static final Source SESSION = new Source() {
        @Override
        public ExtTrack ext() {
            return sessionExt();
        }

        @Override
        public NetworkPlayerInfo tab(UUID id) {
            NetHandlerPlayClient h = Minecraft.getMinecraft().getNetHandler();
            return h == null ? null : h.getPlayerInfo(id);
        }
    };

    private static final Identity INSTANCE = new Identity(SESSION);

    /** The last recording's records and the profile built from them (one profile object per recording). */
    private static ExtTrack profileFor;
    private static GameProfile profile;

    private final Source source;
    /** The shared stand-in entry, for {@link #infoFor}'s profile. */
    private GameProfile infoFor;
    private NetworkPlayerInfo info;

    Identity(Source source) {
        this.source = source;
    }

    /** Registers the part with playback (once; repeated calls do nothing). Client thread. */
    public static void register() {
        RewindPlayback.register(INSTANCE);
    }

    private static ExtTrack sessionExt() {
        LoadedRecording r = RewindPlayback.session().recording();
        return r == null ? ExtTrack.EMPTY : r.extTrack();
    }

    /**
     * The recorded player's profile: UUID and name from the recording's first IDENTITY record, with its
     * {@code textures} property (and signature) when it has one; null without IDENTITY (v1) or an unusable one.
     * The same object for the same records.
     */
    static synchronized GameProfile recorded(ExtTrack ext) {
        if (ext == null || !ext.has(RewindFormat.TAG_IDENTITY)) {
            return null;
        }
        if (ext != profileFor) {
            profileFor = ext;
            profile = build(ext);
        }
        return profile;
    }

    private static GameProfile build(ExtTrack ext) {
        for (int seg = 0; seg < ext.segmentCount(); seg++) {
            if (ext.count(RewindFormat.TAG_IDENTITY, seg) == 0) {
                continue;
            }
            ExtCodec.Identity id;
            try {
                id = ext.read(ext.record(RewindFormat.TAG_IDENTITY, seg, 0), new ExtCodec.Identity());
            } catch (RuntimeException e) {
                return null;
            }
            UUID uuid = id.uuidMost == 0L && id.uuidLeast == 0L ? null : new UUID(id.uuidMost, id.uuidLeast);
            String name = id.name == null || id.name.isEmpty() ? null : id.name;
            if (uuid == null) {
                return null; // the camera and the puppet need an id; keep the session's
            }
            GameProfile p = new GameProfile(uuid, name);
            if (id.skinValue != null && !id.skinValue.isEmpty()) {
                p.getProperties().put("textures", id.skinSignature == null || id.skinSignature.isEmpty()
                        ? new Property("textures", id.skinValue)
                        : new Property("textures", id.skinValue, id.skinSignature));
            }
            return p;
        }
        return null;
    }

    /** The playback connection's profile for the playing recording: the recorded one, else {@code session}. */
    public static GameProfile connectionProfile(GameProfile session) {
        return connectionProfile(sessionExt(), session);
    }

    static GameProfile connectionProfile(ExtTrack ext, GameProfile session) {
        GameProfile p = recorded(ext);
        return p != null ? p : session;
    }

    /** The puppet's profile (was {@code PlaybackSink.puppetProfile}): the tab entry's for that UUID, else it. */
    public static GameProfile puppetProfile(NetHandlerPlayClient handler, GameProfile session) {
        return puppetProfile(sessionExt(), handler::getPlayerInfo, session);
    }

    static GameProfile puppetProfile(ExtTrack ext, Function<UUID, NetworkPlayerInfo> tab, GameProfile session) {
        GameProfile own = connectionProfile(ext, session);
        NetworkPlayerInfo info = own.getId() == null ? null : tab.apply(own.getId());
        return info != null && info.getGameProfile() != null ? info.getGameProfile() : own;
    }

    /** A signed {@code textures} property, which vanilla's skin manager reads without asking Mojang. */
    private static boolean signedSkin(GameProfile p) {
        for (Property t : p.getProperties().get("textures")) {
            return t.hasSignature() && t.getValue() != null && !t.getValue().isEmpty();
        }
        return false;
    }

    @Override
    public NetworkPlayerInfo playerInfo(AbstractClientPlayer player) {
        if (!(player instanceof RewindCamera) && !(player instanceof RewindSelfPuppet)) {
            return null;
        }
        GameProfile p = recorded(source.ext());
        if (p == null) {
            return null; // v1: vanilla's own lookup (the watching account's, as before)
        }
        NetworkPlayerInfo tab = source.tab(p.getId());
        if (tab != null) {
            return tab;
        }
        if (!signedSkin(p)) {
            return null;
        }
        if (p != infoFor) {
            infoFor = p;
            info = new NetworkPlayerInfo(p);
        }
        return info;
    }

    /** The session ended: let go of the recording (its records) and the stand-in entry. */
    @Override
    public void exit() {
        infoFor = null;
        info = null;
        synchronized (Identity.class) {
            profileFor = null;
            profile = null;
        }
    }
}
