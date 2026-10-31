package com.cabin.orchestrator.weather;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Daily pull of outdoor conditions from the nearest airport weather station per location,
 * so the sensor-history charts can show an outdoor baseline next to indoor readings.
 *
 * Why: indoor humidity only means something against what the air outside was doing.
 * Dew point is the measure that separates "the weather got wetter" from "the building
 * is still holding moisture."
 *
 * Storage is two-tier on purpose (see docs/STORAGE_ARCHITECTURE.md): rows live in Postgres
 * on the NVMe (tiny: about 70 rows a day per station) for the chart, and every run
 * also writes (a) the raw downloaded CSV and (b) a per-month CSV rebuilt from the table to
 * the cold tier on /storage (bind-mounted at archiveDir). Nothing here ever deletes data.
 *
 * Stations are configured per location id (no coordinates are stored on locations), as
 * {@code location=PRIMARY[,FALLBACK...]} entries separated by semicolons. A location with
 * no entry simply has no baseline; nothing is guessed from the location name.
 */
@Service
public class OutdoorWeatherService {

    private static final Logger log = LoggerFactory.getLogger(OutdoorWeatherService.class);
    /** A day with fewer observations than this is flagged partial / lower-ranked. ASOS reports about 3 an hour. */
    static final int MIN_OBS_PER_DAY = 12;

    private final OutdoorWeatherStore store;
    private final WeatherFetcher fetcher;
    private final Clock clock;
    private final boolean enabled;
    private final int backfillDays;
    private final Path archiveDir;
    private final Map<String, List<String>> stationsByLocation;

    // Two constructors: Spring needs the marker to know which one the container uses. Without it startup
    // fails with "No default constructor found" (this class's first deploy, 2026-10-29, rolled back on it).
    @Autowired
    public OutdoorWeatherService(OutdoorWeatherStore store, WeatherFetcher fetcher,
                                 @Value("${cabin.weather.enabled:true}") boolean enabled,
                                 @Value("${cabin.weather.stations:cabin=UBE,RPD}") String stations,
                                 @Value("${cabin.weather.backfillDays:60}") int backfillDays,
                                 @Value("${cabin.weather.archiveDir:/app/archives/weather}") String archiveDir) {
        this(store, fetcher, Clock.systemUTC(), enabled, stations, backfillDays, archiveDir);
    }

    OutdoorWeatherService(OutdoorWeatherStore store, WeatherFetcher fetcher, Clock clock, boolean enabled,
                          String stations, int backfillDays, String archiveDir) {
        this.store = store;
        this.fetcher = fetcher;
        this.clock = clock;
        this.enabled = enabled;
        this.backfillDays = Math.max(1, backfillDays);
        this.archiveDir = Path.of(archiveDir);
        this.stationsByLocation = parseStations(stations);
    }

    static Map<String, List<String>> parseStations(String spec) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (spec == null) return out;
        for (String entry : spec.split(";")) {
            String[] kv = entry.split("=", 2);
            if (kv.length != 2 || kv[0].isBlank()) continue;
            List<String> ids = new ArrayList<>();
            for (String s : kv[1].split(",")) if (!s.isBlank()) ids.add(s.trim().toUpperCase(Locale.ROOT));
            if (!ids.isEmpty()) out.put(kv[0].trim().toLowerCase(Locale.ROOT), ids);
        }
        return out;
    }

    public List<String> stationsFor(String location) {
        return stationsByLocation.getOrDefault(location == null ? "" : location.toLowerCase(Locale.ROOT), List.of());
    }

    /** Catch-up after a deploy or an outage, off the startup thread so a slow download never delays boot. */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        if (!enabled) return;
        Thread t = new Thread(this::refreshAll, "outdoor-weather-catchup");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(cron = "${cabin.weather.cron:0 30 3 * * *}")
    public void refreshAll() {
        if (!enabled) return;
        for (Map.Entry<String, List<String>> e : stationsByLocation.entrySet()) {
            for (String station : e.getValue()) {
                try {
                    refresh(e.getKey(), station);
                } catch (Exception ex) {
                    if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                    log.warn("outdoor weather refresh failed for {} / {}: {}", e.getKey(), station, ex.toString());
                }
            }
        }
    }

    /** Fetches from a day before the newest stored row (or backfillDays ago), stores and archives. Returns rows added. */
    int refresh(String location, String station) throws IOException, InterruptedException {
        Instant now = clock.instant();
        Instant latest = store.latest(location, station);
        Instant floor = now.minus(backfillDays, ChronoUnit.DAYS);
        Instant start = latest == null || latest.isBefore(floor) ? floor : latest.minus(1, ChronoUnit.DAYS);
        LocalDate from = start.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate to = now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1);
        String csv = fetcher.fetch(station, from, to);
        List<OutdoorObservation> obs = MesonetCsvParser.parse(location, csv);
        int added = store.insertAll(obs);
        archiveRaw(station, now, csv);
        TreeSet<YearMonth> months = new TreeSet<>();
        for (OutdoorObservation o : obs) months.add(YearMonth.from(o.observedAt().atZone(ZoneOffset.UTC)));
        for (YearMonth m : months) archiveMonth(location, station, m);
        log.info("outdoor weather {} / {}: {} parsed, {} new", location, station, obs.size(), added);
        return added;
    }

    public OutdoorHistoryResponse history(String location, int requestedDays, int maxDays) {
        int effective = Math.max(1, Math.min(requestedDays, maxDays));
        List<String> stations = stationsFor(location);
        if (stations.isEmpty()) {
            return new OutdoorHistoryResponse(location, List.of(), requestedDays, effective, effective != requestedDays, List.of());
        }
        Instant now = clock.instant();
        Instant since = now.truncatedTo(ChronoUnit.DAYS).minus(effective - 1L, ChronoUnit.DAYS);
        List<OutdoorObservation> obs = store.since(location.toLowerCase(Locale.ROOT), since);
        List<OutdoorDailyPoint> points = OutdoorDailyAggregator.aggregate(obs, stations, MIN_OBS_PER_DAY, now);
        return new OutdoorHistoryResponse(location, stations, requestedDays, effective, effective != requestedDays, points);
    }

    // ── cold-tier log ────────────────────────────────────────────────────────

    private void archiveRaw(String station, Instant now, String csv) {
        try {
            Path dir = archiveDir.resolve("raw");
            Files.createDirectories(dir);
            String stamp = now.truncatedTo(ChronoUnit.MINUTES).toString().replace(":", "");
            Files.writeString(dir.resolve("asos_" + station + "_" + stamp + ".csv"), csv, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.warn("outdoor weather raw archive failed (data is still stored in the database): {}", e.toString());
        }
    }

    /** Rebuilds one month's CSV from the table and swaps it in atomically, so a crash never leaves a half file. */
    void archiveMonth(String location, String station, YearMonth month) {
        try {
            Files.createDirectories(archiveDir);
            Path target = archiveDir.resolve("outdoor_" + location + "_" + station + "_" + month + ".csv");
            Path tmp = archiveDir.resolve(target.getFileName() + ".tmp");
            StringBuilder sb = new StringBuilder("station,valid_utc,temp_c,dewpoint_c,rh_pct\n");
            for (OutdoorObservation o : store.month(location, station, month)) {
                sb.append(o.station()).append(',').append(o.observedAt()).append(',')
                  .append(fmt(o.tempC())).append(',').append(fmt(o.dewpointC())).append(',').append(fmt(o.rhPct())).append('\n');
            }
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            log.warn("outdoor weather month archive failed (data is still stored in the database): {}", e.toString());
        }
    }

    private static String fmt(Double v) {
        return v == null ? "" : String.format(Locale.ROOT, "%.2f", v);
    }
}
