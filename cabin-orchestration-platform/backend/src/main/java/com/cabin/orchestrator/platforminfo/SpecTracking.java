package com.cabin.orchestrator.platforminfo;

import java.util.regex.Pattern;

/**
 * How firmly a declared version is fixed. The catalog states it for every entry and
 * PlatformSpecsGuardTest checks the statement against this rule, so nothing can be
 * labelled "pinned" while its source says "latest".
 *
 * <ul>
 *   <li>pinned: an exact version (three or more numeric parts);</li>
 *   <li>series: only a major or major.minor is fixed (a tag like 20-alpine or 3.9-..., a ^ or ~ range);</li>
 *   <li>floating: latest, stable, main, an untagged base, or a tag that only names a variant (alpine).</li>
 * </ul>
 */
public final class SpecTracking {

    private static final Pattern LEADING_VERSION = Pattern.compile("^v?(\\d+(?:\\.\\d+)*)(?![\\d.]).*$");
    private static final Pattern EXACT_TRIPLE = Pattern.compile("^v?\\d+\\.\\d+\\.\\d+.*$");

    private SpecTracking() {}

    /** @param kind one of PlatformSpecsCatalog.KINDS; @param declared the version as written in the source */
    public static String classify(String kind, String declared) {
        if ("host".equals(kind)) return "unmanaged";
        String v = declared == null ? "" : declared.trim();
        if (v.isEmpty()) return "floating";
        if (v.startsWith("^") || v.startsWith("~")) return "series";
        var m = LEADING_VERSION.matcher(v);
        if (!m.matches()) return "floating";          // latest, stable, main, alpine, latest-pg16 ...
        int parts = m.group(1).split("\\.").length;
        if (parts >= 3 || EXACT_TRIPLE.matcher(v).matches()) return "pinned";
        return "series";
    }
}
