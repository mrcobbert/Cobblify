package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Layer 1 of the anonymous-nick-skin denick fix: pins texture-hash canonicalization, the bundled
 * pool-skin membership set, and the blob-decode path that feeds them. A player nicked with a Hypixel
 * /nick pool skin must NOT be denicked to the pool skin's uploader; a kept personal skin still is.
 */
public class DenicksNickSkinTest {

    /** A real entry from the pinned nick-skins.txt (gist rev f85b76ee…). */
    private static final String POOL_HASH =
            "04426382d98452d90ef2cdb492af67853ca8542972595351acc6cbf88f51532";

    @Test
    public void canonicalizeStripsQueryFragmentSlashAndCase() {
        assertEquals("abc123", Denicks.canonicalTextureHash(
                "http://textures.minecraft.net/texture/ABC123"));
        assertEquals("abc123", Denicks.canonicalTextureHash(
                "http://textures.minecraft.net/texture/abc123?size=64"));
        assertEquals("abc123", Denicks.canonicalTextureHash(
                "http://textures.minecraft.net/texture/abc123#frag"));
        assertEquals("abc123", Denicks.canonicalTextureHash(
                "http://textures.minecraft.net/texture/abc123/"));
    }

    @Test
    public void canonicalizeHandlesNullAndEmpty() {
        assertNull(Denicks.canonicalTextureHash(null));
        assertNull(Denicks.canonicalTextureHash(""));
        assertNull(Denicks.canonicalTextureHash("/"));
    }

    @Test
    public void isNickSkinMembership() {
        assertTrue("known pool hash must load from the bundled resource", Denicks.isNickSkin(POOL_HASH));
        assertFalse(Denicks.isNickSkin("deadbeefdeadbeefdeadbeef"));
        assertFalse(Denicks.isNickSkin(null));
    }

    @Test
    public void poolSkinBlobResolvesToKnownNickSkin() {
        // A pool-skin blob names the UPLOADER, not the player — the bug source. Its hash IS a nick skin.
        String json = "{\"textures\":{\"SKIN\":{\"url\":"
                + "\"http://textures.minecraft.net/texture/" + POOL_HASH + "\"}},"
                + "\"profileId\":\"8c11721ac9f142789a0d822dfc3a1a9a\","
                + "\"profileName\":\"DogyCZSK_\"}";
        Denicks.SkinIdentity id = Denicks.identityFromDecodedJson(json);
        assertNotNull(id);
        assertEquals("DogyCZSK_", id.name);
        assertEquals(POOL_HASH, id.textureHash);
        assertTrue(Denicks.isNickSkin(id.textureHash));
    }

    @Test
    public void keptSkinBlobIsNotANickSkin() {
        String json = "{\"textures\":{\"SKIN\":{\"url\":"
                + "\"http://textures.minecraft.net/texture/"
                + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"}},"
                + "\"profileName\":\"RealAccount\"}";
        Denicks.SkinIdentity id = Denicks.identityFromDecodedJson(json);
        assertNotNull(id);
        assertEquals("RealAccount", id.name);
        assertFalse(Denicks.isNickSkin(id.textureHash));
    }

    @Test
    public void malformedBlobsReturnNull() {
        assertNull(Denicks.identityFromDecodedJson(null));
        assertNull(Denicks.identityFromDecodedJson("{\"textures\":{}}")); // no profileName
    }
}
