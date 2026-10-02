package com.lian.notabackdoor.panel.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class PanelMetricsTest {
    @TempDir Path directory;

    @Test
    void realtimeShowsOnlyPastMinuteAndHistoricRangesDownsample() throws Exception {
        long now = 1_800_000_000_000L;
        try (PanelMetrics metrics = new PanelMetrics(directory, Clock.fixed(Instant.ofEpochMilli(now), ZoneId.of("UTC")))) {
            for (int second = 3_600; second >= 0; second--) {
                long timestamp = now - second * 1_000L;
                metrics.record(new PanelMetrics.Sample(timestamp, 30.0, 19.8, 12.0,
                        1_000_000L, 4_000_000L, 2));
            }
            PanelMetrics.History realtime = metrics.history("realtime");
            assertEquals(61, realtime.samples().size());
            assertTrue(realtime.samples().get(0).timestamp() >= now - 60_000);
            assertTrue(metrics.history("30m").samples().size() <= 370);
            assertTrue(metrics.history("1h").samples().size() <= 370);
            assertEquals(30.0, metrics.latest().cpuPercent());
            assertThrows(IllegalArgumentException.class, () -> metrics.history("2h"));
        }
    }

    @Test
    void weekSummarySurvivesPanelRestart() throws Exception {
        long now = 1_800_000_000_000L;
        Clock clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneId.of("UTC"));
        try (PanelMetrics metrics = new PanelMetrics(directory, clock)) {
            metrics.record(new PanelMetrics.Sample(now - 6L * 24 * 60 * 60_000, 25.0, 20.0,
                    8.0, 100, 1_000, 1));
            metrics.record(new PanelMetrics.Sample(now - 60_000, 60.0, 18.0, 40.0, 500, 1_000, 4));
            metrics.record(new PanelMetrics.Sample(now, 20.0, 20.0, 10.0, 200, 1_000, 2));
        }
        try (PanelMetrics restored = new PanelMetrics(directory, clock)) {
            PanelMetrics.History week = restored.history("1w");
            assertEquals(3, week.samples().size());
            assertTrue(week.coverageStart() <= now - 6L * 24 * 60 * 60_000);
            assertTrue(week.coverageEnd() >= now - 60_000);
            assertTrue(restored.history("1h").samples().size() >= 1);
        }
    }
}
