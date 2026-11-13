package com.bedwarsqol;

import net.minecraft.client.Minecraft;
import net.ornithemc.osl.entrypoints.api.client.ClientModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Ornithe entrypoint. Build skeleton only; the ported mod replaces this in the next phase. */
public final class CobblifyOrnithe implements ClientModInitializer {

    private static final Logger LOGGER = LogManager.getLogger("Cobblify");

    @Override
    public void initClient() {
        LOGGER.info("Cobblify (ornithe) initialized");
    }

    /** Never called. Compiles only if the MCP (stable_22) named namespace is on the classpath. */
    @SuppressWarnings("unused")
    private static boolean mcpNamesCompile() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.thePlayer != null && mc.gameSettings.showDebugInfo;
    }
}
