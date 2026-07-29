package com.bedwarsqol.stats;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.KeyStore;

/**
 * Backs the mod's outbound HTTPS calls with a bundled, up-to-date CA truststore.
 *
 * Some launchers (e.g. Mojang's bundled {@code jre-legacy}) ship a very old Java 8 whose
 * {@code cacerts} contains expired CA certificates, which makes the TLS handshake fail with
 * {@code CertificateExpiredException: NotAfter: ...} when contacting Cloudflare/Mojang/Hypixel.
 * Pointing our connections at a current CA set fixes that without the user touching their Java.
 *
 * This is full certificate validation against a real, current CA set — not a trust-all hack.
 * If the bundled store can't be loaded for any reason we silently fall back to the JVM default.
 */
public final class Tls {

    private static volatile SSLSocketFactory factory;
    private static volatile boolean failed;

    private Tls() {}

    /** Applies the bundled truststore to an HTTPS connection. No-op on plain HTTP or if unavailable. */
    public static void apply(HttpURLConnection conn) {
        if (!(conn instanceof HttpsURLConnection)) return;
        SSLSocketFactory f = socketFactory();
        if (f != null) ((HttpsURLConnection) conn).setSSLSocketFactory(f);
    }

    private static SSLSocketFactory socketFactory() {
        if (factory != null || failed) return factory;
        synchronized (Tls.class) {
            if (factory != null || failed) return factory;
            try {
                factory = build();
            } catch (Throwable t) {
                failed = true; // fall back to the JVM default truststore
            }
            return factory;
        }
    }

    private static SSLSocketFactory build() throws Exception {
        KeyStore ks = load();
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx.getSocketFactory();
    }

    private static KeyStore load() throws Exception {
        char[] pw = "changeit".toCharArray();
        Exception last = null;
        // The bundled file is JKS; PKCS12 is a fallback in case it is ever swapped out.
        for (String type : new String[] {"JKS", "PKCS12"}) {
            try (InputStream in = Tls.class.getResourceAsStream("/bedwarsqol/cacerts")) {
                if (in == null) throw new Exception("bundled cacerts resource missing");
                KeyStore ks = KeyStore.getInstance(type);
                ks.load(in, pw);
                return ks;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("cannot load bundled truststore");
    }
}
