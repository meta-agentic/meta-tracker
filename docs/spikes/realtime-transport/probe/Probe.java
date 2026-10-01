import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The spike's client. A JDK-only single-file program ({@code java probe/Probe.java <mode> ...}),
 * so every timestamp is taken by one process on one clock.
 *
 * <pre>
 * latency   --write A[,B] --watch A[,B] --subscribers K --count N --rate R --writers C
 * race      --remote A --own B --trials N
 * dualwrite --slow A --fast B
 * payload   --write A --watch B --size BYTES
 * </pre>
 */
public class Probe {

    record Event(long nanos, long seq, String type, String data) {}

    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new HashMap<>();
        for (int i = 1; i + 1 < args.length; i += 2) {
            opt.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        switch (args.length == 0 ? "" : args[0]) {
            case "latency" -> latency(opt);
            case "race" -> race(opt);
            case "dualwrite" -> dualwrite(opt);
            case "payload" -> payload(opt);
            default -> {
                System.err.println("modes: latency | race | dualwrite | payload (see the class comment)");
                System.exit(2);
            }
        }
        System.exit(0);
    }

    // ---- latency: write on one instance, time receipt on clients of the other -----------

    static void latency(Map<String, String> opt) throws Exception {
        String[] writeTo = opt.get("write").split(",");
        String[] watch = opt.get("watch").split(",");
        int subscribers = Integer.parseInt(opt.getOrDefault("subscribers", "1"));
        int count = Integer.parseInt(opt.getOrDefault("count", "500"));
        double rate = Double.parseDouble(opt.getOrDefault("rate", "20"));
        int writers = Integer.parseInt(opt.getOrDefault("writers", "1"));
        String ws = opt.getOrDefault("workspace", "VEC");

        long after = snapshotSeq(writeTo[0], ws);
        long[] sent = new long[count];
        Arrays.fill(sent, -1);
        List<Map<Integer, Long>> received = new ArrayList<>();
        CountDownLatch connected = new CountDownLatch(subscribers);
        for (int s = 0; s < subscribers; s++) {
            Map<Integer, Long> got = new ConcurrentHashMap<>();
            received.add(got);
            subscribe(watch[s % watch.length], ws, after, connected, e -> {
                Matcher m = PROBE.matcher(e.data());
                if (m.find()) {
                    got.putIfAbsent(Integer.parseInt(m.group(1)), e.nanos());
                }
            });
        }
        if (!connected.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("subscribers did not connect");
        }
        Thread.sleep(200);

        AtomicInteger next = new AtomicInteger();
        long intervalNanos = (long) (1e9 / rate);
        long start = System.nanoTime();
        List<Thread> threads = new ArrayList<>();
        List<Long> responseMicros = Collections.synchronizedList(new ArrayList<>());
        for (int w = 0; w < writers; w++) {
            String item = ws + "-" + (1 + w % 2);
            threads.add(Thread.ofPlatform().start(() -> {
                int i;
                while ((i = next.getAndIncrement()) < count) {
                    long due = start + i * intervalNanos;
                    long wait = due - System.nanoTime();
                    if (wait > 0) {
                        sleepNanos(wait);
                    }
                    String base = writeTo[i % writeTo.length];
                    sent[i] = System.nanoTime();
                    send("PATCH", base + "/api/v1/workspaces/" + ws + "/items/" + item, "{\"fields\":{\"probe\":" + i + "}}", null);
                    responseMicros.add((System.nanoTime() - sent[i]) / 1000);
                }
            }));
        }
        for (Thread t : threads) {
            t.join();
        }
        Thread.sleep(3000);

        List<Double> perDelivery = new ArrayList<>();
        List<Double> perWriteWorst = new ArrayList<>();
        int missing = 0;
        for (int i = 0; i < count; i++) {
            double worst = -1;
            for (Map<Integer, Long> got : received) {
                Long at = got.get(i);
                if (at == null) {
                    missing++;
                    continue;
                }
                double ms = (at - sent[i]) / 1e6;
                perDelivery.add(ms);
                worst = Math.max(worst, ms);
            }
            if (worst >= 0) {
                perWriteWorst.add(worst);
            }
        }
        long expected = (long) count * subscribers;
        System.out.printf("writes=%d writers=%d rate=%.0f/s write-to=%s watch=%s subscribers=%d%n",
                count, writers, rate, String.join(",", writeTo), String.join(",", watch), subscribers);
        System.out.printf("delivered=%d/%d missing=%d%n", expected - missing, expected, missing);
        if (!perDelivery.isEmpty()) {
            System.out.println("per delivery (ms):              " + stats(perDelivery));
            System.out.println("per write, slowest client (ms): " + stats(perWriteWorst));
        }
        List<Double> response = responseMicros.stream().map(us -> us / 1000.0).toList();
        System.out.println("write HTTP round trip (ms):     " + stats(new ArrayList<>(response)));
    }

    // ---- race: my own write's response against a concurrent remote write's event ------

    static void race(Map<String, String> opt) throws Exception {
        String remote = opt.get("remote");
        String own = opt.get("own");
        int trials = Integer.parseInt(opt.getOrDefault("trials", "200"));
        String ws = "VEC";
        String item = "VEC-2";
        long after = snapshotSeq(own, ws);
        List<long[]> arrivals = new CopyOnWriteArrayList<>(); // {nanos, version}
        CountDownLatch connected = new CountDownLatch(1);
        subscribe(own, ws, after, connected, e -> {
            if (e.data().contains("\"key\":\"" + item + "\"")) {
                arrivals.add(new long[] {e.nanos(), version(e.data())});
            }
        });
        connected.await(10, TimeUnit.SECONDS);
        Thread.sleep(200);
        java.util.Random random = new java.util.Random(46);
        for (int t = 0; t < trials; t++) {
            // A colleague's write goes to A; mine goes to B a moment later, before theirs has answered.
            var theirs = HTTP.sendAsync(HttpRequest.newBuilder(URI.create(remote + "/api/v1/workspaces/" + ws + "/items/" + item))
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"title\":\"remote " + t + "\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            sleepNanos(random.nextInt(3_000_000));
            String body = send("PATCH", own + "/api/v1/workspaces/" + ws + "/items/" + item, "{\"title\":\"own " + t + "\"}", "tab-1");
            arrivals.add(new long[] {System.nanoTime(), version(body)});
            theirs.join();
            sleepNanos(20_000_000);
        }
        Thread.sleep(1500);

        List<long[]> ordered = new ArrayList<>(arrivals);
        ordered.sort((a, b) -> Long.compare(a[0], b[0]));
        long arrivalShown = -1;
        long highest = -1;
        int regressions = 0;
        long staleSince = -1;
        List<Double> staleWindows = new ArrayList<>();
        int dropped = 0;
        for (long[] a : ordered) {
            if (a[1] < highest) {
                regressions++;
                if (staleSince < 0) {
                    staleSince = a[0];
                }
            } else if (staleSince >= 0) {
                staleWindows.add((a[0] - staleSince) / 1e6);
                staleSince = -1;
            }
            if (a[1] <= highest) {
                dropped++;
            }
            arrivalShown = a[1];
            highest = Math.max(highest, a[1]);
        }
        System.out.printf("trials=%d: a remote write to %s and, 0-3 ms later, my own write to %s; my stream is on %s%n",
                trials, remote, own, own);
        System.out.printf("representations reaching my store (stream events + my responses): %d%n", ordered.size());
        System.out.printf("arrival-order store: went back to an older version %d times; final version %d%n", regressions, arrivalShown);
        if (!staleWindows.isEmpty()) {
            System.out.println("  showing the older version for (ms): " + stats(staleWindows));
        }
        System.out.printf("version rule:        went back 0 times; final version %d; dropped %d stale or echoed representations%n",
                highest, dropped);
    }

    // ---- dualwrite: a publish after commit, stalled on one instance ------------------

    static void dualwrite(Map<String, String> opt) throws Exception {
        String slow = opt.get("slow");
        String fast = opt.get("fast");
        String ws = "VEC";
        String item = "VEC-1";
        long after = snapshotSeq(fast, ws);
        List<Event> events = new CopyOnWriteArrayList<>();
        CountDownLatch connected = new CountDownLatch(1);
        subscribe(fast, ws, after, connected, events::add);
        connected.await(10, TimeUnit.SECONDS);
        Thread.sleep(200);
        long t0 = System.nanoTime();
        send("PATCH", slow + "/api/v1/workspaces/" + ws + "/items/" + item, "{\"title\":\"first\"}", null);
        sleepNanos(50_000_000);
        send("PATCH", fast + "/api/v1/workspaces/" + ws + "/items/" + item, "{\"title\":\"second\"}", null);
        Thread.sleep(1500);
        String arrivalTitle = null;
        long held = -1;
        String versionTitle = null;
        System.out.println("arrival order at a client of " + fast + ":");
        for (Event e : events) {
            if (!e.data().contains("\"key\":\"" + item + "\"")) {
                continue;
            }
            long v = version(e.data());
            String title = title(e.data());
            System.out.printf("  +%6.1f ms  seq=%d version=%d title=%s%n", (e.nanos() - t0) / 1e6, e.seq(), v, title);
            arrivalTitle = title;
            if (v > held) {
                held = v;
                versionTitle = title;
            }
        }
        String truth = title(send("GET", fast + "/api/v1/workspaces/" + ws + "/snapshot", null, null).replaceAll(".*\"key\":\"" + item + "\"", "{\"key\":\"" + item + "\""));
        System.out.println("database:                       title=" + truth);
        System.out.println("client, last arrival wins:      title=" + arrivalTitle + (truth.equals(arrivalTitle) ? "" : "   <- diverged"));
        System.out.println("client, highest version wins:   title=" + versionTitle + (truth.equals(versionTitle) ? "" : "   <- diverged"));
    }

    // ---- payload: an item larger than NOTIFY's 8000-byte limit -------------------------

    static void payload(Map<String, String> opt) throws Exception {
        String write = opt.get("write");
        String watch = opt.get("watch");
        int size = Integer.parseInt(opt.getOrDefault("size", "9000"));
        long after = snapshotSeq(watch, "VEC");
        List<Event> events = new CopyOnWriteArrayList<>();
        CountDownLatch connected = new CountDownLatch(1);
        subscribe(watch, "VEC", after, connected, events::add);
        connected.await(10, TimeUnit.SECONDS);
        Thread.sleep(200);
        String description = "x".repeat(size);
        String response = send("PATCH", write + "/api/v1/workspaces/VEC/items/VEC-2", "{\"fields\":{\"description\":\"" + description + "\"}}", null);
        Thread.sleep(1500);
        System.out.printf("write accepted, version %d; event bytes the client received: %s%n", version(response),
                events.isEmpty() ? "none (event lost)" : events.get(0).data().length() + " (delivered)");
    }

    // ---- plumbing ---------------------------------------------------------------------

    static final Pattern PROBE = Pattern.compile("\"probe\":(\\d+)");
    static final Pattern VERSION = Pattern.compile("\"version\":(\\d+)");
    static final Pattern TITLE = Pattern.compile("\"title\":\"([^\"]*)\"");
    static final Pattern SEQ = Pattern.compile("\"seq\":(\\d+)");

    static long version(String json) {
        Matcher m = VERSION.matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    static String title(String json) {
        Matcher m = TITLE.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static long snapshotSeq(String base, String ws) throws Exception {
        Matcher m = SEQ.matcher(send("GET", base + "/api/v1/workspaces/" + ws + "/snapshot", null, null));
        if (!m.find()) {
            throw new IllegalStateException("no seq in snapshot");
        }
        return Long.parseLong(m.group(1));
    }

    static String send(String method, String url, String json, String origin) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
        if (origin != null) {
            b.header("Vectis-Origin", origin);
        }
        b = json == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
                : b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        try {
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 300) {
                throw new IllegalStateException(method + " " + url + " -> " + r.statusCode() + " " + r.body());
            }
            return r.body();
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Opens an SSE stream on a daemon thread and hands each event to {@code sink} with its arrival time. */
    static void subscribe(String base, String ws, long after, CountDownLatch connected, Consumer<Event> sink) {
        Thread.ofPlatform().daemon().start(() -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/v1/workspaces/" + ws + "/events?after=" + after))
                    .header("Accept", "text/event-stream").build();
            try {
                HttpResponse<java.util.stream.Stream<String>> r = HTTP.send(req, HttpResponse.BodyHandlers.ofLines());
                long[] seq = {-1};
                String[] type = {null};
                StringBuilder data = new StringBuilder();
                r.body().forEach(line -> {
                    long now = System.nanoTime();
                    if (line.startsWith(":")) {
                        connected.countDown();
                    } else if (line.isEmpty()) {
                        if (!data.isEmpty()) {
                            sink.accept(new Event(now, seq[0], type[0], data.toString()));
                        }
                        data.setLength(0);
                        type[0] = null;
                    } else if (line.startsWith("id:")) {
                        seq[0] = Long.parseLong(line.substring(3).trim());
                    } else if (line.startsWith("event:")) {
                        type[0] = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring(5).replaceFirst("^ ", ""));
                    }
                });
            } catch (Exception e) {
                System.err.println("stream to " + base + " ended: " + e);
            }
        });
    }

    static String stats(List<Double> values) {
        List<Double> v = new ArrayList<>(values);
        Collections.sort(v);
        return String.format("n=%d p50=%.2f p95=%.2f p99=%.2f max=%.2f", v.size(), pct(v, 50), pct(v, 95), pct(v, 99), v.get(v.size() - 1));
    }

    static double pct(List<Double> sorted, double p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    static void sleepNanos(long nanos) {
        try {
            Thread.sleep(Duration.ofNanos(nanos));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
