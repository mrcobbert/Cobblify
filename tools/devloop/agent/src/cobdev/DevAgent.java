package cobdev;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Java agent loaded into the Cobblify dev client by start.sh ({@code -javaagent}). It serves a
 * localhost-only control API that the agent-side {@code cobdev} CLI calls: hot-swap changed classes,
 * open Cobblify screens, inject mouse and keyboard input, and read back the framebuffer as a PNG.
 *
 * <p>Never part of the mod jar. Everything that touches Minecraft lives in the dev-only bridge mod
 * ({@code tools/devloop/bridge}); {@link Game} forwards to it and keeps hot-swap, logs and restarts.
 */
public final class DevAgent {

    static Instrumentation inst;

    private DevAgent() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        inst = instrumentation;
        int port = Integer.getInteger("cobdev.port", 47821);
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            server.createContext("/", DevAgent::handle);
            // One request at a time: commands are ordered, and none of them may interleave on the game thread.
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cobdev-http");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            System.out.println("[cobdev] control API on http://127.0.0.1:" + port);
        } catch (IOException e) {
            System.err.println("[cobdev] control API failed to start on port " + port + ": " + e);
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        byte[] body;
        String type = "application/json";
        int code = 200;
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/shot")) {
                body = (byte[]) Game.call("/shot", q);
                type = "image/png";
            } else {
                body = Json.write(route(path, q)).getBytes(StandardCharsets.UTF_8);
            }
        } catch (Refused e) {
            code = 409;
            body = Json.write(error(e.getMessage())).getBytes(StandardCharsets.UTF_8);
        } catch (Throwable e) {
            code = 500;
            body = Json.write(error(e.toString())).getBytes(StandardCharsets.UTF_8);
        }
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static Map<String, Object> route(String path, Map<String, String> q) throws Exception {
        switch (path) {
            case "/status":
                return Game.status();
            case "/swap":
                return Game.swap(Arrays.asList(required(q, "classes").split(",")), "1".equals(q.get("reinit")));
            case "/restart":
                return Game.restart();
            case "/log":
                return Game.log(intParam(q, "lines", 100));
            case "/main":
            case "/shutdown":
                throw new Refused("unknown endpoint " + path);
            default:
                // Everything that touches the game lives in the bridge mod, which refuses what it does not know.
                return Game.castMap(Game.call(path, q));
        }
    }

    static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", message);
        return m;
    }

    private static Map<String, String> query(HttpExchange ex) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if ("POST".equals(ex.getRequestMethod())) {
            String form = new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
            raw = raw == null ? form : raw + "&" + form;
        }
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), "UTF-8");
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
            out.put(k, v);
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }

    private static String required(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null || v.isEmpty()) throw new Refused("missing parameter: " + key);
        return v;
    }

    private static int intParam(Map<String, String> q, String key, int def) {
        String v = q.get(key);
        return v == null || v.isEmpty() ? def : Integer.parseInt(v);
    }

    /** A request the game declines on purpose (wrong screen, not ready, on a server): HTTP 409. */
    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }
}
