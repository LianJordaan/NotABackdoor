package com.lian.notabackdoor.panel.web;

import com.sun.management.OperatingSystemMXBean;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** One-second server samples and durable minute summaries for the panel's history charts. */
public final class PanelMetrics implements AutoCloseable {
    private static final long SECOND = 1_000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long WEEK = 7 * 24 * HOUR;
    private static final int MAX_RAW = 3_700;
    private static final int MAX_MINUTES = 10_080;
    private final Path store;
    private final Clock clock;
    private final ArrayDeque<Sample> raw = new ArrayDeque<>();
    private final ArrayDeque<Sample> minutes = new ArrayDeque<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "notabackdoor-metrics-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final OperatingSystemMXBean processBean;
    private BukkitTask sampler;
    private Bucket current;
    private long previousCpuNanos = -1;
    private long previousWallNanos = -1;
    private int appendedSinceCompaction;
    private volatile String storageError = "";

    public PanelMetrics(Path dataDirectory) {
        this(dataDirectory, Clock.systemUTC());
    }

    PanelMetrics(Path dataDirectory, Clock clock) {
        store = dataDirectory.resolve("metrics-history-v1.csv");
        this.clock = clock;
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        processBean = bean instanceof OperatingSystemMXBean extended ? extended : null;
        try {
            Files.createDirectories(dataDirectory);
            load();
        } catch (IOException error) {
            storageError = "Metric history could not be read: " + error.getMessage();
        }
    }

    /** Call after the panel listener starts; sampling runs on the Minecraft server thread. */
    public synchronized void start(JavaPlugin plugin) {
        if (sampler != null || Bukkit.getServer() == null) return;
        sampler = Bukkit.getScheduler().runTaskTimer(plugin, this::capture, 20L, 20L);
    }

    private void capture() {
        long now = clock.millis();
        long wall = System.nanoTime();
        long cpu = processBean == null ? -1 : processBean.getProcessCpuTime();
        Double cpuPercent = null;
        if (cpu >= 0 && previousCpuNanos >= 0 && wall > previousWallNanos && cpu >= previousCpuNanos) {
            double cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            cpuPercent = finite(100.0 * (cpu - previousCpuNanos) / (wall - previousWallNanos) / cores);
            if (cpuPercent != null) cpuPercent = Math.max(0, Math.min(100, cpuPercent));
        }
        previousWallNanos = wall;
        previousCpuNanos = cpu;
        double[] tpsValues = Bukkit.getTPS();
        Double tps = tpsValues.length == 0 ? null : finite(tpsValues[0]);
        Double mspt = finite(Bukkit.getAverageTickTime());
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        record(new Sample(now, cpuPercent, tps, mspt, used, runtime.maxMemory(),
                Bukkit.getOnlinePlayers().size()));
    }

    /** Package-private so deterministic tests can exercise ranges and persistence without Bukkit. */
    synchronized void record(Sample sample) {
        if (sample.timestamp() < 0) throw new IllegalArgumentException("Negative sample timestamp");
        raw.addLast(sample);
        while (raw.size() > MAX_RAW || (!raw.isEmpty() && raw.peekFirst().timestamp() < sample.timestamp() - HOUR)) {
            raw.removeFirst();
        }
        long minute = sample.timestamp() / MINUTE;
        if (current == null) {
            current = new Bucket(minute);
        } else if (minute > current.minute) {
            saveMinute(current.average());
            current = new Bucket(minute);
        } else if (minute < current.minute) {
            return; // A backwards clock adjustment must not corrupt ordered history.
        }
        current.add(sample);
    }

    public synchronized History history(String range) {
        long duration = duration(range);
        long interval = interval(range);
        long now = clock.millis();
        long from = now - duration;
        long firstRaw = raw.isEmpty() ? Long.MAX_VALUE : raw.peekFirst().timestamp();
        ArrayList<Sample> candidates = new ArrayList<>();
        if (!range.equals("realtime")) {
            for (Sample sample : minutes) {
                if (sample.timestamp() >= from && sample.timestamp() < firstRaw
                        && (current == null || sample.timestamp() / MINUTE != current.minute)) {
                    candidates.add(sample);
                }
            }
        }
        for (Sample sample : raw) {
            if (sample.timestamp() >= from && sample.timestamp() <= now) candidates.add(sample);
        }
        long coverageStart = minutes.isEmpty() ? firstRaw : Math.min(minutes.peekFirst().timestamp(), firstRaw);
        if (coverageStart == Long.MAX_VALUE) coverageStart = 0;
        long coverageEnd = raw.isEmpty() ? (minutes.isEmpty() ? 0 : minutes.peekLast().timestamp())
                : raw.peekLast().timestamp();
        return new History(range, from, coverageStart, coverageEnd,
                downsample(candidates, interval), storageError);
    }

    public synchronized Sample latest() {
        return raw.peekLast();
    }

    private static List<Sample> downsample(List<Sample> source, long interval) {
        if (interval == SECOND) return List.copyOf(source);
        ArrayList<Sample> result = new ArrayList<>();
        Bucket bucket = null;
        for (Sample sample : source) {
            long slot = sample.timestamp() / interval;
            if (bucket == null || bucket.minute != slot) {
                if (bucket != null) result.add(bucket.average());
                bucket = new Bucket(slot);
            }
            bucket.add(sample);
        }
        if (bucket != null) result.add(bucket.average());
        return result;
    }

    private static long duration(String range) {
        return switch (range) {
            case "realtime", "1m" -> MINUTE;
            case "5m" -> 5 * MINUTE;
            case "10m" -> 10 * MINUTE;
            case "30m" -> 30 * MINUTE;
            case "1h" -> HOUR;
            case "12h" -> 12 * HOUR;
            case "1d" -> 24 * HOUR;
            case "1w" -> WEEK;
            default -> throw new IllegalArgumentException("Unknown metrics range");
        };
    }

    private static long interval(String range) {
        return switch (range) {
            case "realtime", "1m", "5m" -> SECOND;
            case "10m" -> 2 * SECOND;
            case "30m" -> 5 * SECOND;
            case "1h" -> 10 * SECOND;
            case "12h" -> 2 * MINUTE;
            case "1d" -> 3 * MINUTE;
            case "1w" -> 20 * MINUTE;
            default -> throw new IllegalArgumentException("Unknown metrics range");
        };
    }

    private synchronized void saveMinute(Sample sample) {
        if (!minutes.isEmpty() && minutes.peekLast().timestamp() / MINUTE == sample.timestamp() / MINUTE) {
            minutes.removeLast();
        }
        minutes.addLast(sample);
        while (minutes.size() > MAX_MINUTES
                || (!minutes.isEmpty() && minutes.peekFirst().timestamp() < clock.millis() - WEEK)) {
            minutes.removeFirst();
        }
        List<Sample> snapshot = List.copyOf(minutes);
        writer.execute(() -> {
            try {
                Files.writeString(store, csv(sample) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (++appendedSinceCompaction >= 1_440) {
                    compact(snapshot);
                    appendedSinceCompaction = 0;
                }
                storageError = "";
            } catch (IOException error) {
                storageError = "Metric history could not be saved: " + error.getMessage();
            }
        });
    }

    private void load() throws IOException {
        if (!Files.isRegularFile(store)) return;
        if (Files.size(store) > 8 * 1024 * 1024) {
            throw new IOException("Metric history exceeds its 8 MiB safety limit");
        }
        try (BufferedReader input = Files.newBufferedReader(store, StandardCharsets.UTF_8)) {
            String line;
            while ((line = input.readLine()) != null) {
                Sample sample = parse(line);
                if (sample == null || sample.timestamp() < clock.millis() - WEEK) continue;
                if (!minutes.isEmpty() && minutes.peekLast().timestamp() / MINUTE == sample.timestamp() / MINUTE) {
                    minutes.removeLast();
                }
                minutes.addLast(sample);
                if (minutes.size() > MAX_MINUTES) minutes.removeFirst();
            }
        }
    }

    private static String csv(Sample sample) {
        return sample.timestamp() + "," + number(sample.cpuPercent()) + "," + number(sample.tps()) + ","
                + number(sample.mspt()) + "," + sample.memoryUsedBytes() + "," + sample.memoryMaxBytes()
                + "," + sample.players();
    }

    private static String number(Double value) { return value == null ? "" : Double.toString(value); }

    private static Sample parse(String line) {
        String[] parts = line.split(",", -1);
        if (parts.length != 7) return null;
        try {
            return new Sample(Long.parseLong(parts[0]), optional(parts[1]), optional(parts[2]), optional(parts[3]),
                    Long.parseLong(parts[4]), Long.parseLong(parts[5]), Integer.parseInt(parts[6]));
        } catch (NumberFormatException badRecord) {
            return null;
        }
    }

    private static Double optional(String value) {
        return value.isEmpty() ? null : finite(Double.parseDouble(value));
    }

    private static Double finite(double value) { return Double.isFinite(value) ? value : null; }

    private void compact(List<Sample> snapshot) throws IOException {
        Path temporary = Files.createTempFile(store.getParent(), ".metrics-", ".tmp");
        try {
            StringBuilder contents = new StringBuilder(snapshot.size() * 80);
            for (Sample sample : snapshot) contents.append(csv(sample)).append('\n');
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, store, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, store, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public synchronized void close() {
        if (sampler != null) sampler.cancel();
        sampler = null;
        if (current != null && current.count > 0) {
            saveMinute(current.average());
            current = null;
        }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }

    public record Sample(long timestamp, Double cpuPercent, Double tps, Double mspt,
                         long memoryUsedBytes, long memoryMaxBytes, int players) { }

    public record History(String range, long requestedFrom, long coverageStart, long coverageEnd,
                          List<Sample> samples, String storageError) { }

    private static final class Bucket {
        final long minute;
        int count;
        long timestamp;
        double cpu, tps, mspt;
        int cpuCount, tpsCount, msptCount;
        long used, max;
        int players;

        Bucket(long minute) { this.minute = minute; }

        void add(Sample sample) {
            count++;
            timestamp = sample.timestamp();
            if (sample.cpuPercent() != null) { cpu += sample.cpuPercent(); cpuCount++; }
            if (sample.tps() != null) { tps += sample.tps(); tpsCount++; }
            if (sample.mspt() != null) { mspt += sample.mspt(); msptCount++; }
            used += sample.memoryUsedBytes();
            max = sample.memoryMaxBytes();
            players = sample.players();
        }

        Sample average() {
            return new Sample(timestamp, cpuCount == 0 ? null : cpu / cpuCount,
                    tpsCount == 0 ? null : tps / tpsCount, msptCount == 0 ? null : mspt / msptCount,
                    used / count, max, players);
        }
    }
}
