package com.bedwarsqol.rewind.watch;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** What a Rewind session puts back, and that one failing step never stops the rest (TASK A15). */
public class PlaybackRestoreTest {

    /** A client whose chat lines are "text@createdCounter". */
    static final class Client implements PlaybackRestore.Client {
        float timerSpeed = 1f;
        int thirdPerson = 1;
        int difficulty = 2;
        boolean paused;
        List<String> chat = new ArrayList<String>(Arrays.asList("hello@400", "gg@450"));
        List<String> sent = new ArrayList<String>(Arrays.asList("/bw"));
        int counter = 500;
        String throwOn = "";

        private void maybe(String step) {
            if (throwOn.contains(step)) {
                throw new IllegalStateException(step);
            }
        }

        @Override
        public float timerSpeed() {
            return timerSpeed;
        }

        @Override
        public void setTimerSpeed(float speed) {
            maybe("timer");
            timerSpeed = speed;
        }

        @Override
        public int thirdPersonView() {
            return thirdPerson;
        }

        @Override
        public void setThirdPersonView(int view) {
            maybe("f5");
            thirdPerson = view;
        }

        @Override
        public int difficulty() {
            return difficulty;
        }

        @Override
        public void setDifficulty(int id) {
            maybe("difficulty");
            difficulty = id;
        }

        @Override
        public void setGamePaused(boolean p) {
            maybe("paused");
            paused = p;
        }

        @Override
        public Object copyChat() {
            return new Object[]{new ArrayList<String>(chat), new ArrayList<String>(sent)};
        }

        @Override
        @SuppressWarnings("unchecked")
        public void restoreChat(Object saved) {
            maybe("chat");
            Object[] s = (Object[]) saved;
            chat = new ArrayList<String>((List<String>) s[0]);
            sent = new ArrayList<String>((List<String>) s[1]);
        }

        @Override
        public int updateCounter() {
            return counter;
        }

        @Override
        public void setUpdateCounter(int c) {
            maybe("counter");
            counter = c;
        }

        int age(int line) {
            return counter - Integer.parseInt(chat.get(line).split("@")[1]);
        }
    }

    /** What playback does to the client between capture and restore. */
    private static void play(Client c) {
        c.timerSpeed = 4f;
        c.thirdPerson = 0;
        c.difficulty = 0;
        c.paused = true;
        c.chat.clear();
        c.sent.clear();
        c.counter = 0;
        c.chat.add("recorded@3");
        c.counter = 1234;
    }

    @Test
    public void everythingCapturedComesBackAndChatKeepsItsAge() {
        Client c = new Client();
        int ageBefore = c.age(0);
        PlaybackRestore r = PlaybackRestore.capture(c);
        play(c);
        r.restoreSettings(c, null);
        assertEquals(1f, c.timerSpeed, 0f);
        assertEquals(1, c.thirdPerson);
        assertEquals(2, c.difficulty);
        assertFalse(c.paused);
        assertEquals("chat waits for the second half", Arrays.asList("recorded@3"), c.chat);
        r.restoreChat(c, null);
        assertEquals(Arrays.asList("hello@400", "gg@450"), c.chat);
        assertEquals(Arrays.asList("/bw"), c.sent);
        assertEquals(500, c.counter);
        assertEquals("a line 5 s old at entry is 5 s old after exit", ageBefore, c.age(0));
        assertEquals(100, c.age(0));
    }

    @Test
    public void theSavedChatIsACopyNotTheLiveList() {
        Client c = new Client();
        List<String> live = c.chat;
        PlaybackRestore r = PlaybackRestore.capture(c);
        live.clear();
        live.add("changed@1");
        r.restoreChat(c, null);
        assertEquals(Arrays.asList("hello@400", "gg@450"), c.chat);
    }

    @Test
    public void aThrowingStepIsReportedAndTheOthersStillRun() {
        Client c = new Client();
        PlaybackRestore r = PlaybackRestore.capture(c);
        play(c);
        c.throwOn = "timer chat";
        final List<String> failed = new ArrayList<String>();
        PlaybackRestore.Errors errors = (step, t) -> failed.add(step);
        r.restoreSettings(c, errors);
        r.restoreChat(c, errors);
        assertEquals(Arrays.asList("timerSpeed", "chat"), failed);
        assertEquals(4f, c.timerSpeed, 0f);
        assertEquals(1, c.thirdPerson);
        assertEquals(2, c.difficulty);
        assertFalse(c.paused);
        assertEquals(500, c.counter);
    }

    @Test
    public void eachHalfRunsOnce() {
        Client c = new Client();
        PlaybackRestore r = PlaybackRestore.capture(c);
        r.restoreSettings(c, null);
        r.restoreChat(c, null);
        assertTrue(r.settingsRestored() && r.chatRestored());
        play(c);
        r.restoreSettings(c, null);
        r.restoreChat(c, null);
        assertEquals("a second restore does not run", 4f, c.timerSpeed, 0f);
        assertEquals(Arrays.asList("recorded@3"), c.chat);
    }

    @Test
    public void aSpeedLeftBrokenIsNeverPutBack() {
        Client c = new Client();
        c.timerSpeed = 0f;
        PlaybackRestore r = PlaybackRestore.capture(c);
        c.timerSpeed = 2f;
        r.restoreSettings(c, null);
        assertEquals(1f, c.timerSpeed, 0f);
    }
}
