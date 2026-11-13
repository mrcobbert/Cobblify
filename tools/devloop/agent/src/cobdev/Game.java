package cobdev;

import java.io.File;
import java.lang.instrument.ClassDefinition;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;

/**
 * The agent's side of the game: hot-swap through Instrumentation, the log, restarts, and a thin
 * forwarder to the bridge mod ({@code tools/devloop/bridge}), which does everything that touches
 * Minecraft. The agent lives on the app class loader and must not touch Minecraft, LWJGL or Pylon
 * classes itself: under Knot those load child-first, so the agent would get a second, dead copy.
 * Only JDK types cross over: the bridge publishes a {@link BiFunction} in the system properties.
 */
final class Game {

    private static final String MIXIN_PREFIX = "com.bedwarsqol.mixin.";
    private static final long STARTED_AT = System.currentTimeMillis();

    private Game() {
    }

    @SuppressWarnings("unchecked")
    private static BiFunction<String, Map<String, Object>, Object> bridge() {
        Object b = System.getProperties().get("cobdev.bridge");
        if (b == null) {
            throw new DevAgent.Refused("the game is still starting (the cobdev bridge mod has not initialized yet)");
        }
        return (BiFunction<String, Map<String, Object>, Object>) b;
    }

    /** Forwards one request to the bridge; its Refused (another class loader's class) keeps its meaning. */
    static Object call(String path, Map<String, ?> params) throws Exception {
        try {
            return bridge().apply(path, new HashMap<>(params));
        } catch (RuntimeException e) {
            throw unwrap(e);
        }
    }

    private static Exception unwrap(Throwable e) {
        Throwable t = e;
        while ((t instanceof InvocationTargetException || t instanceof ExecutionException
                || t.getClass() == RuntimeException.class) && t.getCause() != null) {
            t = t.getCause();
        }
        if (t.getClass().getSimpleName().equals("Refused")) return new DevAgent.Refused(t.getMessage());
        if (t instanceof Exception) return (Exception) t;
        return new RuntimeException(t);
    }

    static Map<String, Object> status() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("pid", ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
        out.put("uptimeMs", System.currentTimeMillis() - STARTED_AT);
        out.put("mode", System.getProperty("cobdev.mode", "dev"));
        out.put("classesDir", System.getProperty("cobdev.classes"));
        out.put("bridge", System.getProperties().get("cobdev.bridge") != null);
        if (!(Boolean) out.get("bridge")) {
            out.put("ready", false);
            out.put("reason", "the game is still starting (the cobdev bridge mod has not initialized yet)");
            return out;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> game = (Map<String, Object>) call("/status", new HashMap<String, Object>());
        out.putAll(game);
        return out;
    }

    static Map<String, Object> swap(List<String> names, boolean reinit) throws Exception {
        bridge();
        File dir = new File(System.getProperty("cobdev.classes", ""));
        if (!dir.isDirectory()) throw new DevAgent.Refused("this run has no class directory to swap from (prod mode?)");
        Map<String, byte[]> bytes = new HashMap<>();
        List<String> mixins = new ArrayList<>();
        for (String raw : names) {
            String n = raw.trim();
            if (n.isEmpty()) continue;
            if (!n.matches("[A-Za-z0-9_$.]+")) throw new DevAgent.Refused("bad class name: " + n);
            if (n.startsWith(MIXIN_PREFIX)) {
                mixins.add(n);
                continue;
            }
            bytes.put(n, Files.readAllBytes(new File(dir, n.replace('.', '/') + ".class").toPath()));
        }
        if (!mixins.isEmpty()) {
            return restartNeeded("mixins are applied at class load and cannot be swapped", mixins);
        }
        List<ClassDefinition> defs = new ArrayList<>();
        List<String> swapped = new ArrayList<>();
        for (Class<?> c : DevAgent.inst.getAllLoadedClasses()) {
            byte[] b = bytes.get(c.getName());
            if (b != null && c.getClassLoader() != null) {
                defs.add(new ClassDefinition(c, b));
                swapped.add(c.getName());
            }
        }
        List<String> notLoaded = new ArrayList<>(bytes.keySet());
        notLoaded.removeAll(swapped);
        Map<String, Object> params = new HashMap<>();
        params.put("task", (Callable<Object>) () -> {
            DevAgent.inst.redefineClasses(defs.toArray(new ClassDefinition[0]));
            return null;
        });
        params.put("reinit", reinit ? "1" : "0");
        try {
            call("/main", params);
        } catch (UnsupportedOperationException | LinkageError e) {
            return restartNeeded(e.toString(), swapped);
        }
        Map<String, Object> out = ok();
        out.put("swapped", swapped);
        out.put("notLoadedYet", notLoaded);
        return out;
    }

    static Map<String, Object> restart() throws Exception {
        File run = new File(System.getProperty("cobdev.run", "."));
        Files.write(new File(run, "restart-requested").toPath(), new byte[0]);
        return castMap(call("/shutdown", new HashMap<String, Object>()));
    }

    static Map<String, Object> log(int lines) throws Exception {
        File f = new File(System.getProperty("cobdev.run", "."), "logs/latest.log");
        List<String> all = Files.readAllLines(f.toPath(), StandardCharsets.ISO_8859_1);
        Map<String, Object> out = ok();
        out.put("lines", all.subList(Math.max(0, all.size() - Math.max(1, lines)), all.size()));
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    private static Map<String, Object> restartNeeded(String reason, List<String> classes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("needsRestart", true);
        m.put("reason", reason);
        m.put("classes", classes);
        return m;
    }
}
