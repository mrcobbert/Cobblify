package com.bedwarsqol.rewind.watch;

import com.bedwarsqol.stats.EligibilitySnapshot;

import java.util.List;

/**
 * Where "Rejoin Hypixel" connects (TASK A12, G48, Q7): the server that was left to watch; with none (watching started
 * from the title screen), the first Hypixel entry in the player's server list (exact {@code hypixel.net} or
 * {@code *.hypixel.net} host); else {@code mc.hypixel.net}. It only connects: no command is sent.
 */
public final class RejoinTarget {

    public static final String DEFAULT_ADDRESS = "mc.hypixel.net";
    public static final String DEFAULT_NAME = "Hypixel";

    /** A server-list entry or the server that was left: its name and address as the player saved them. */
    public static final class Server {
        public final String name;
        public final String address;

        public Server(String name, String address) {
            this.name = name;
            this.address = address;
        }
    }

    private RejoinTarget() {
    }

    /** Never null. */
    public static Server choose(Server left, List<Server> serverList) {
        if (hasAddress(left)) return left;
        if (serverList != null) {
            for (Server s : serverList) {
                if (hasAddress(s) && EligibilitySnapshot.isExactHypixelHost(s.address)) return s;
            }
        }
        return new Server(DEFAULT_NAME, DEFAULT_ADDRESS);
    }

    private static boolean hasAddress(Server s) {
        return s != null && s.address != null && !s.address.trim().isEmpty();
    }
}
