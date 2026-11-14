package com.bedwarsqol.hud;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The icon loader: a full verified cache makes no request; a miss fetches once, stores and publishes; failures are quiet. */
public class EffectIconLoaderTest {

    private static final Executor DIRECT = new Executor() {
        @Override
        public void execute(Runnable r) {
            r.run();
        }
    };

    /** Serves a synthetic slice and records every request. */
    private static final class FakeFetcher implements EffectIconLoader.Fetcher {
        final List<String> calls = new ArrayList<String>();
        byte[] body;
        IOException fail;

        @Override
        public byte[] range(String url, long from, long to) throws IOException {
            calls.add(url + " " + from + "-" + to);
            if (fail != null) throw fail;
            return body;
        }
    }

    private File dir;
    private EffectIconFixtures.Jar jar;
    private FakeFetcher fetcher;

    @Before
    public void setUp() throws Exception {
        dir = new File(Files.createTempDirectory("cobblify-effect-icons-test").toFile(), "cobblify-effect-icons");
        jar = EffectIconFixtures.jar("speed", "jump_boost", "wither");
        fetcher = new FakeFetcher();
        fetcher.body = jar.span;
    }

    private EffectIconLoader loader() {
        return new EffectIconLoader(dir, fetcher, jar.table);
    }

    private void cache(String name, byte[] bytes) throws IOException {
        dir.mkdirs();
        Files.write(new File(dir, name + ".png").toPath(), bytes);
    }

    @Test
    public void fullCacheMakesNoRequest() throws Exception {
        for (String n : new String[]{"speed", "jump_boost", "wither"}) cache(n, EffectIconFixtures.png(n));
        EffectIconLoader l = loader();
        l.start(DIRECT);
        assertEquals(EffectIconLoader.State.READY, l.state());
        assertEquals(EffectIconLoader.Source.CACHE, l.source());
        assertEquals(0, l.requests());
        assertTrue(fetcher.calls.isEmpty());
        assertEquals(3, l.published());
        assertArrayEquals(EffectIconFixtures.png("wither"), l.png("wither"));
    }

    @Test
    public void missFetchesOnceStoresAndPublishes() throws Exception {
        EffectIconLoader l = loader();
        assertEquals(EffectIconLoader.State.IDLE, l.state());
        assertNull(l.png("speed"));
        l.start(DIRECT);
        assertEquals(EffectIconLoader.State.READY, l.state());
        assertEquals(EffectIconLoader.Source.NETWORK, l.source());
        assertEquals(1, l.requests());
        assertEquals(1, fetcher.calls.size());
        assertEquals(EffectIcons.JAR_URL + " " + EffectIcons.SPAN_FROM + "-" + EffectIcons.SPAN_TO, fetcher.calls.get(0));
        assertEquals(3, l.published());
        assertArrayEquals(EffectIconFixtures.png("speed"), l.png("speed"));
        for (String n : new String[]{"speed", "jump_boost", "wither"}) {
            assertArrayEquals(n, EffectIconFixtures.png(n), Files.readAllBytes(new File(dir, n + ".png").toPath()));
        }
    }

    @Test
    public void secondLoaderReadsTheCache() {
        loader().start(DIRECT);
        assertEquals(1, fetcher.calls.size());
        EffectIconLoader second = loader();
        second.start(DIRECT);
        assertEquals(0, second.requests());
        assertEquals(EffectIconLoader.Source.CACHE, second.source());
        assertEquals(EffectIconLoader.State.READY, second.state());
        assertEquals(3, second.published());
        assertEquals("no second request", 1, fetcher.calls.size());
    }

    @Test
    public void offlineFailsQuietly() throws Exception {
        fetcher.fail = new IOException("offline");
        cache("speed", EffectIconFixtures.png("speed"));
        EffectIconLoader l = loader();
        l.start(DIRECT);
        assertEquals(EffectIconLoader.State.FAILED, l.state());
        assertEquals(1, l.requests());
        assertNull(l.png("wither"));
        assertArrayEquals("what the cache had stays usable", EffectIconFixtures.png("speed"), l.png("speed"));
        assertEquals(1, l.published());

        // a fetcher that throws something unexpected is still quiet
        EffectIconLoader.Fetcher broken = new EffectIconLoader.Fetcher() {
            @Override
            public byte[] range(String url, long from, long to) {
                throw new IllegalStateException("boom");
            }
        };
        EffectIconLoader b = new EffectIconLoader(new File(dir, "other"), broken, jar.table);
        b.start(DIRECT);
        assertEquals(EffectIconLoader.State.FAILED, b.state());
        assertEquals(0, b.published());
    }

    @Test
    public void corruptCacheFileIsRefetched() throws Exception {
        cache("speed", EffectIconFixtures.png("speed"));
        cache("jump_boost", EffectIconFixtures.png("jump_boost"));
        cache("wither", new byte[]{1, 2, 3});
        EffectIconLoader l = loader();
        l.start(DIRECT);
        assertEquals(1, l.requests());
        assertEquals(EffectIconLoader.Source.NETWORK, l.source());
        assertArrayEquals(EffectIconFixtures.png("wither"), l.png("wither"));
        assertArrayEquals("the bad file is replaced", EffectIconFixtures.png("wither"),
                Files.readAllBytes(new File(dir, "wither.png").toPath()));
    }

    @Test
    public void startRunsOnce() {
        EffectIconLoader l = loader();
        final List<Runnable> queued = new ArrayList<Runnable>();
        Executor later = new Executor() {
            @Override
            public void execute(Runnable r) {
                queued.add(r);
            }
        };
        l.start(later);
        l.start(later);
        l.start(DIRECT);
        assertEquals(1, queued.size());
        assertEquals(EffectIconLoader.State.LOADING, l.state());
        queued.get(0).run();
        assertEquals(EffectIconLoader.State.READY, l.state());
        assertEquals(1, fetcher.calls.size());
        assertFalse(l.png("speed") == null);
    }
}
