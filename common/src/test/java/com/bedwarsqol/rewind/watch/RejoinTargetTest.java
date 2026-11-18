package com.bedwarsqol.rewind.watch;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** TASK A12, Q7: Rejoin goes to the server that was left, else the Hypixel entry in the server list, else mc.hypixel.net. */
public class RejoinTargetTest {

    private static RejoinTarget.Server s(String name, String address) {
        return new RejoinTarget.Server(name, address);
    }

    @Test
    public void theServerThatWasLeftComesFirst() {
        RejoinTarget.Server left = s("Hypixel", "mc.hypixel.net:25565");
        List<RejoinTarget.Server> list = Arrays.asList(s("Mine", "hypixel.net"), s("Other", "example.org"));
        assertSame(left, RejoinTarget.choose(left, list));
    }

    @Test
    public void thenTheFirstHypixelEntryInTheServerList() {
        List<RejoinTarget.Server> list = Arrays.asList(s("Other", "example.org"), s("Spoof", "hypixel.net.evil.com"),
                s("HP", "MC.Hypixel.NET."), s("HP2", "hypixel.net"));
        RejoinTarget.Server t = RejoinTarget.choose(null, list);
        assertEquals("HP", t.name);
        assertEquals("MC.Hypixel.NET.", t.address);
    }

    @Test
    public void thenMcHypixelNet() {
        RejoinTarget.Server t = RejoinTarget.choose(null, Arrays.asList(s("Other", "example.org"), null,
                s("Blank", null)));
        assertEquals("mc.hypixel.net", t.address);
        assertEquals("Hypixel", t.name);
        assertEquals("mc.hypixel.net", RejoinTarget.choose(null, null).address);
        assertEquals("mc.hypixel.net", RejoinTarget.choose(null, Collections.<RejoinTarget.Server>emptyList()).address);
    }

    @Test
    public void aLeftServerWithoutAnAddressDoesNotCount() {
        RejoinTarget.Server t = RejoinTarget.choose(s("Ghost", "  "), Collections.singletonList(s("HP", "hypixel.net")));
        assertEquals("HP", t.name);
    }
}
