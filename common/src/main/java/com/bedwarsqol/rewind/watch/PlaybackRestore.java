package com.bedwarsqol.rewind.watch;

/**
 * The client state a Rewind session changes and puts back (PLAN section 3 step 3 and "Exit"; TASK A15).
 *
 * <p>Captured once, when gate entry completes (after the real connection was closed): the timer speed, the F5 view,
 * hidden HUD (F1), F3 and smooth camera (B3), the difficulty setting, the three chat lists (copies) and
 * {@code GuiIngame}'s update counter. On exit, in two halves: {@link #restoreSettings} before the gate opens and the
 * destination screen is shown (timer speed, F5 view, F1, F3, smooth camera, difficulty, the pause flag cleared),
 * then {@link #restoreChat} after the destination is shown (opening the title
 * screen clears chat when Keep Chat History is off), so every saved chat line has the age it had when playback
 * started: its age is the update counter minus the counter it was created at, and both come back together.
 *
 * <p>Every step runs on its own: a step that throws is skipped and the others still run. Each half runs at most
 * once. Pure; the client thread.
 */
public final class PlaybackRestore {

    /** The client fields the session reads and writes (Ornithe implements it with Minecraft calls). */
    public interface Client {
        float timerSpeed();

        void setTimerSpeed(float speed);

        /** {@code gameSettings.thirdPersonView}. */
        int thirdPersonView();

        void setThirdPersonView(int view);

        /** {@code gameSettings.hideGUI} (F1). */
        boolean hideGui();

        void setHideGui(boolean hide);

        /** {@code gameSettings.showDebugInfo} (F3). */
        boolean showDebugInfo();

        void setShowDebugInfo(boolean show);

        /** {@code gameSettings.smoothCamera} (F8). */
        boolean smoothCamera();

        void setSmoothCamera(boolean smooth);

        /** {@code gameSettings.difficulty} as its id (0-3); a recorded S01 overwrites it. */
        int difficulty();

        void setDifficulty(int id);

        /** {@code Minecraft.isGamePaused}, which the session forces while it controls the world. */
        void setGamePaused(boolean paused);

        /** Copies of the three chat lists (chat lines, drawn lines, sent messages); opaque to the session. */
        Object copyChat();

        /** Replaces the three chat lists with a {@link #copyChat()} result. */
        void restoreChat(Object saved);

        /** {@code GuiIngame.updateCounter}. */
        int updateCounter();

        void setUpdateCounter(int counter);
    }

    /** Reports a step that threw (counts only; the session logs it). */
    public interface Errors {
        void failed(String step, Throwable t);
    }

    private final float timerSpeed;
    private final int thirdPersonView;
    private final boolean hideGui;
    private final boolean showDebugInfo;
    private final boolean smoothCamera;
    private final int difficulty;
    private final Object chat;
    private final int updateCounter;
    private boolean settingsRestored;
    private boolean chatRestored;

    private PlaybackRestore(float timerSpeed, int thirdPersonView, boolean hideGui, boolean showDebugInfo,
                            boolean smoothCamera, int difficulty, Object chat, int updateCounter) {
        this.timerSpeed = timerSpeed;
        this.thirdPersonView = thirdPersonView;
        this.hideGui = hideGui;
        this.showDebugInfo = showDebugInfo;
        this.smoothCamera = smoothCamera;
        this.difficulty = difficulty;
        this.chat = chat;
        this.updateCounter = updateCounter;
    }

    /** Reads the state to put back later. Throws only if the client cannot be read at all. */
    public static PlaybackRestore capture(Client c) {
        float speed = c.timerSpeed();
        if (!(speed > 0f) || Float.isInfinite(speed)) {
            speed = 1f; // never put back a speed the session itself could have left
        }
        return new PlaybackRestore(speed, c.thirdPersonView(), c.hideGui(), c.showDebugInfo(), c.smoothCamera(),
                c.difficulty(), c.copyChat(), c.updateCounter());
    }

    /** Timer speed, F5 view, F1, F3, smooth camera, difficulty, pause flag cleared. Once. */
    public void restoreSettings(Client c, Errors errors) {
        if (settingsRestored) {
            return;
        }
        settingsRestored = true;
        try {
            c.setTimerSpeed(timerSpeed);
        } catch (Throwable t) {
            report(errors, "timerSpeed", t);
        }
        try {
            c.setThirdPersonView(thirdPersonView);
        } catch (Throwable t) {
            report(errors, "thirdPersonView", t);
        }
        try {
            c.setHideGui(hideGui);
        } catch (Throwable t) {
            report(errors, "hideGui", t);
        }
        try {
            c.setShowDebugInfo(showDebugInfo);
        } catch (Throwable t) {
            report(errors, "showDebugInfo", t);
        }
        try {
            c.setSmoothCamera(smoothCamera);
        } catch (Throwable t) {
            report(errors, "smoothCamera", t);
        }
        try {
            c.setDifficulty(difficulty);
        } catch (Throwable t) {
            report(errors, "difficulty", t);
        }
        try {
            c.setGamePaused(false);
        } catch (Throwable t) {
            report(errors, "paused", t);
        }
    }

    /** The three chat lists and the update counter, together. Once. */
    public void restoreChat(Client c, Errors errors) {
        if (chatRestored) {
            return;
        }
        chatRestored = true;
        try {
            c.restoreChat(chat);
        } catch (Throwable t) {
            report(errors, "chat", t);
        }
        try {
            c.setUpdateCounter(updateCounter);
        } catch (Throwable t) {
            report(errors, "updateCounter", t);
        }
    }

    public boolean settingsRestored() {
        return settingsRestored;
    }

    public boolean chatRestored() {
        return chatRestored;
    }

    public float timerSpeed() {
        return timerSpeed;
    }

    public int thirdPersonView() {
        return thirdPersonView;
    }

    public int difficulty() {
        return difficulty;
    }

    public int updateCounter() {
        return updateCounter;
    }

    private static void report(Errors errors, String step, Throwable t) {
        if (errors != null) {
            try {
                errors.failed(step, t);
            } catch (Throwable ignored) {
                // reporting never stops a restore
            }
        }
    }
}
