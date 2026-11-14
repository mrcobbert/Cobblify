package com.bedwarsqol.hud;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/** The Range fetcher against a local server: it sends only the range and generic headers, and takes only an exact 206. */
public class HttpRangeFetcherTest {

    private HttpServer server;
    private String url;
    private final AtomicReference<HttpExchange> seen = new AtomicReference<HttpExchange>();
    private volatile HttpHandler reply;

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                seen.set(ex);
                reply.handle(ex);
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/objects/abc/client.jar";
    }

    @After
    public void stop() {
        server.stop(0);
    }

    private static HttpHandler send(final int status, final String contentRange, final byte[] body) {
        return new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                if (contentRange != null) ex.getResponseHeaders().add("Content-Range", contentRange);
                ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                OutputStream out = ex.getResponseBody();
                out.write(body);
                out.close();
            }
        };
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 7);
        return b;
    }

    private void refused(long from, long to) {
        try {
            new HttpRangeFetcher(url).range(url, from, to);
            fail("must refuse");
        } catch (IOException expected) {
            // fine
        }
    }

    @Test
    public void sendsTheRangeAndOnlyGenericHeaders() throws IOException {
        reply = send(206, "bytes 100-109/5000", bytes(10));
        new HttpRangeFetcher(url).range(url, 100, 109);
        HttpExchange ex = seen.get();
        assertEquals("GET", ex.getRequestMethod());
        assertEquals("bytes=100-109", ex.getRequestHeaders().getFirst("Range"));
        assertEquals("BedwarsQol/0.1 EffectIcons", ex.getRequestHeaders().getFirst("User-Agent"));
        assertEquals("identity", ex.getRequestHeaders().getFirst("Accept-Encoding"));
        assertNull(ex.getRequestHeaders().getFirst("Cookie"));
        assertNull(ex.getRequestHeaders().getFirst("Authorization"));
        assertEquals("/v1/objects/abc/client.jar", ex.getRequestURI().toString());
    }

    @Test
    public void acceptsAnExact206() throws IOException {
        reply = send(206, "bytes 100-109/5000", bytes(10));
        assertArrayEquals(bytes(10), new HttpRangeFetcher(url).range(url, 100, 109));
    }

    @Test
    public void refusesA200() {
        reply = send(200, null, bytes(10));
        refused(100, 109);
    }

    @Test
    public void refusesAWrongContentRangeOrLength() {
        reply = send(206, "bytes 0-9/5000", bytes(10));
        refused(100, 109);
        reply = send(206, null, bytes(10));
        refused(100, 109);
        reply = send(206, "bytes 100-109/5000", bytes(9));
        refused(100, 109);
        reply = send(206, "bytes 100-109/5000", bytes(11));
        refused(100, 109);
    }

    @Test
    public void refusesARedirect() {
        reply = new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                ex.getResponseHeaders().add("Location", url + "?moved");
                ex.sendResponseHeaders(302, -1);
                ex.close();
            }
        };
        refused(100, 109);
        assertFalse("followed no redirect", seen.get().getRequestURI().toString().contains("moved"));
    }

    @Test
    public void fetchesOnlyItsOwnUrl() {
        reply = send(206, "bytes 100-109/5000", bytes(10));
        try {
            new HttpRangeFetcher(url).range(url + "x", 100, 109);
            fail("must refuse another URL");
        } catch (IOException expected) {
            // fine
        }
        assertNull("no request made", seen.get());
    }
}
