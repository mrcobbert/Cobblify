package com.bedwarsqol.ornithe;

import net.minecraft.network.NetworkManager;

/**
 * The client finished logging in and switched the connection to the play state, still on the netty
 * thread (where FML fires {@code ClientConnectedToServerEvent}).
 */
public final class ServerConnectEvent {

    public final NetworkManager manager;

    public ServerConnectEvent(NetworkManager manager) {
        this.manager = manager;
    }
}
