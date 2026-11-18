package com.bedwarsqol.mixin;

import net.minecraft.client.gui.GuiIngame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * GUI state that lives outside the world, which Rewind playback resets at every restart and puts back at exit (PLAN
 * section 3 step 5, round 2 B2): the update counter (chat line ages and the heart jiggle follow it), the title timer
 * and the action bar. Only {@code RewindPlayback} uses it.
 */
@Mixin(GuiIngame.class)
public interface GuiIngameAccessor {

    @Accessor("updateCounter")
    int bedwarsqol$getUpdateCounter();

    @Accessor("updateCounter")
    void bedwarsqol$setUpdateCounter(int counter);

    @Accessor("titlesTimer")
    void bedwarsqol$setTitlesTimer(int ticks);

    @Accessor("displayedTitle")
    void bedwarsqol$setDisplayedTitle(String title);

    @Accessor("displayedSubTitle")
    void bedwarsqol$setDisplayedSubTitle(String subtitle);

    @Accessor("recordPlaying")
    void bedwarsqol$setRecordPlaying(String text);

    @Accessor("recordPlayingUpFor")
    void bedwarsqol$setRecordPlayingUpFor(int ticks);

    @Accessor("recordIsPlaying")
    void bedwarsqol$setRecordIsPlaying(boolean playing);

    @Accessor("remainingHighlightTicks")
    void bedwarsqol$setRemainingHighlightTicks(int ticks);
}
