package com.bedwarsqol.stats;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one submit path for the server-side Urchin/Seraph provider keys, shared by the chat commands
 * and the settings GUI so the semantics exist once: length validation, the escaped
 * {@code {"key":"..."}} / {@code {"key":null}} body, the background {@link
 * ScraperBackendClient#postSecret} POST, and the client-thread result marshal. Sequencing contract:
 * a clear strips the local tags BEFORE the request goes out (the Worker commits the deletion before
 * it replies, so a committed-but-response-lost clear must still leave the display safe); a
 * successful set invalidates provider resolution on the client thread before the caller's feedback
 * runs. Both cache actions are caller-supplied runnables (they live in platform code). The key is
 * never logged, echoed, or embedded in an exception.
 */
public final class ProviderKeySubmitter {

    /** Which provider a submission targets; carries the Worker key route it POSTs to. */
    public enum Provider {
        URCHIN("/urchin/key"),
        SERAPH("/seraph/key");

        public final String path;

        Provider(String path) {
            this.path = path;
        }
    }

    public static final int MIN_KEY_LENGTH = 8;
    public static final int MAX_KEY_LENGTH = 200;

    static final String CLEAR_BODY = "{\"key\":null}";

    /** Delivered on the caller's client-thread executor; {@code result} is never null. */
    public interface Callback {
        void onResult(ScraperBackendClient.SecretPostResult result);
    }

    private static final ExecutorService SHARED_BACKGROUND =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "BedwarsQol-KeySubmit");
                t.setDaemon(true);
                return t;
            });

    private final Executor background;
    private final Executor clientThread;

    /** Submits on the shared single-thread background executor; results marshal via {@code clientThread}. */
    public ProviderKeySubmitter(Executor clientThread) {
        this(SHARED_BACKGROUND, clientThread);
    }

    public ProviderKeySubmitter(Executor background, Executor clientThread) {
        this.background = background;
        this.clientThread = clientThread;
    }

    /**
     * Set a provider key. The key is trimmed and length-checked (8-200) before anything is built or
     * sent; on success {@code invalidateResolution} runs on the client thread ahead of the callback.
     * The url/token pair rides the single {@link BackendTarget} captured by the caller at operation
     * start - a mixed pair cannot be assembled here.
     */
    public void submitSet(final Provider provider, final BackendTarget target, String rawKey,
            final Runnable invalidateResolution, final Callback callback) {
        final String key = rawKey == null ? "" : rawKey.trim();
        if (key.length() < MIN_KEY_LENGTH || key.length() > MAX_KEY_LENGTH) {
            deliver(new ScraperBackendClient.SecretPostResult(false, 0, "invalid_key_length"),
                    null, callback);
            return;
        }
        final String body = setBody(key);
        background.execute(() -> {
            ScraperBackendClient.SecretPostResult res =
                    ScraperBackendClient.postSecret(target.url, provider.path, target.token, body);
            deliver(res, res.success ? invalidateResolution : null, callback);
        });
    }

    /**
     * Clear a provider key. {@code stripLocalTags} runs synchronously on the calling thread BEFORE
     * the request is submitted; the callback afterwards only reports the server outcome and must
     * never re-derive display safety from it.
     */
    public void submitClear(final Provider provider, final BackendTarget target,
            Runnable stripLocalTags, final Callback callback) {
        if (stripLocalTags != null) stripLocalTags.run();
        background.execute(() -> {
            ScraperBackendClient.SecretPostResult res =
                    ScraperBackendClient.postSecret(target.url, provider.path, target.token, CLEAR_BODY);
            deliver(res, null, callback);
        });
    }

    private void deliver(final ScraperBackendClient.SecretPostResult res,
            final Runnable onSuccess, final Callback callback) {
        clientThread.execute(() -> {
            if (onSuccess != null) onSuccess.run();
            if (callback != null) callback.onResult(res);
        });
    }

    static String setBody(String trimmedKey) {
        return "{\"key\":\"" + jsonEscape(trimmedKey) + "\"}";
    }

    /** JSON string-escapes quotes, backslashes, and all control chars ({@code < 0x20}). */
    public static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Masked form for on-screen rendering: every char a dot except the last 4 (all dots when the
     *  value is too short for a tail to be safe to show). */
    public static String maskForDisplay(String s) {
        if (s == null || s.isEmpty()) return "";
        int keep = s.length() > 4 ? 4 : 0;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length() - keep; i++) sb.append('•');
        sb.append(s, s.length() - keep, s.length());
        return sb.toString();
    }
}
