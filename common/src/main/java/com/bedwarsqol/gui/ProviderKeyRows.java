package com.bedwarsqol.gui;

import com.bedwarsqol.stats.ProviderKeySubmitter;

/**
 * Row-model for the settings GUI's provider-key TEXT rows: the control kind numbers (shared by both
 * trees, following the SettingsGui kind space) and the kind -&gt; provider mapping every submission
 * dispatches through. Extracted from SettingsGui so the wiring is unit-testable without Minecraft
 * classes - a swapped enum here fails {@code ProviderKeyRowsTest} in BOTH suites.
 */
public final class ProviderKeyRows {

    /** Kind of the "Urchin API Key" TEXT row. */
    public static final int URCHIN_KEY_KIND = 118;

    /** Kind of the "Seraph API Key" TEXT row. */
    public static final int SERAPH_KEY_KIND = 119;

    private ProviderKeyRows() {}

    /** Provider a TEXT key row submits to, or null when the kind is not a key row. */
    public static ProviderKeySubmitter.Provider providerFor(int kind) {
        if (kind == URCHIN_KEY_KIND) return ProviderKeySubmitter.Provider.URCHIN;
        if (kind == SERAPH_KEY_KIND) return ProviderKeySubmitter.Provider.SERAPH;
        return null;
    }

    /** The cache action a submission dispatches for its provider: URCHIN selects
     *  {@code urchinAction}, SERAPH selects {@code seraphAction}. Extracted from the former
     *  SettingsGui ternary (the actions live in platform code) so the provider -&gt; action mapping
     *  is pinned by {@code ProviderKeyRowsTest} in BOTH suites. */
    public static Runnable cacheActionFor(ProviderKeySubmitter.Provider provider,
            Runnable urchinAction, Runnable seraphAction) {
        return provider == ProviderKeySubmitter.Provider.URCHIN ? urchinAction : seraphAction;
    }
}
