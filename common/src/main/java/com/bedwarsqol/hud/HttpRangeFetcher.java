package com.bedwarsqol.hud;

import com.bedwarsqol.rewind.watch.PlaybackGate;
import com.bedwarsqol.stats.Tls;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * One HTTP Range request to the URL it was made for, sending nothing but the range and generic headers (no cookies,
 * no credentials, no player data). It takes only a {@code 206} whose {@code Content-Range} is the range asked for, with
 * exactly that many bytes; anything else, a redirect or a full {@code 200} body included, is refused without reading
 * the body.
 *
 * <p>The request runs under a Rewind gate permit ({@link PlaybackGate}, PLAN "Isolation", layer 4), with the
 * connection attached and the permit re-checked before the request starts; while a recording plays, or once entry
 * aborts it, it fails with {@link PlaybackGate.PlaybackMutedException}.
 */
public final class HttpRangeFetcher implements EffectIconLoader.Fetcher {

    static final String USER_AGENT = "BedwarsQol/0.1 EffectIcons";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private final String url;

    public HttpRangeFetcher(String url) {
        this.url = url;
    }

    @Override
    public byte[] range(String url, long from, long to) throws IOException {
        if (!this.url.equals(url)) throw new IOException("not this fetcher's URL");
        if (from < 0 || to < from || to - from >= Integer.MAX_VALUE) throw new IOException("bad range");
        int n = (int) (to - from + 1);
        PlaybackGate.Permit permit = PlaybackGate.global().admit();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            permit.attach(conn);
            Tls.apply(conn);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Range", "bytes=" + from + "-" + to);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept-Encoding", "identity");
            permit.check(); // the request starts in getResponseCode
            int status = conn.getResponseCode();
            if (status != 206) throw new IOException("HTTP " + status + ", want 206");
            String contentRange = conn.getHeaderField("Content-Range");
            if (contentRange == null || !contentRange.startsWith("bytes " + from + "-" + to + "/")) {
                throw new IOException("Content-Range " + contentRange);
            }
            long length = conn.getContentLengthLong();
            if (length != -1 && length != n) throw new IOException("Content-Length " + length + ", want " + n);
            byte[] out = new byte[n];
            try (InputStream in = conn.getInputStream()) {
                int got = 0;
                while (got < n) {
                    int r = in.read(out, got, n - got);
                    if (r < 0) throw new IOException("short body: " + got + " of " + n);
                    got += r;
                }
            }
            return out;
        } catch (IOException e) {
            throw permit.failure(e);
        } finally {
            if (conn != null) conn.disconnect();
            permit.end();
        }
    }
}
