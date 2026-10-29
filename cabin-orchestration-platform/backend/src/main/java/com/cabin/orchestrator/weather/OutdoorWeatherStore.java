package com.cabin.orchestrator.weather;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Outdoor baseline readings, one row per (location, station, observation time).
 * Same idempotent CREATE TABLE IF NOT EXISTS self-migration as the other stores here.
 * Re-fetching an overlapping window is safe: the primary key makes inserts no-ops.
 * Rows are never deleted by this class (BI-adjacent data, see docs/STORAGE_ARCHITECTURE.md).
 */
@Repository
public class OutdoorWeatherStore {

    private final JdbcTemplate jdbc;

    public OutdoorWeatherStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS outdoor_observation (
              location    VARCHAR(64)  NOT NULL,
              station     VARCHAR(16)  NOT NULL,
              observed_at TIMESTAMPTZ  NOT NULL,
              temp_c      DOUBLE PRECISION,
              dewpoint_c  DOUBLE PRECISION,
              rh_pct      DOUBLE PRECISION,
              PRIMARY KEY (location, station, observed_at)
            )""");
    }

    public int insertAll(List<OutdoorObservation> obs) {
        if (obs.isEmpty()) return 0;
        int[][] r = jdbc.batchUpdate("""
            INSERT INTO outdoor_observation (location, station, observed_at, temp_c, dewpoint_c, rh_pct)
            VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""",
            obs, 500, (ps, o) -> {
                ps.setString(1, o.location());
                ps.setString(2, o.station());
                ps.setTimestamp(3, Timestamp.from(o.observedAt()));
                ps.setObject(4, o.tempC(), java.sql.Types.DOUBLE);
                ps.setObject(5, o.dewpointC(), java.sql.Types.DOUBLE);
                ps.setObject(6, o.rhPct(), java.sql.Types.DOUBLE);
            });
        int inserted = 0;
        for (int[] batch : r) for (int n : batch) if (n > 0) inserted += n;
        return inserted;
    }

    public Instant latest(String location, String station) {
        Timestamp t = jdbc.queryForObject(
            "SELECT max(observed_at) FROM outdoor_observation WHERE location = ? AND station = ?",
            Timestamp.class, location, station);
        return t == null ? null : t.toInstant();
    }

    public List<OutdoorObservation> since(String location, Instant since) {
        return jdbc.query("""
            SELECT location, station, observed_at, temp_c, dewpoint_c, rh_pct FROM outdoor_observation
            WHERE location = ? AND observed_at >= ? ORDER BY observed_at""",
            (rs, i) -> map(rs), location, Timestamp.from(since));
    }

    public List<OutdoorObservation> month(String location, String station, YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return jdbc.query("""
            SELECT location, station, observed_at, temp_c, dewpoint_c, rh_pct FROM outdoor_observation
            WHERE location = ? AND station = ? AND observed_at >= ? AND observed_at < ? ORDER BY observed_at""",
            (rs, i) -> map(rs), location, station, Timestamp.from(from), Timestamp.from(to));
    }

    private static OutdoorObservation map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OutdoorObservation(rs.getString("location"), rs.getString("station"),
            rs.getTimestamp("observed_at").toInstant(),
            (Double) rs.getObject("temp_c"), (Double) rs.getObject("dewpoint_c"), (Double) rs.getObject("rh_pct"));
    }
}
