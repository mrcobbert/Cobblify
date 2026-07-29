package bench;

import com.bedwarsqol.stats.ScraperBackendClient;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Streaming batch check: prints each player's ARRIVAL time so we can see results trickling in. */
public class BatchTest {
    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://127.0.0.1:8787";
        List<String> names = Arrays.asList(
                "gamerboy80", "Technoblade",
                "Ph1LzA", "Antfrost", "Hbomb94", "Eret", "Fundy", "Tubbo", "Ranboo", "WilburSoot");

        System.out.println("== streaming batch of " + names.size() + " (arrival times) ==");
        final long t0 = System.nanoTime();
        final AtomicInteger n = new AtomicInteger();
        ScraperBackendClient.fetchBatchStreaming(names, base, "",
                (name, s) -> {
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    System.out.printf("  +%5dms  #%-2d %-14s state=%-12s FK=%d%n",
                            ms, n.incrementAndGet(), name, s.state, s.overall.finalKills);
                });
        long total = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  total " + total + "ms for " + names.size() + " players ("
                + n.get() + " counters)");
    }
}
