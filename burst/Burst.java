import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * G18: on-sale stampede against a live URL (Java 21, no dependencies).
 * Run: {@code java burst/Burst.java <BASE_URL>}. Prints outcome distribution,
 * latency percentiles, per-check verdicts; exits non-zero on any failure.
 */
public class Burst {

    record Cfg(String base, String adminToken, int showSeats, int hotUsers, String hotSeat,
            int stampedeRequests, int hotSet, int stampedeUsers, int idemRetries, int stampedeConcurrency,
            int setupConcurrency, int stormConcurrency) {
    }

    record Resp(int status, String body, String replayed, long latencyMs, String netError) {
    }

    static Cfg cfg;
    static HttpClient client;
    static final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
    static final Map<String, AtomicInteger> netErrors = new ConcurrentHashMap<>();
    static final List<String> failures = Collections.synchronizedList(new ArrayList<>());
    static final AtomicLong client201s = new AtomicLong();
    static final Set<String> confirmedSeats = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println("usage: java burst/Burst.java <BASE_URL>");
            System.out.println("env: ADMIN_TOKEN SHOW_SEATS HOT_USERS HOT_SEAT STAMPEDE_REQUESTS "
                    + "HOT_SET STAMPEDE_USERS IDEM_RETRIES STAMPEDE_CONCURRENCY SETUP_CONCURRENCY STORM_CONCURRENCY");
            return;
        }
        if (args.length < 1) {
            System.err.println("usage: java burst/Burst.java <BASE_URL>");
            System.exit(2);
        }
        String base = args[0].endsWith("/") ? args[0].substring(0, args[0].length() - 1) : args[0];
        cfg = new Cfg(base, env("ADMIN_TOKEN", "dev-admin-token"), envInt("SHOW_SEATS", 20000),
                envInt("HOT_USERS", 500), System.getenv().getOrDefault("HOT_SEAT", "A12"),
                envInt("STAMPEDE_REQUESTS", 20000), envInt("HOT_SET", 10), envInt("STAMPEDE_USERS", 200),
                envInt("IDEM_RETRIES", 30), envInt("STAMPEDE_CONCURRENCY", 1000),
                envInt("SETUP_CONCURRENCY", 50), envInt("STORM_CONCURRENCY", 500));
        client = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(10)).build();

        System.out.println("== setup: readiness + admin show ==");
        boolean up = false;
        for (int i = 1; i <= 10 && !up; i++) {
            up = ready();
            if (!up) {
                System.out.println("  waiting for readiness (" + i + "/10, cold start?) ...");
                Thread.sleep(10000);
            }
        }
        expect(up, "readiness is 200");
        Map<String, Double> metricsBefore = scrapeMetrics();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < cfg.showSeats(); i++) {
            labels.add("A" + i);
        }
        List<String> extras = new ArrayList<>(List.of("SPOOF1", "IDEM1", "IDEM2", "CANCEL1"));
        for (int i = 0; i < 10; i++) {
            extras.add("LIMIT" + i);
        }
        labels.addAll(extras);
        StringBuilder seatsJson = new StringBuilder("[");
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) {
                seatsJson.append(',');
            }
            seatsJson.append('"').append(labels.get(i)).append('"');
        }
        seatsJson.append(']');
        Resp created = call("POST", "/shows", cfg.adminToken(),
                "{\"name\":\"burst\",\"seats\":" + seatsJson + ",\"price_paise\":25000}");
        check(created.status() == 201, "setup show returns 201 (got " + created.status() + ")");
        String showId = str(created.body(), "id");
        check(showId != null, "setup returns show id");

        System.out.println("== hot-seat storm: " + cfg.hotUsers() + " users x " + cfg.hotSeat() + " ==");
        List<String> hotTokens = fetchTokens("hot-u-", cfg.hotUsers());
        var stormCodes = new ConcurrentHashMap<String, AtomicInteger>();
        var stormWinners = ConcurrentHashMap.<String>newKeySet();
        var stormGate = new java.util.concurrent.Semaphore(cfg.stormConcurrency());
        runParallel(cfg.hotUsers(), i -> {
            try {
                stormGate.acquire();
                try {
                    Resp r = callWithRetry("POST", "/shows/" + showId + "/reserve", hotTokens.get(i),
                            "{\"seats\":[\"" + cfg.hotSeat() + "\"],\"idempotency_key\":\"storm-" + i + "\"}");
                    if (r.status() == 200 && "true".equals(r.replayed())) {
                        String id = str(r.body(), "reservation_id");
                        if (id != null) {
                            stormWinners.add(id);
                        }
                        stormCodes.computeIfAbsent("200:replay-resolved", k -> new AtomicInteger())
                                .incrementAndGet();
                    } else {
                        tally(r, stormCodes, List.of(cfg.hotSeat()), stormWinners);
                    }
                } finally {
                    stormGate.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        });
        printCodes("storm", stormCodes);
        check(count(stormCodes, "201") + count(stormCodes, "200:replay-resolved") >= 1,
                "hot seat has a winner");
        check(count(stormCodes, "NET") == 0, "zero storm network errors");
        check(count(stormCodes, "409:SEAT_TAKEN") == cfg.hotUsers()
                - count(stormCodes, "201") - count(stormCodes, "200:replay-resolved"),
                "losers get 409 SEAT_TAKEN");
        check(stormWinners.size() == 1, "single winning reservation");

        System.out.println("== on-sale stampede: " + cfg.stampedeRequests() + " requests ==");
        List<String> stampedeTokens = fetchTokens("st-u-", cfg.stampedeUsers());
        var stampCodes = new ConcurrentHashMap<String, AtomicInteger>();
        record LastReq(String key, String body) {
        }
        var lastReq = new ConcurrentHashMap<Integer, LastReq>();
        long stampStart = System.nanoTime();
        // Cap in-flight requests below the server's max-connections: 20k total
        // requests still fire, but as sustained pressure rather than one
        // instant socket pile-on the transport can't absorb.
        var inFlight = new java.util.concurrent.Semaphore(cfg.stampedeConcurrency());
        runParallel(cfg.stampedeRequests(), i -> {
            int user = i % cfg.stampedeUsers();
            String body;
            String key = "st-" + i;
            LastReq prev = lastReq.get(user);
            if (i % 20 == 19 && prev != null) {
                key = prev.key();
                body = prev.body();
            } else {
                body = stampedeBody(i);
                lastReq.put(user, new LastReq(key, body));
            }
            Resp r;
            try {
                inFlight.acquire();
                try {
                    r = callWithRetry("POST", "/shows/" + showId + "/reserve", stampedeTokens.get(user),
                            body.replace("\"idempotency_key\":\"K\"", "\"idempotency_key\":\"" + key + "\""));
                } finally {
                    inFlight.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            tallyReplayAware(r, stampCodes, body, ConcurrentHashMap.newKeySet());
        });
        long stampSecs = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - stampStart));
        check(count(stampCodes, "5xx") == 0, "zero 5xx across stampede");
        check(count(stampCodes, "NET") == 0, "zero network errors");
        printCodes("stampede", stampCodes);

        System.out.println("== idempotent retries x" + cfg.idemRetries() + " ==");
        String idemToken = token("idem-u");
        var seenIds = ConcurrentHashMap.<String>newKeySet();
        var replayCodes = new ConcurrentHashMap<String, AtomicInteger>();
        runParallel(cfg.idemRetries(), i -> {
            Resp r = callWithRetry("POST", "/shows/" + showId + "/reserve", idemToken,
                    "{\"seats\":[\"IDEM1\"],\"idempotency_key\":\"idem-1\"}");
            if (r.status() == 201) {
                tally(r, replayCodes, List.of("IDEM1"), ConcurrentHashMap.newKeySet());
                String id = str(r.body(), "reservation_id");
                if (id != null) {
                    seenIds.add(id);
                }
            } else if (r.status() == 200 && "true".equals(r.replayed())) {
                String id = str(r.body(), "reservation_id");
                if (id != null) {
                    seenIds.add(id);
                }
                replayCodes.computeIfAbsent(r.status() + (r.status() == 200 ? ":replay" : ""), k -> new AtomicInteger())
                        .incrementAndGet();
            } else {
                replayCodes.computeIfAbsent(classify(r), k -> new AtomicInteger()).incrementAndGet();
            }
        });
        check(seenIds.size() == 1, "one reservation across retries");
        check(count(replayCodes, "200:replay") == cfg.idemRetries() - 1, "retries replay with 200");

        System.out.println("== same key, different seats ==");
        Resp reused = call("POST", "/shows/" + showId + "/reserve", idemToken,
                "{\"seats\":[\"IDEM2\"],\"idempotency_key\":\"idem-1\"}");
        check(reused.status() == 409 && "IDEMPOTENCY_KEY_REUSED".equals(code(reused.body())),
                "same key + different body is 409 IDEMPOTENCY_KEY_REUSED");

        System.out.println("== per-user limit: 10 parallel, limit 4 ==");
        String limitToken = token("limit-u");
        var limitCodes = new ConcurrentHashMap<String, AtomicInteger>();
        runParallel(10, i -> {
            Resp r = callWithRetry("POST", "/shows/" + showId + "/reserve", limitToken,
                    "{\"seats\":[\"LIMIT" + i + "\"],\"idempotency_key\":\"lim-" + i + "\"}");
            tally(r, limitCodes, List.of("LIMIT" + i), ConcurrentHashMap.newKeySet());
        });
        check(count(limitCodes, "201") == 4, "exactly 4 confirmed (got " + count(limitCodes, "201") + ")");
        check(count(limitCodes, "409:PER_USER_LIMIT") == 6, "rest declined PER_USER_LIMIT");

        System.out.println("== spoofed identity ==");
        String spoofToken = token("spoof-u");
        Resp spoof = callWithRetry("POST", "/shows/" + showId + "/reserve", spoofToken,
                "{\"seats\":[\"SPOOF1\"],\"idempotency_key\":\"spoof-1\",\"user_id\":\"mallory\"}");
        if (spoof.status() == 201) {
            tally(spoof, new ConcurrentHashMap<>(), List.of("SPOOF1"), ConcurrentHashMap.newKeySet());
        }
        check(spoof.status() == 201 && "spoof-u".equals(str(spoof.body(), "user_id")),
                "reservation owned by token user, body user_id ignored");

        System.out.println("== cancel authorisation ==");
        String canceller = token("cancel-u");
        String other = token("cancel-other");
        Resp booked = callWithRetry("POST", "/shows/" + showId + "/reserve", canceller,
                "{\"seats\":[\"CANCEL1\"],\"idempotency_key\":\"cancel-1\"}");
        if (booked.status() == 201) {
            tally(booked, new ConcurrentHashMap<>(), List.of("CANCEL1"), ConcurrentHashMap.newKeySet());
        }
        String cancelId = str(booked.body(), "reservation_id");
        check(booked.status() == 201 && cancelId != null, "cancel fixture booked");
        Resp forbidden = callWithRetry("POST", "/reservations/" + cancelId + "/cancel", other, "{}");
        check(forbidden.status() == 403, "non-owner cancel is 403");
        Resp cancelled = callWithRetry("POST", "/reservations/" + cancelId + "/cancel", canceller, "{}");
        check(cancelled.status() == 200, "owner cancel is 200");
        Resp rebook = callWithRetry("POST", "/shows/" + showId + "/reserve", other,
                "{\"seats\":[\"CANCEL1\"],\"idempotency_key\":\"cancel-2\"}");
        if (rebook.status() == 201) {
            // Counted but not seat-tracked: CANCEL1 was legitimately freed by
            // the cancel above, so re-confirming it is not a double-sell.
            client201s.incrementAndGet();
        }
        check(rebook.status() == 201, "seat re-bookable after cancel");

        System.out.println("== reconciliation ==");
        Resp summary = callWithRetry("GET", "/shows/" + showId + "?summary=true", null, null);
        long available = num(summary.body(), "available");
        long held = num(summary.body(), "held");
        long confirmed = num(summary.body(), "confirmed");
        long total = num(summary.body(), "total_seats");
        check(summary.status() == 200 && available + held + confirmed == total,
                "available + held + confirmed == total_seats (" + available + "+" + held + "+" + confirmed + "="
                        + total + ")");
        Map<String, Double> metricsAfter = scrapeMetrics();
        long confirmedDelta = Math
                .round(metricsAfter.getOrDefault("bookmyseat_reservations_confirmed_total", -1.0)
                        - metricsBefore.getOrDefault("bookmyseat_reservations_confirmed_total", -1.0));
        check(confirmedDelta == client201s.get(),
                "metrics confirmed delta " + confirmedDelta + " == client 201s " + client201s.get());

        printReport(stampSecs);
        if (!failures.isEmpty()) {
            System.out.println("RESULT: FAIL (" + failures.size() + " checks)");
            failures.forEach(f -> System.out.println("  FAIL: " + f));
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    static String stampedeBody(int i) {
        java.util.Random rnd = new java.util.Random(i * 7919L);
        int n = 1 + rnd.nextInt(2);
        Set<String> seats = new java.util.LinkedHashSet<>();
        while (seats.size() < n) {
            if (rnd.nextDouble() < 0.7) {
                seats.add("A" + rnd.nextInt(cfg.hotSet()));
            } else {
                seats.add("A" + rnd.nextInt(cfg.showSeats()));
            }
        }
        StringBuilder sb = new StringBuilder("{\"seats\":[");
        boolean first = true;
        for (String s : seats) {
            if (!first) {
                sb.append(',');
            }
            sb.append('"').append(s).append('"');
            first = false;
        }
        return sb.append("],\"idempotency_key\":\"K\"}").toString();
    }

    static void tally(Resp r, Map<String, AtomicInteger> codes, List<String> seats, Set<String> winners) {
        String key = classify(r);
        codes.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        if (r.status() == 201) {
            client201s.incrementAndGet();
            String id = str(r.body(), "reservation_id");
            if (id != null) {
                winners.add(id);
            }
            for (String s : seats) {
                if (!confirmedSeats.add(s)) {
                    failures.add("seat confirmed twice: " + s);
                }
            }
        }
    }

    static void tallyReplayAware(Resp r, Map<String, AtomicInteger> codes, String body, Set<String> winners) {
        if (r.status() == 200 && "true".equals(r.replayed())) {
            codes.computeIfAbsent("200:replay", k -> new AtomicInteger()).incrementAndGet();
            return;
        }
        tally(r, codes, seatsOf(body), winners);
    }

    static List<String> seatsOf(String body) {
        List<String> out = new ArrayList<>();
        int i = body.indexOf('[');
        int j = body.indexOf(']', i);
        if (i < 0 || j < 0) {
            return out;
        }
        for (String part : body.substring(i + 1, j).split(",")) {
            out.add(part.replace("\"", "").trim());
        }
        return out;
    }

    static String classify(Resp r) {
        if (r.netError() != null) {
            return "NET";
        }
        if (r.status() >= 500) {
            return "5xx";
        }
        if (r.status() == 409) {
            return "409:" + code(r.body());
        }
        return String.valueOf(r.status());
    }

    static long count(Map<String, AtomicInteger> codes, String key) {
        AtomicInteger v = codes.get(key);
        return v == null ? 0 : v.get();
    }

    static void check(boolean ok, String label) {
        System.out.println((ok ? "  PASS " : "  FAIL ") + label);
        if (!ok) {
            failures.add(label);
        }
    }

    static void expect(boolean ok, String label) {
        check(ok, label);
        if (!ok) {
            System.out.println("RESULT: FAIL (setup)");
            System.exit(1);
        }
    }

    static void runParallel(int n, java.util.function.IntConsumer task) throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(n);
            for (int i = 0; i < n; i++) {
                final int k = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        task.accept(k);
                    } catch (Exception e) {
                        failures.add("task error: " + e);
                    } finally {
                        done.countDown();
                    }
                    return null;
                });
            }
            start.countDown();
            if (!done.await(15, TimeUnit.MINUTES)) {
                failures.add("timeout waiting for tasks");
            }
        }
    }

    static List<String> fetchTokens(String prefix, int n) throws Exception {
        List<String> tokens = new ArrayList<>(Collections.nCopies(n, null));
        var gate = new java.util.concurrent.Semaphore(cfg.setupConcurrency());
        runParallel(n, i -> {
            try {
                gate.acquire();
                try {
                    tokens.set(i, token(prefix + i));
                } finally {
                    gate.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return tokens;
    }

    static String token(String userId) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Resp r = call("POST", "/auth/token", null, "{\"user_id\":\"" + userId + "\"}");
                if (r.status() == 200) {
                    String token = str(r.body(), "token");
                    if (token != null) {
                        return token;
                    }
                    last = new IllegalStateException("no token in: " + r.body());
                } else {
                    last = new IllegalStateException(
                            "token for " + userId + " -> " + r.status() + " " + r.body());
                }
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(2000L * attempt);
        }
        throw new IllegalStateException("token for " + userId + " failed after retries", last);
    }

    static boolean ready() {
        try {
            Resp r = call("GET", "/health/ready", null, null);
            return r.status() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    static Map<String, Double> scrapeMetrics() {
        try {
            Resp r = callWithRetry("GET", "/metrics", null, null);
            Map<String, Double> out = new ConcurrentHashMap<>();
            for (String line : r.body().split("\n")) {
                if (line.startsWith("#") || line.isBlank()) {
                    continue;
                }
                int sp = line.lastIndexOf(' ');
                if (sp < 0) {
                    continue;
                }
                try {
                    out.put(line.substring(0, sp), Double.parseDouble(line.substring(sp + 1)));
                } catch (NumberFormatException ignored) {
                }
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    static Resp call(String method, String path, String token, String body) {
        long start = System.nanoTime();
        try {
            var builder = HttpRequest.newBuilder(URI.create(cfg.base() + path))
                    .timeout(Duration.ofSeconds(90));
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            if ("GET".equals(method)) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
            }
            HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            latencies.add(ms);
            String replayed = res.headers().firstValue("Idempotent-Replayed").orElse(null);
            return new Resp(res.statusCode(), res.body(), replayed, ms, null);
        } catch (Exception e) {
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            latencies.add(ms);
            String detail = String.valueOf(e.getMessage());
            String kind = e.getClass().getSimpleName() + ": "
                    + detail.substring(0, Math.min(80, detail.length()));
            netErrors.computeIfAbsent(kind, k -> new AtomicInteger()).incrementAndGet();
            return new Resp(-1, "", null, ms, e.toString());
        }
    }

    /**
     * Same key + same body retried: safe by idempotency (a lost response to a
     * committed attempt replays instead of double-booking) and safe for GETs
     * and owner-scoped cancels. Only transport failures retry — 4xx/5xx stand.
     */
    static Resp callWithRetry(String method, String path, String token, String body) {
        Resp r = null;
        for (int i = 1; i <= 3; i++) {
            r = call(method, path, token, body);
            if (r.netError() == null) {
                return r;
            }
            try {
                Thread.sleep(1000L * i);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return r;
            }
        }
        return r;
    }

    static String str(String json, String key) {
        if (json == null) {
            return null;
        }
        String q = "\"" + key + "\"";
        int i = json.indexOf(q);
        if (i < 0) {
            return null;
        }
        int c = json.indexOf(':', i + q.length());
        if (c < 0) {
            return null;
        }
        int s1 = json.indexOf('"', c + 1);
        if (s1 < 0) {
            return null;
        }
        int s2 = json.indexOf('"', s1 + 1);
        if (s2 < 0) {
            return null;
        }
        return json.substring(s1 + 1, s2);
    }

    static String code(String json) {
        if (json == null) {
            return "unknown";
        }
        int i = json.indexOf("\"code\"");
        if (i < 0) {
            return "unknown";
        }
        int s1 = json.indexOf('"', json.indexOf(':', i) + 1);
        int s2 = json.indexOf('"', s1 + 1);
        return s1 < 0 || s2 < 0 ? "unknown" : json.substring(s1 + 1, s2);
    }

    static long num(String json, String key) {
        if (json == null) {
            return -1;
        }
        String q = "\"" + key + "\"";
        int i = json.indexOf(q);
        if (i < 0) {
            return -1;
        }
        int c = json.indexOf(':', i + q.length());
        if (c < 0) {
            return -1;
        }
        int s = c + 1;
        while (s < json.length() && (json.charAt(s) == ' ' || json.charAt(s) == '"')) {
            s++;
        }
        int e = s;
        while (e < json.length() && (Character.isDigit(json.charAt(e)) || json.charAt(e) == '.')) {
            e++;
        }
        try {
            return (long) Double.parseDouble(json.substring(s, e));
        } catch (Exception ex) {
            return -1;
        }
    }

    static void printCodes(String phase, Map<String, AtomicInteger> codes) {
        var parts = new ArrayList<String>();
        codes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> parts.add(e.getKey() + "=" + e.getValue()));
        System.out.println("  [" + phase + "] " + String.join(" ", parts));
    }

    static void printReport(long stampSecs) {
        if (!netErrors.isEmpty()) {
            System.out.println("== transport errors by cause ==");
            netErrors.forEach((k, v) -> System.out.println("  net error x" + v + "  " + k));
        }
        List<Long> sorted;
        synchronized (latencies) {
            sorted = new ArrayList<>(latencies);
        }
        Collections.sort(sorted);
        long total = sorted.size();
        System.out.println("== outcome distribution ==");
        System.out.println("  client 201s: " + client201s.get() + ", distinct confirmed seats: " + confirmedSeats.size());
        if (!sorted.isEmpty()) {
            System.out.println("== latency ms (n=" + total + ", ~" + (total / Math.max(1, stampSecs)) + "/s stampede) ==");
            System.out.println("  p50=" + pct(sorted, 50) + " p95=" + pct(sorted, 95) + " p99=" + pct(sorted, 99)
                    + " max=" + sorted.get(sorted.size() - 1));
        }
    }

    static long pct(List<Long> sorted, int p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * (p / 100.0))));
    }

    static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }

    static int envInt(String key, int def) {
        try {
            return Integer.parseInt(System.getenv().getOrDefault(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
