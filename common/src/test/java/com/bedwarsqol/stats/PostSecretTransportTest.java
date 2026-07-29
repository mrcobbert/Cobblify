package com.bedwarsqol.stats;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.Certificate;
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Exercises {@link ScraperBackendClient#postSecret} end-to-end through the package-private
 * {@link ScraperBackendClient.SecretConnectionFactory} seam, with no live TLS endpoint and no
 * Minecraft classes. Proves the fail-closed transport contract: exactly one connection is opened,
 * every 3xx (same-origin, cross-origin, https->http downgrade) fails without following the
 * redirect, the secret is written once and never replayed, and pre-open validation failures open
 * nothing at all.
 */
public class PostSecretTransportTest {

    private static final String SECRET = "{\"key\":\"s3cr3t-value\"}";

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

    /** Records writes and header/redirect settings against an injectable response. Extends
     *  HttpsURLConnection so postSecret's HTTPS-specific path is the one under test. */
    private static final class RecordingHttpURLConnection extends HttpsURLConnection {
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        final List<String> headers = new ArrayList<String>();
        final int responseCode;
        final String responseBody;
        final String location;
        boolean followRedirectsFlag = true; // vanilla default; postSecret must flip this off
        int outputStreams = 0;

        RecordingHttpURLConnection(int responseCode, String responseBody, String location) throws IOException {
            super(new URL("https://placeholder.invalid"));
            this.responseCode = responseCode;
            this.responseBody = responseBody;
            this.location = location;
        }

        @Override
        public void setInstanceFollowRedirects(boolean follow) {
            this.followRedirectsFlag = follow;
        }

        @Override
        public void setRequestProperty(String key, String value) {
            headers.add(key + ": " + value);
        }

        @Override
        public OutputStream getOutputStream() {
            outputStreams++;
            return written;
        }

        @Override
        public int getResponseCode() {
            return responseCode;
        }

        @Override
        public String getHeaderField(String name) {
            return "Location".equalsIgnoreCase(name) ? location : null;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(
                    (responseBody == null ? "" : responseBody).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(
                    (responseBody == null ? "" : responseBody).getBytes(StandardCharsets.UTF_8));
        }

        boolean hasHeader(String key, String value) {
            return headers.contains(key + ": " + value);
        }

        String body() {
            return new String(written.toByteArray(), StandardCharsets.UTF_8);
        }

        @Override
        public void connect() { }

        @Override
        public String getCipherSuite() { return "TLS_TEST"; }

        @Override
        public Certificate[] getLocalCertificates() { return null; }

        @Override
        public Certificate[] getServerCertificates() { return new Certificate[0]; }

        @Override
        public void disconnect() { }

        @Override
        public boolean usingProxy() {
            return false;
        }
    }

    /** Counts opens and hands back the single prepared connection; records every opened URL. */
    private static final class RecordingFactory implements ScraperBackendClient.SecretConnectionFactory {
        final RecordingHttpURLConnection conn;
        final List<URL> opened = new ArrayList<URL>();

        RecordingFactory(RecordingHttpURLConnection conn) {
            this.conn = conn;
        }

        @Override
        public HttpURLConnection open(URL url) {
            opened.add(url);
            return conn;
        }
    }

    private RecordingFactory install(int code, String body, String location) throws IOException {
        RecordingFactory f = new RecordingFactory(new RecordingHttpURLConnection(code, body, location));
        ScraperBackendClient.secretConnectionFactory = f;
        return f;
    }

    @Test
    public void httpsSuccessSetsTokenWritesBodyAndSucceeds() throws IOException {
        RecordingFactory f = install(200, "{}", null);

        ScraperBackendClient.SecretPostResult r = ScraperBackendClient.postSecret(
                "https://worker.example.com", "/owner/key", "tok-123", SECRET);

        assertTrue(r.success);
        assertEquals(200, r.status);
        assertNull(r.error);
        assertEquals(1, f.opened.size());
        assertTrue(f.conn.hasHeader("X-BedwarsQol-Token", "tok-123"));
        assertFalse(f.conn.followRedirectsFlag);
        assertEquals(1, f.conn.outputStreams);
        assertEquals(SECRET, f.conn.body());
    }

    @Test
    public void sameOriginRedirect301FailsWithoutReplay() throws IOException {
        RecordingFactory f = install(301, "", "https://worker.example.com/moved");

        ScraperBackendClient.SecretPostResult r = ScraperBackendClient.postSecret(
                "https://worker.example.com", "/owner/key", "tok-123", SECRET);

        assertRedirectRejected(f, r, 301, "https://worker.example.com/moved");
    }

    @Test
    public void crossOriginRedirect302FailsWithoutReplay() throws IOException {
        RecordingFactory f = install(302, "", "https://evil.example.net/steal");

        ScraperBackendClient.SecretPostResult r = ScraperBackendClient.postSecret(
                "https://worker.example.com", "/owner/key", "tok-123", SECRET);

        assertRedirectRejected(f, r, 302, "https://evil.example.net/steal");
    }

    @Test
    public void httpsToHttpDowngrade301FailsWithoutReplay() throws IOException {
        RecordingFactory f = install(301, "", "http://worker.example.com/insecure");

        ScraperBackendClient.SecretPostResult r = ScraperBackendClient.postSecret(
                "https://worker.example.com", "/owner/key", "tok-123", SECRET);

        assertRedirectRejected(f, r, 301, "http://worker.example.com/insecure");
    }

    /** Shared assertions for every redirect variant: single open, failure, one write, no replay. */
    private void assertRedirectRejected(RecordingFactory f, ScraperBackendClient.SecretPostResult r,
            int expectedCode, String location) {
        assertFalse(r.success);
        assertEquals(expectedCode, r.status);
        assertEquals("redirect_rejected", r.error);
        // Exactly one connection opened, and never the redirect target -> no secret replay.
        assertEquals(1, f.opened.size());
        assertFalse(f.opened.contains(url(location)));
        assertFalse(f.conn.followRedirectsFlag);
        assertEquals(1, f.conn.outputStreams);
        // The secret was written once (initial request) and appears exactly once in the stream.
        assertEquals(SECRET, f.conn.body());
    }

    @Test
    public void temporaryRedirect307FailsWithoutReplay() throws IOException {
        RecordingFactory f = install(307, "", "https://worker.example.com/temp");

        ScraperBackendClient.SecretPostResult r = ScraperBackendClient.postSecret(
                "https://worker.example.com", "/owner/key", "tok-123", SECRET);

        assertRedirectRejected(f, r, 307, "https://worker.example.com/temp");
    }

    @Test
    public void permanentRedirect308FailsWithoutReplay() throws IOException {
        RecordingFactory f = install(308, "", "https://evil.example.net/perm");

        ScraperBackendClient.SecretPostResult r = ScraperBackendClient.postSecret(
                "https://worker.example.com", "/owner/key", "tok-123", SECRET);

        assertRedirectRejected(f, r, 308, "https://evil.example.net/perm");
    }

    @Test
    public void insecureSchemeIsRejectedBeforeAnyOpen() throws IOException {
        RecordingFactory f = install(200, "{}", null);

        ScraperBackendClient.SecretPostResult r = ScraperBackendClient.postSecret(
                "http://worker.example.com", "/owner/key", "tok-123", SECRET);

        assertNoRequestMade(f, r, "insecure_scheme");
    }

    @Test
    public void userinfoUrlIsRejectedBeforeAnyOpen() throws IOException {
        RecordingFactory f = install(200, "{}", null);

        ScraperBackendClient.SecretPostResult r = ScraperBackendClient.postSecret(
                "https://user:pass@worker.example.com", "/owner/key", "tok-123", SECRET);

        assertNoRequestMade(f, r, "userinfo_present");
    }

    /** Validation failure: factory never invoked, nothing opened, and the secret never leaves memory. */
    private void assertNoRequestMade(RecordingFactory f, ScraperBackendClient.SecretPostResult r, String error) {
        assertFalse(r.success);
        assertEquals(0, r.status);
        assertEquals(error, r.error);
        assertEquals(0, f.opened.size());
        assertEquals(0, f.conn.outputStreams);
        assertEquals("", f.conn.body());
    }

    private static URL url(String s) {
        try {
            return new URL(s);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
