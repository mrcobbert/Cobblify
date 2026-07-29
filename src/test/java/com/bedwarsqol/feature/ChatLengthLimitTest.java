package com.bedwarsqol.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class ChatLengthLimitTest {

    @Test
    public void leavesMessagesWithinTheLimitUntouched() {
        String message = repeat('a', ChatLengthLimit.VANILLA);
        assertSame(message, ChatLengthLimit.clamp(message, ChatLengthLimit.EXTENDED));
    }

    @Test
    public void trimsMessagesPastTheLimit() {
        String message = repeat('a', 300);
        assertEquals(ChatLengthLimit.EXTENDED,
                ChatLengthLimit.clamp(message, ChatLengthLimit.EXTENDED).length());
    }

    @Test
    public void trimsExactlyAtTheBoundary() {
        String message = repeat('a', ChatLengthLimit.EXTENDED + 1);
        assertEquals(repeat('a', ChatLengthLimit.EXTENDED),
                ChatLengthLimit.clamp(message, ChatLengthLimit.EXTENDED));
    }

    @Test
    public void toleratesNull() {
        assertNull(ChatLengthLimit.clamp(null, ChatLengthLimit.EXTENDED));
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }
}
