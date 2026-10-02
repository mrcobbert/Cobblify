package com.bedwarsqol.gui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HoverFadeTest {

    @Test
    public void startsAtZeroOnTheFirstFrame() {
        assertEquals(0f, new HoverFade(100, 200).update(true, 1000), 0f);
    }

    @Test
    public void easesInWhileHovered() {
        HoverFade fade = new HoverFade(100, 200);
        fade.update(true, 1000);
        assertEquals(0.5f, fade.update(true, 1050), 1e-6f);
        assertEquals(1f, fade.update(true, 1100), 0f);
        assertEquals(1f, fade.update(true, 5000), 0f);
    }

    @Test
    public void easesOutAtItsOwnSpeed() {
        HoverFade fade = new HoverFade(100, 200);
        fade.update(true, 0);
        fade.update(true, 100);
        assertEquals(0.5f, fade.update(false, 200), 1e-6f);
        assertEquals(0f, fade.update(false, 300), 0f);
    }

    @Test
    public void reversesFromWhereItIs() {
        HoverFade fade = new HoverFade(100, 100);
        fade.update(true, 0);
        fade.update(true, 50);
        fade.update(false, 75); // progress 0.25
        assertEquals(0.5f, fade.update(true, 100), 1e-6f);
    }

    @Test
    public void ignoresAClockThatGoesBackwards() {
        HoverFade fade = new HoverFade(100, 100);
        fade.update(true, 1000);
        assertEquals(0f, fade.update(true, 900), 0f);
    }
}
