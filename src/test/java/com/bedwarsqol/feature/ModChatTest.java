package com.bedwarsqol.feature;

import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The mod-authored mark must have no player-visible effect. It used to be the root style's
 * {@code insertion}, which vanilla types into the chat box on shift-click, so shift-clicking any
 * Cobblify line inserted {@code bwqol self}.
 */
public class ModChatTest {

    @Test
    public void markedLineHasNoShiftClickInsertionOnAnyVisiblePart() {
        ChatComponentText line = ModChat.mark(new ChatComponentText("§7[Cobblify] hello"));
        assertTrue(ModChat.isMarked(line));
        assertNull(line.getChatStyle().getInsertion());
        for (IChatComponent part : line) {
            if (part.getUnformattedTextForChat().isEmpty()) continue; // zero width: never hit by a click
            assertNull("visible part: " + part, part.getChatStyle().getInsertion());
        }
    }

    @Test
    public void markAddsNoText() {
        ChatComponentText line = ModChat.mark(new ChatComponentText("hello"));
        assertEquals("hello", line.getUnformattedText());
    }

    /** Even if a chat GUI did hit the zero-width part, the text field would insert nothing. */
    @Test
    public void anyInsertionTheMarkCarriesFiltersToNothing() {
        ChatComponentText line = ModChat.mark(new ChatComponentText("hello"));
        for (IChatComponent part : line) {
            String insertion = part.getChatStyle().getInsertion();
            if (insertion != null) assertEquals("", ChatAllowedCharacters.filterAllowedCharacters(insertion));
        }
    }

    /** Lunar may copy or re-serialize the component before our ChatEvent.Received listener sees it. */
    @Test
    public void markSurvivesCopyAndJsonRoundTrip() {
        ChatComponentText line = ModChat.mark(new ChatComponentText("hello"));
        assertTrue(ModChat.isMarked(line.createCopy()));
        String json = IChatComponent.Serializer.componentToJson(line);
        assertTrue(ModChat.isMarked(IChatComponent.Serializer.jsonToComponent(json)));
    }

    @Test
    public void markSurvivesSiblingsAppendedLaterAndWrapping() {
        ChatComponentText line = ModChat.mark(new ChatComponentText("a"));
        line.appendSibling(new ChatComponentText("b"));
        assertTrue(ModChat.isMarked(line));
        ChatComponentText wrapper = new ChatComponentText("[12:00] ");
        wrapper.appendSibling(line);
        assertTrue(ModChat.isMarked(wrapper));
    }

    @Test
    public void markIsIdempotent() {
        ChatComponentText line = ModChat.mark(ModChat.mark(new ChatComponentText("hello")));
        assertEquals(1, line.getSiblings().size());
    }

    @Test
    public void ordinaryChatIsNotMarked() {
        assertFalse(ModChat.isMarked(null));
        assertFalse(ModChat.isMarked(new ChatComponentText("Steve: hi")));
        ChatComponentText withEmpty = new ChatComponentText("Steve: hi");
        withEmpty.appendSibling(new ChatComponentText(""));
        assertFalse(ModChat.isMarked(withEmpty));
        ChatComponentText withInsertion = new ChatComponentText("Steve");
        withInsertion.getChatStyle().setInsertion("Steve");
        assertFalse(ModChat.isMarked(withInsertion));
    }
}
