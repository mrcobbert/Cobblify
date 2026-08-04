package com.bedwarsqol.stats;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import javax.net.ssl.HttpsURLConnection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ProviderKeySubmitter} contract, exercised end-to-end through the real
 * {@link ScraperBackendClient#postSecret} via the package-private connection-factory seam and
 * synchronous executors: payload escaping, the 8-200 length gate (no transport touched on
 * rejection), provider-to-path mapping (URCHIN -&gt; /urchin/key, SERAPH -&gt; /seraph/key), one
 * captured {@link BackendTarget} per submission, set/clear x success/failure dispatch to the right
 * callbacks (invalidate only on set success; strip BEFORE the request on clear), and that a
 * submission never writes key material to stdout/stderr.
 */
public class ProviderKeySubmitterTest {

    private static final Executor DIRECT = new Executor() {
        @Override
        public void execute(Runnable r) {
            r.run();
        }
    };

    private static final String KEY = "urchin-key-0123456789abcdef";

    @After
    public void restoreFactory() {
        ScraperBackendClient.secretConnectionFactory =
                new ScraperBackendClient.SecretConnectionFactory() {
                    @Override
                    public HttpURLConnection open(URL url) throws IOException {
                        return (HttpURLConnection) url.openConnection();
                    }
                };
    }

    /** Records the written body and headers against an injectable response code/body. */
    private static final class FakeConn extends HttpsURLConnection {
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        final List<String> headers = new ArrayList<String>();
        final int code;
        final String responseBody;

        FakeConn(int code, String responseBody) throws IOException {
            super(new URL("https://placeholder.invalid"));
            this.code = code;
            this.responseBody = responseBody;
        }

        @Override
        public void setRequestProperty(String key, String value) {
            headers.add(key + ": " + value);
        }

        @Override
        public OutputStream getOutputStream() {
            return written;
        }

        @Override
        public int getResponseCode() {
            return code;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8));
        }

        String body() {
            return new String(written.toByteArray(), StandardCharsets.UTF_8);
        }

        @Override
        public void connect() { }

        @Override
        public void disconnect() { }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public String getCipherSuite() { return "TLS_TEST"; }

        @Override
        public Certificate[] getLocalCertificates() { return null; }

        @Override
        public Certificate[] getServerCertificates() { return new Certificate[0]; }
    }

    /** Counts opens, records opened URLs, and appends "open" to a shared event log. */
    private static final class Factory implements ScraperBackendClient.SecretConnectionFactory {
        final FakeConn conn;
        final List<URL> opened = new ArrayList<URL>();
        final List<String> events;

        Factory(FakeConn conn, List<String> events) {
            this.conn = conn;
            this.events = events;
        }

        @Override
        public HttpURLConnection open(URL url) {
            opened.add(url);
            events.add("open");
            return conn;
        }
    }

    /** Keeps the last delivered result. */
    private static class Cb implements ProviderKeySubmitter.Callback {
        ScraperBackendClient.SecretPostResult last;
        int calls;

        @Override
        public void onResult(ScraperBackendClient.SecretPostResult result) {
            last = result;
            calls++;
        }
    }

    private final List<String> events = new ArrayList<String>();

    private Factory install(int code, String body) throws IOException {
        Factory f = new Factory(new FakeConn(code, body), events);
        ScraperBackendClient.secretConnectionFactory = f;
        return f;
    }

    private static ProviderKeySubmitter submitter() {
        return new ProviderKeySubmitter(DIRECT, DIRECT);
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    // ---- payload -------------------------------------------------------------

    @Test
    public void escapesQuotesBackslashesAndControlChars() {
        assertEquals("a\\\"b\\\\c\\nd\\re\\tf\\u0001g",
                ProviderKeySubmitter.jsonEscape("a\"b\\c\nd\re\tfg"));
        assertEquals("plain-key_123", ProviderKeySubmitter.jsonEscape("plain-key_123"));
    }

    @Test
    public void buildsSetAndClearBodies() {
        assertEquals("{\"key\":\"abcd1234\"}", ProviderKeySubmitter.setBody("abcd1234"));
        assertEquals("{\"key\":null}", ProviderKeySubmitter.CLEAR_BODY);
    }

    // ---- length gate ---------------------------------------------------------

    @Test
    public void rejectsShortAndLongKeysWithoutTouchingTransport() throws IOException {
        Factory f = install(200, "{}");
        Cb cb = new Cb();
        final boolean[] invalidated = {false};
        Runnable inv = new Runnable() {
            @Override
            public void run() {
                invalidated[0] = true;
            }
        };

        submitter().submitSet(ProviderKeySubmitter.Provider.URCHIN,
                new BackendTarget("https://worker.example.com", "tok"), repeat('k', 7), inv, cb);
        submitter().submitSet(ProviderKeySubmitter.Provider.URCHIN,
                new BackendTarget("https://worker.example.com", "tok"), repeat('k', 201), inv, cb);

        assertEquals(2, cb.calls);
        assertFalse(cb.last.success);
        assertEquals("invalid_key_length", cb.last.error);
        assertEquals(0, cb.last.status);
        assertEquals(0, f.opened.size());
        assertFalse(invalidated[0]);
    }

    @Test
    public void acceptsBoundaryLengths() throws IOException {
        Factory f = install(200, "{}");
        Cb cb = new Cb();

        submitter().submitSet(ProviderKeySubmitter.Provider.URCHIN,
                new BackendTarget("https://worker.example.com", "tok"), repeat('k', 8), null, cb);
        assertTrue(cb.last.success);

        submitter().submitSet(ProviderKeySubmitter.Provider.URCHIN,
                new BackendTarget("https://worker.example.com", "tok"), repeat('k', 200), null, cb);
        assertTrue(cb.last.success);
        assertEquals(2, f.opened.size());
    }

    // ---- provider-to-path + one BackendTarget per submission -----------------

    @Test
    public void urchinSubmissionPostsTheTargetPairToTheUrchinPath() throws IOException {
        Factory f = install(200, "{}");
        Cb cb = new Cb();

        submitter().submitSet(ProviderKeySubmitter.Provider.URCHIN,
                new BackendTarget("https://worker.example.com", "tok-urchin"), KEY, null, cb);

        assertEquals(1, f.opened.size());
        assertEquals("https://worker.example.com/urchin/key", f.opened.get(0).toString());
        assertTrue(f.conn.headers.contains("X-BedwarsQol-Token: tok-urchin"));
        assertEquals("{\"key\":\"" + KEY + "\"}", f.conn.body());
        assertTrue(cb.last.success);
    }

    @Test
    public void seraphSubmissionPostsTheTargetPairToTheSeraphPath() throws IOException {
        Factory f = install(200, "{}");
        Cb cb = new Cb();

        submitter().submitSet(ProviderKeySubmitter.Provider.SERAPH,
                new BackendTarget("https://worker.example.com", "tok-seraph"), KEY, null, cb);

        assertEquals(1, f.opened.size());
        assertEquals("https://worker.example.com/seraph/key", f.opened.get(0).toString());
        assertTrue(f.conn.headers.contains("X-BedwarsQol-Token: tok-seraph"));
    }

    @Test
    public void clearPostsToTheProviderPathToo() throws IOException {
        Factory f = install(200, "{}");

        submitter().submitClear(ProviderKeySubmitter.Provider.SERAPH,
                new BackendTarget("https://worker.example.com", "tok"), null, new Cb());

        assertEquals("https://worker.example.com/seraph/key", f.opened.get(0).toString());
        assertEquals("{\"key\":null}", f.conn.body());
    }

    // ---- set/clear x success/failure dispatch --------------------------------

    @Test
    public void setSuccessInvalidatesOnClientThreadBeforeTheCallback() throws IOException {
        install(200, "{}");
        Cb cb = new Cb() {
            @Override
            public void onResult(ScraperBackendClient.SecretPostResult result) {
                events.add("callback");
                super.onResult(result);
            }
        };

        submitter().submitSet(ProviderKeySubmitter.Provider.URCHIN,
                new BackendTarget("https://worker.example.com", "tok"), KEY,
                new Runnable() {
                    @Override
                    public void run() {
                        events.add("invalidate");
                    }
                }, cb);

        assertTrue(cb.last.success);
        assertEquals("open then invalidate then callback",
                "[open, invalidate, callback]", events.toString());
    }

    @Test
    public void setFailureSkipsInvalidateButStillCallsBack() throws IOException {
        install(500, "{\"error\":\"boom\"}");
        Cb cb = new Cb();
        final boolean[] invalidated = {false};

        submitter().submitSet(ProviderKeySubmitter.Provider.URCHIN,
                new BackendTarget("https://worker.example.com", "tok"), KEY,
                new Runnable() {
                    @Override
                    public void run() {
                        invalidated[0] = true;
                    }
                }, cb);

        assertEquals(1, cb.calls);
        assertFalse(cb.last.success);
        assertEquals(500, cb.last.status);
        assertFalse(invalidated[0]);
    }

    @Test
    public void clearStripsLocalTagsBeforeTheRequestAndReportsSuccess() throws IOException {
        install(200, "{}");
        Cb cb = new Cb();

        submitter().submitClear(ProviderKeySubmitter.Provider.URCHIN,
                new BackendTarget("https://worker.example.com", "tok"),
                new Runnable() {
                    @Override
                    public void run() {
                        events.add("strip");
                    }
                }, cb);

        assertTrue(cb.last.success);
        assertEquals("strip must precede the network submit",
                "[strip, open]", events.toString());
    }

    @Test
    public void clearFailureWasStillStrippedFirst() throws IOException {
        install(500, "{}");
        Cb cb = new Cb();

        submitter().submitClear(ProviderKeySubmitter.Provider.URCHIN,
                new BackendTarget("https://worker.example.com", "tok"),
                new Runnable() {
                    @Override
                    public void run() {
                        events.add("strip");
                    }
                }, cb);

        assertFalse(cb.last.success);
        assertEquals("[strip, open]", events.toString());
    }

    // ---- hygiene -------------------------------------------------------------

    @Test
    public void submissionNeverWritesKeyMaterialToStdoutOrStderr() throws IOException {
        install(200, "{}");
        PrintStream oldOut = System.out, oldErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, "UTF-8"));
            System.setErr(new PrintStream(err, true, "UTF-8"));
            submitter().submitSet(ProviderKeySubmitter.Provider.URCHIN,
                    new BackendTarget("https://worker.example.com", "tok"), KEY, null, new Cb());
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
        assertFalse(out.toString("UTF-8").contains(KEY));
        assertFalse(err.toString("UTF-8").contains(KEY));
    }

    // ---- mask helper ---------------------------------------------------------


    @Test
    public void providerPathsAreFixed() {
        assertEquals("/urchin/key", ProviderKeySubmitter.Provider.URCHIN.path);
        assertEquals("/seraph/key", ProviderKeySubmitter.Provider.SERAPH.path);
        assertNotNull(ProviderKeySubmitter.Provider.valueOf("URCHIN"));
    }
}
