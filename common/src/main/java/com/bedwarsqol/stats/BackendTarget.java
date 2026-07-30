package com.bedwarsqol.stats;

/**
 * Immutable URL/token pair for the stats backend, resolved atomically at operation start so the
 * two values can never come from different sources (user-set vs baked) — see
 * {@code ClientSettings#backendTarget()}.
 */
public final class BackendTarget {

    /** Backend base URL; "" = no backend configured. Never null. */
    public final String url;

    /** Token sent alongside requests to {@link #url}; "" = none. Never null. */
    public final String token;

    public BackendTarget(String url, String token) {
        this.url = url == null ? "" : url;
        this.token = token == null ? "" : token;
    }

    /** Whether any backend URL is present. */
    public boolean isConfigured() {
        return !url.isEmpty();
    }
}
