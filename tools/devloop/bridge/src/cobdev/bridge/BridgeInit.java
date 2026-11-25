package cobdev.bridge;

import net.ornithemc.osl.entrypoints.api.client.ClientModInitializer;

/** OSL client entrypoint: runs during Minecraft's startup, after which game classes are safe to touch. */
public final class BridgeInit implements ClientModInitializer {

    @Override
    public void initClient() {
        Bridge.register();
    }
}
