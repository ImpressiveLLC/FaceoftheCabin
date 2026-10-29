package com.cabin.orchestrator.weather;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the Iowa Environmental Mesonet ASOS download
 * ({@code asos.py ... format=onlycomma&tz=UTC&data=tmpf&data=dwpf&data=relh}), whose
 * header is {@code station,valid,tmpf,dwpf,relh}. Pure function so it is unit-testable
 * without a network. Lines that do not parse are skipped rather than failing the batch:
 * a daily job should keep the good rows it did get.
 */
public final class MesonetCsvParser {

    private static final DateTimeFormatter VALID = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private MesonetCsvParser() {}

    public static List<OutdoorObservation> parse(String location, String csv) {
        List<OutdoorObservation> out = new ArrayList<>();
        if (csv == null) return out;
        for (String line : csv.split("\\R")) {
            String[] c = line.split(",", -1);
            if (c.length < 5 || c[0].equals("station") || c[0].startsWith("#")) continue;
            try {
                java.time.Instant at = LocalDateTime.parse(c[1].trim(), VALID).toInstant(ZoneOffset.UTC);
                Double tF = num(c[2]), dF = num(c[3]), rh = num(c[4]);
                if (tF == null && dF == null && rh == null) continue;
                out.add(new OutdoorObservation(location, c[0].trim(), at, toC(tF), toC(dF), rh));
            } catch (RuntimeException e) {
                // unparseable row: skip it
            }
        }
        return out;
    }

    static Double num(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("M") || t.equalsIgnoreCase("T")) return null;
        double v = Double.parseDouble(t);
        return Double.isNaN(v) ? null : v;
    }

    static Double toC(Double f) {
        return f == null ? null : (f - 32.0) * 5.0 / 9.0;
    }
}
