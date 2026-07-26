package com.bedwarsqol.feature;

import com.bedwarsqol.stats.BedwarsStats;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared denick state and skin-decoding, used by {@link NickUtils} (detection + announce), the chat
 * annotation ({@link ChatNameTags}), and the tab-list overlay ({@code GuiPlayerTabOverlayMixin}).
 *
 * <p><b>How the denick works (and why it is undetectable).</b> A nicked player who keeps their own
 * skin has Hypixel forward the original Mojang-signed {@code textures} property. That base64 blob is
 * JSON embedding the skin owner's real {@code profileName} — the genuine account, because Mojang signs
 * it. We decode a {@link GameProfile} we were already sent: zero requests, nothing transmitted, pure
 * local parsing, so Hypixel cannot observe it (see {@code nick-denick-via-signed-skin}). A player whose
 * signed skin names the same account as their in-game name isn't nicked; a mismatch is (UUID is
 * immutable across renames, so a mere rename is not a false positive).
 *
 * <p><b>Context gate ({@link #identitiesVisible()}).</b> Since ~Aug 2024 Hypixel obfuscates names and
 * strips skins in the pregame waiting lobby — and the pregame→game transition briefly pairs obfuscated
 * tab names with <i>real</i> signed skins, so "any signed skin present" is NOT proof identities are real
 * (that definition once let the whole lobby false-denick, ourselves included). The scan in
 * {@link NickUtils} therefore only reports identities visible when it saw at least one
 * identity-consistent real row (v4 UUID whose signed skin names its own tab account) and no
 * obfuscation-window tripwire fired. The dependent surfaces stay silent when it is false.
 */
public final class Denicks {

    private Denicks() {}

    /** "profileName" inside the base64-decoded, Mojang-signed skin "textures" blob = the real account. */
    private static final Pattern PROFILE_NAME = Pattern.compile("\"profileName\"\\s*:\\s*\"([^\"]+)\"");

    /** "profileId" in the same blob = the skin owner's real account UUID (32 hex chars, no dashes). */
    private static final Pattern PROFILE_ID = Pattern.compile("\"profileId\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");

    /** The SKIN texture URL inside the blob, e.g. http://textures.minecraft.net/texture/&lt;hash&gt;. */
    private static final Pattern SKIN_URL =
            Pattern.compile("\"SKIN\"\\s*:\\s*\\{\\s*\"url\"\\s*:\\s*\"([^\"]+)\"");

    /** Hash of a valid texture line in {@code nick-skins.txt} (lower hex, min 16 chars). */
    private static final Pattern HASH_LINE = Pattern.compile("[0-9a-f]{16,}");

    /**
     * Canonical texture hashes of every Hypixel /nick pool skin (see the bundled resource for
     * provenance). A player nicked with one of these carries a signed blob naming the pool skin's
     * <i>uploader</i>, not themselves, so the raw {@code profileName} would be a false denick.
     * Loaded lazily; any failure yields an empty set (Layer 1 disabled, Layer 2 still guards).
     */
    private static volatile Set<String> nickSkinHashes;

    /**
     * Lower-cased in-game nick -> recovered real account name — the <b>verified</b> map, the only one
     * ever read by chat/tab surfaces. A nick reaches here only after {@link NickUtils} confirmed its
     * resolved real account is OK and elevated-rank (see Layer 2). Published as an immutable snapshot
     * swapped atomically each scan, so a departed/changed/downgraded nick disappears at once and no
     * unverified or stale mapping is ever visible.
     */
    private static volatile Map<String, String> verified = Collections.emptyMap();

    /** Set true whenever the latest scan decoded at least one valid signed skin (real identities present). */
    private static volatile boolean identitiesVisible = false;

    /** Reset all per-world state (called on world load). */
    public static void clear() {
        verified = Collections.emptyMap();
        identitiesVisible = false;
    }

    public static boolean identitiesVisible() {
        return identitiesVisible;
    }

    public static void setIdentitiesVisible(boolean visible) {
        identitiesVisible = visible;
    }

    /**
     * Replace the verified nick→real map with this scan's snapshot (keys lower-cased). Recomputed
     * every scan from the live tab rows plus their resolved rank, so pruning (leave / owner change /
     * rank downgrade / session) is automatic and a late/stale result cannot linger.
     */
    public static void publishVerified(Map<String, String> next) {
        verified = (next == null || next.isEmpty())
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new HashMap<String, String>(next));
    }

    /** The verified real name for an in-game nick, or null when it isn't a confirmed denick. */
    public static String realNameForNick(String inGameName) {
        if (inGameName == null || inGameName.isEmpty()) return null;
        return verified.get(inGameName.toLowerCase());
    }

    /** Whether a settled denick candidate should publish, keep waiting, or be dropped (Layer 2). */
    public enum DenickDecision { VERIFY, PENDING, DROP }

    /**
     * Decide a settled candidate from its resolved real account: VERIFY only when OK + elevated rank
     * (only elevated ranks can /nick); PENDING when unresolved or provenance UNKNOWN (uncached / old
     * cache / fetch error — caller refetches); DROP when the account is known non-elevated (a false
     * denick). Pure — the whole Layer-2 gate in one testable place.
     */
    public static DenickDecision decide(BedwarsStats st) {
        if (st == null) return DenickDecision.PENDING;
        switch (st.rankProvenance()) {
            case ELEVATED: return DenickDecision.VERIFY;
            case UNKNOWN:  return DenickDecision.PENDING;
            default:       return DenickDecision.DROP; // NON_ELEVATED
        }
    }

    /** The real identity a Mojang-signed skin embeds: account name, undashed UUID hex, texture hash. */
    public static final class SkinIdentity {
        public final String name;
        public final String idHex;       // null on blobs that omit profileId
        public final String textureHash; // canonical skin-texture hash, null if absent/unparseable

        SkinIdentity(String name, String idHex, String textureHash) {
            this.name = name;
            this.idHex = idHex;
            this.textureHash = textureHash;
        }
    }

    /** Decode the player's Mojang-signed skin property; return the real identity it embeds, or null. */
    public static SkinIdentity identityFromSkin(GameProfile profile) {
        if (profile == null) return null;
        Collection<Property> textures = profile.getProperties().get("textures");
        if (textures == null || textures.isEmpty()) return null;
        Property prop = textures.iterator().next();
        if (prop == null || prop.getValue() == null) return null;
        String json;
        try {
            json = new String(Base64.getDecoder().decode(prop.getValue()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException badBase64) {
            return null;
        }
        return identityFromDecodedJson(json);
    }

    /** Extract the embedded identity from an already-decoded skin {@code textures} blob, or null. */
    static SkinIdentity identityFromDecodedJson(String json) {
        if (json == null) return null;
        Matcher name = PROFILE_NAME.matcher(json);
        if (!name.find()) return null;
        Matcher id = PROFILE_ID.matcher(json);
        Matcher url = SKIN_URL.matcher(json);
        String hash = url.find() ? canonicalTextureHash(url.group(1)) : null;
        return new SkinIdentity(name.group(1), id.find() ? id.group(1) : null, hash);
    }

    /**
     * Canonicalize a skin texture URL to its comparable hash: drop query/fragment, trim trailing
     * slashes, take the last non-empty path segment, lowercase. Matches how {@code nick-skins.txt}
     * entries were extracted. Null if nothing usable.
     */
    static String canonicalTextureHash(String url) {
        if (url == null) return null;
        int cut = url.indexOf('?');
        if (cut >= 0) url = url.substring(0, cut);
        cut = url.indexOf('#');
        if (cut >= 0) url = url.substring(0, cut);
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        int slash = url.lastIndexOf('/');
        String seg = (slash >= 0 ? url.substring(slash + 1) : url).toLowerCase();
        return seg.isEmpty() ? null : seg;
    }

    /** True if {@code textureHash} is a known Hypixel /nick pool skin (so its embedded name is bogus). */
    public static boolean isNickSkin(String textureHash) {
        return textureHash != null && nickSkinHashes().contains(textureHash);
    }

    private static Set<String> nickSkinHashes() {
        Set<String> set = nickSkinHashes;
        if (set == null) {
            synchronized (Denicks.class) {
                set = nickSkinHashes;
                if (set == null) {
                    set = loadNickSkinHashes();
                    nickSkinHashes = set;
                }
            }
        }
        return set;
    }

    /** Load the bundled pool-skin hash set; any failure yields an empty set (never throws). */
    private static Set<String> loadNickSkinHashes() {
        Set<String> set = new HashSet<String>();
        InputStream in = Denicks.class.getResourceAsStream("/assets/bedwarsqol/nick-skins.txt");
        if (in == null) return Collections.unmodifiableSet(set);
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim().toLowerCase();
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                if (HASH_LINE.matcher(line).matches()) set.add(line);
            }
        } catch (Exception ignored) {
            // fall through with whatever parsed; Layer 2 still guards
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
        return Collections.unmodifiableSet(set);
    }
}
