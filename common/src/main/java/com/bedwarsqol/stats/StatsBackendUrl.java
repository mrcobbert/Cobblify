package com.bedwarsqol.stats;

/**
 * The one rule for accepting a stats backend URL from {@code /cobblify statsurl}. The command used to
 * store whatever was typed, so a bare host ("my-worker.workers.dev") became a "no protocol" failure on
 * every fetch — blank stats with no explanation — and an {@code http://} URL would carry the backend
 * token in clear. Accepting only what {@link ScraperBackendClient#validateSecretUrl} already requires
 * of a secret POST keeps the saved setting and the transport rule identical.
 */
public final class StatsBackendUrl {

    private StatsBackendUrl() {}

    /**
     * The URL to store for {@code raw}, or null when it must be refused. Trims, drops trailing
     * slashes (the client appends its own path), and keeps everything else verbatim — case included,
     * since only the scheme comparison is case-insensitive.
     */
    public static String normalize(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String s = raw.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return ScraperBackendClient.validateSecretUrl(s) == null ? s : null;
    }
}
