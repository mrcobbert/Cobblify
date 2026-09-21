package com.bedwarsqol.command;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Pins what {@code /cobblify} tab-completes on either loader: subcommands first, mode words second. */
public class CommandCompletionTest {

    @Test
    public void firstWordOffersTheSubcommandsFilteredByPrefix() {
        assertEquals(Arrays.asList(CommandCompletion.SUBCOMMANDS), CommandCompletion.suggest(new String[]{""}));
        assertEquals(Arrays.asList("mode"), CommandCompletion.suggest(new String[]{"mo"}));
        assertEquals(Arrays.asList("stats", "statsurl", "statstoken"), CommandCompletion.suggest(new String[]{"STAT"}));
        assertEquals(Collections.emptyList(), CommandCompletion.suggest(new String[]{"zzz"}));
    }

    @Test
    public void modeSecondWordOffersTheOptionWords() {
        assertEquals(Arrays.asList("auto", "all", "solo", "2s", "3s", "4s"),
                CommandCompletion.suggest(new String[]{"mode", ""}));
        assertEquals(Arrays.asList("auto", "all"), CommandCompletion.suggest(new String[]{"MODE", "a"}));
        assertEquals(Arrays.asList("4s"), CommandCompletion.suggest(new String[]{"mode", "4"}));
        assertEquals(Arrays.asList("reset"), CommandCompletion.suggest(new String[]{"session", ""}));
    }

    @Test
    public void nothingElseIsSuggested() {
        assertTrue(CommandCompletion.suggest(new String[0]).isEmpty());
        assertTrue(CommandCompletion.suggest(null).isEmpty());
        assertTrue(CommandCompletion.suggest(new String[]{"stats", ""}).isEmpty());   // player names come from the server
        assertTrue(CommandCompletion.suggest(new String[]{"mode", "4s", ""}).isEmpty());
    }
}
