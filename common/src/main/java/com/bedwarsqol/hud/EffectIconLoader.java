package com.bedwarsqol.hud;

import com.bedwarsqol.config.AtomicFileWrite;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loads the modern potion icons once: from the disk cache when every verified PNG is there (no request), else with one
 * Range request through {@link Fetcher}, keeping and caching only the icons whose SHA-1 matches {@link EffectIcons}.
 * Runs on the executor given to {@link #start}; the render thread only reads the published map. Never throws to its
 * caller: a failure leaves the cached icons usable and the rest falls back to the 1.8.9 icons.
 */
public final class EffectIconLoader {

    /** One HTTP Range request. */
    public interface Fetcher {
        byte[] range(String url, long from, long to) throws IOException;
    }

    public enum State { IDLE, LOADING, READY, FAILED }

    public enum Source { NONE, CACHE, NETWORK }

    private final File cacheDir;
    private final Fetcher fetcher;
    private final List<EffectIcons.Entry> table;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile Map<String, byte[]> published = Collections.emptyMap();
    private volatile State state = State.IDLE;
    private volatile Source source = Source.NONE;

    public EffectIconLoader(File cacheDir, Fetcher fetcher, List<EffectIcons.Entry> table) {
        this.cacheDir = cacheDir;
        this.fetcher = fetcher;
        this.table = table;
    }

    /** Starts loading on {@code executor}; only the first call does anything. */
    public void start(Executor executor) {
        if (!started.compareAndSet(false, true)) return;
        state = State.LOADING;
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    load();
                }
            });
        } catch (RuntimeException e) {
            state = State.FAILED;
        }
    }

    private void load() {
        Map<String, byte[]> got = new LinkedHashMap<String, byte[]>();
        try {
            for (EffectIcons.Entry e : table) {
                byte[] cached = readCached(e);
                if (cached != null) got.put(e.name, cached);
            }
            publish(got);
            if (got.size() == table.size()) {
                source = Source.CACHE;
                state = State.READY;
                return;
            }
            requests.incrementAndGet();
            byte[] span = fetcher.range(EffectIcons.JAR_URL, EffectIcons.SPAN_FROM, EffectIcons.SPAN_TO);
            for (Map.Entry<String, byte[]> icon : EffectIcons.parse(span, EffectIcons.SPAN_FROM, table).entrySet()) {
                if (got.containsKey(icon.getKey())) continue;
                got.put(icon.getKey(), icon.getValue());
                try {
                    AtomicFileWrite.write(new File(cacheDir, icon.getKey() + ".png"), icon.getValue());
                } catch (IOException ignored) {
                    // still usable this session; the next one fetches it again
                }
            }
            publish(got);
            source = Source.NETWORK;
            state = State.READY;
        } catch (Throwable t) {
            state = State.FAILED;
        }
    }

    /** The cached PNG for {@code e}, or null if it is missing, too big or does not match its pinned SHA-1. */
    private byte[] readCached(EffectIcons.Entry e) {
        File f = new File(cacheDir, e.name + ".png");
        if (!f.isFile() || f.length() > EffectIcons.MAX_PNG) return null;
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            return e.sha1.equalsIgnoreCase(EffectIcons.sha1Hex(bytes)) ? bytes : null;
        } catch (IOException ex) {
            return null;
        }
    }

    private void publish(Map<String, byte[]> icons) {
        published = Collections.unmodifiableMap(new LinkedHashMap<String, byte[]>(icons));
    }

    /** The verified PNG of icon {@code name}, or null. Its bytes must not be changed. */
    public byte[] png(String name) {
        return name == null ? null : published.get(name);
    }

    /** How many icons are published. */
    public int published() {
        return published.size();
    }

    public State state() {
        return state;
    }

    public Source source() {
        return source;
    }

    /** How many Range requests this loader made (0 or 1). */
    public int requests() {
        return requests.get();
    }
}
