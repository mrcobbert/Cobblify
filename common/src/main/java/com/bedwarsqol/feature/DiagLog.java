package com.bedwarsqol.feature;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Tiny always-on diagnostic trail for the chat-tag pipeline, written to
 * {@code ~/.cobblify/chat-diag.log} (the same well-known dir on Forge and Lunar, so "send me
 * that file" is one instruction for every install). One line per lifecycle event of a tracked
 * chat line — receive/hoist, trust verdict, tag back-patch with how late it landed, and what a
 * world change wiped — so a "tags missing on his machine" report can be diagnosed from a single
 * file instead of a screen-share. Appends across launches (each session marked by its own SESSION
 * line) so games from earlier runs aren't lost, rolls to a fresh file once it passes the cap so it
 * can't grow unbounded, and silent on any I/O failure.
 */
public final class DiagLog {

    private static final long MAX_BYTES = 1024L * 1024L; // ~1MB: append until this, then roll

    private static PrintWriter out;
    private static long written;
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS");

    private DiagLog() {
    }

    public static synchronized void init(String header) {
        try {
            File dir = new File(System.getProperty("user.home"), ".cobblify");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File file = new File(dir, "chat-diag.log");
            // Append across launches so a game from an earlier session isn't wiped the moment you
            // relaunch; only start fresh (roll) once the file has grown past the cap, keeping it bounded.
            boolean append = file.length() < MAX_BYTES;
            out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file, append), "UTF-8"));
            written = append ? file.length() : 0L;
            log("SESSION " + header + " os=" + System.getProperty("os.name")
                    + " java=" + System.getProperty("java.version") + " start=" + new Date());
        } catch (Throwable t) {
            out = null;
        }
    }

    public static synchronized void log(String line) {
        if (out == null || written > MAX_BYTES) return;
        try {
            String s = TS.format(new Date()) + " " + line;
            out.println(s);
            out.flush();
            written += s.length() + 1;
            if (written > MAX_BYTES) {
                out.println("... capped at " + MAX_BYTES + " bytes");
                out.flush();
            }
        } catch (Throwable ignored) {
        }
    }
}
