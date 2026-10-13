package net.cobblify.guard;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The javaagent Cobblify registers in Lunar Client's JVM arguments, which Lunar passes to EVERY
 * game it starts. It starts the Weave loader only when the game is Minecraft 1.8.9, the one version
 * Cobblify's mod is for; on any other version it does nothing, so that game starts as if Cobblify
 * were not installed (Weave itself stops the game on versions it does not support).
 *
 * Rules:
 * - Weave starts only on 1.8.9, by Weave's own rule for it (a version naming 1.8.9, or "1.8"). A
 *   missing or unreadable version, a missing Weave jar, or any error before Weave is called leaves
 *   the game alone. Nothing here ever exits the JVM.
 * - The version is read the way Weave reads it: the weave.game.info property, else the
 *   "--version <v>" game argument in sun.java.command. That property also holds the game's access
 *   token, so it is matched in memory only: only a version-shaped value is ever written anywhere.
 * - Weave goes on the system class loader and gets this agent's Instrumentation, as if it had been
 *   registered directly (this jar's manifest grants the same Can-Retransform-Classes).
 * - Weave also needs Mojang's vanilla jar for the version, found the way Weave 1.3.3 finds it
 *   (vanillaJarFound); without it Weave stops the game with an error dialog, so the game is left
 *   to start without Cobblify instead (the launcher puts the jar back before its next launch).
 * - One line goes to logs/cobblify-guard.log next to this jar, so the launcher can tell a game on
 *   another version from one where Cobblify failed to load.
 *
 * Option: the Weave jar's file name, in this jar's folder ("-javaagent:<guard>=<weave jar>").
 */
public final class Guard {
    private static final String WEAVE_AGENT = "net.weavemc.loader.impl.bootstrap.AgentKt";
    private static final Pattern VERSION_ARG = Pattern.compile("--version\\s+(\\S+)");
    private static final Pattern VERSION_INFO = Pattern.compile("(?:^|,)\\s*version\\s*=\\s*([^,\\s]+)");
    private static final Pattern VERSION_SHAPE = Pattern.compile("[0-9][0-9A-Za-z._-]{0,31}");

    private Guard() {}

    public static void premain(String options, Instrumentation inst) {
        File dir = null;
        String version = null;
        Method weavePremain;
        try {
            dir = ownFolder();
            version = gameVersion();
            if (!isOneEightNine(version)) {
                note(dir, "Minecraft " + shown(version) + ", Weave not started");
                return;
            }
            // Weave's name for the version it matched, whatever the argument said ("1.8" too).
            if (!vanillaJarFound("1.8.9")) {
                note(dir, "Minecraft " + shown(version) + ", vanilla jar missing");
                return;
            }
            File weave = weaveJar(dir, options);
            if (weave == null) {
                note(dir, "Minecraft " + shown(version) + ", Weave jar missing");
                return;
            }
            inst.appendToSystemClassLoaderSearch(new JarFile(weave));
            Class<?> agent = Class.forName(WEAVE_AGENT, true, ClassLoader.getSystemClassLoader());
            weavePremain = agent.getMethod("premain", String.class, Instrumentation.class);
            note(dir, "Minecraft " + shown(version) + ", starting Weave");
        } catch (Throwable t) {
            note(dir, "Minecraft " + shown(version) + ", Weave not started (guard error)");
            return;
        }
        // From here on it is Weave's own start, exactly as when it was registered directly.
        try {
            weavePremain.invoke(null, null, inst);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static File ownFolder() throws Exception {
        return new File(Guard.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .getAbsoluteFile().getParentFile();
    }

    /** Weave's own test for 1.8.9 (MinecraftVersion.fromVersionName): names 1.8.9, or is "1.8". */
    static boolean isOneEightNine(String version) {
        return version != null && (version.contains("1.8.9") || version.equals("1.8"));
    }

    /**
     * Weave 1.3.3's FileManager.getVanillaMinecraftJar: versions/<v>/<v>.jar in the OS's Minecraft
     * folder, else a classpath entry ending in versions/<v>/<v>.jar or
     * libraries/com/mojang/minecraft/<v>/minecraft-<v>-client.jar.
     */
    static boolean vanillaJarFound(String version) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getenv("HOME");
        if (home == null) home = System.getenv("USERPROFILE");
        home = System.getProperty("user.home", home);
        File mc;
        if (os.contains("win")) {
            mc = new File(new File(new File(home, "AppData"), "Roaming"), ".minecraft");
        } else if (os.contains("mac")) {
            mc = new File(new File(new File(home, "Library"), "Application Support"), "minecraft");
        } else {
            mc = new File(home, ".minecraft");
        }
        if (new File(new File(new File(mc, "versions"), version), version + ".jar").exists()) return true;
        String cp = System.getProperty("java.class.path");
        if (cp == null) return false;
        String a = "versions" + File.separator + version + File.separator + version + ".jar";
        String b = "libraries" + File.separator + "com" + File.separator + "mojang" + File.separator + "minecraft"
            + File.separator + version + File.separator + "minecraft-" + version + "-client.jar";
        for (String entry : cp.split(File.pathSeparator)) {
            if (entry.endsWith(a) || entry.endsWith(b)) return true;
        }
        return false;
    }

    /** The game version, or null when it cannot be read. */
    static String gameVersion() {
        String info = System.getProperty("weave.game.info");
        if (info != null) {
            Matcher m = VERSION_INFO.matcher(info);
            return m.find() ? m.group(1) : null;
        }
        String command = System.getProperty("sun.java.command");
        if (command == null) return null;
        Matcher m = VERSION_ARG.matcher(command);
        return m.find() ? m.group(1) : null;
    }

    /** A version for the log: only a version-shaped value, never anything else from the command. */
    static String shown(String version) {
        return version != null && VERSION_SHAPE.matcher(version).matches() ? version : "unknown";
    }

    /** The Weave jar named by the option: a plain file name in this jar's folder, that exists. */
    static File weaveJar(File dir, String options) {
        if (dir == null || options == null) return null;
        String name = options.trim();
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.startsWith(".")) {
            return null;
        }
        File jar = new File(dir, name);
        return jar.isFile() ? jar : null;
    }

    /** Best effort: a log line must never stop the game. */
    private static void note(File dir, String line) {
        if (dir == null) return;
        try {
            File logs = new File(dir, "logs");
            if (!logs.isDirectory() && !logs.mkdirs()) return;
            Writer w = new OutputStreamWriter(new FileOutputStream(new File(logs, "cobblify-guard.log")), StandardCharsets.UTF_8);
            try {
                w.write("Cobblify guard: " + line + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
            // the log is a hint for the launcher, nothing more
        }
    }
}
