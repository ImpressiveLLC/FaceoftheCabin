package com.cabin.orchestrator.api;

import com.cabin.orchestrator.events.TelemetryExportService;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * GET /api/events/export?from=2026-08-01&amp;to=2026-09-01&amp;deviceId=a,b&amp;field=temperature,humidity&amp;format=jsonl.gz
 *
 * <p>The full-history telemetry download (see TelemetryExportService). It is deliberately not part of the open
 * history reads: ADMINISTRATOR only, because it hands over every sensor reading the platform has ever kept, with no
 * day limit (the cabin.history.max-days clamp on telemetry-history does not apply here, by design). The route sits
 * under /api/events/**, which WebConfig already gates, so GoogleAuthInterceptor authenticates the caller and sets the
 * server-derived household role before this runs; guest links (their scopes cover only telemetry-history and
 * reported-fields) and demo links (deny by default) never reach it, and a managed-user session resolves to a
 * non-administrator role and is refused below.
 *
 * <p>The response is written straight to the servlet output stream (not as an async StreamingResponseBody, whose
 * request timeout would cut a multi-year export off, and which would run the auth interceptor a second time).
 * Parameters are validated and the role checked before the first byte, so a refusal is a normal JSON error. A
 * failure after streaming starts aborts the connection on purpose: a truncated download must not look complete.
 *
 * <p>Parameters (all optional): from (inclusive) and to (exclusive), each an ISO instant or a UTC date, default
 * everything; deviceId and field, comma-separated lists, exact match; format, one of jsonl.gz (default), jsonl,
 * csv.gz, csv. CSV is long format, one line per payload field.
 */
@RestController
@RequestMapping("/api/events/export")
@CrossOrigin
public class TelemetryExportController {

    private static final Logger log = LoggerFactory.getLogger(TelemetryExportController.class);

    /** One export streams a whole database table's worth of history; two at once is plenty for one owner. */
    private final Semaphore concurrent = new Semaphore(2);

    private final TelemetryExportService service;

    public TelemetryExportController(TelemetryExportService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> export(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "field", required = false) String field,
            @RequestParam(name = "format", required = false) String format,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        HouseholdRole role = (HouseholdRole) request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE);
        if (role != HouseholdRole.ADMINISTRATOR) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only an administrator can export telemetry"));
        }

        TelemetryExportService.Query query;
        try {
            Instant fromInstant = parseInstant(from, "from");
            Instant toInstant = parseInstant(to, "to");
            if (fromInstant != null && toInstant != null && !fromInstant.isBefore(toInstant)) {
                throw new IllegalArgumentException("from must be earlier than to");
            }
            query = new TelemetryExportService.Query(fromInstant, toInstant, csvSet(deviceId), csvSet(field),
                TelemetryExportService.Format.parse(format));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        if (!concurrent.tryAcquire()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "Too many exports are running; retry shortly"));
        }
        try {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(query.format().contentType());
            response.setHeader("Content-Disposition", "attachment; filename=\"telemetry-export."
                + query.format().id() + "\"");
            response.setHeader("Cache-Control", "no-store");
            Object who = request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL);
            log.info("Telemetry export started by {} (from={}, to={}, devices={}, fields={}, format={})", who,
                query.from(), query.to(), query.deviceIds().size(), query.fields().size(), query.format().id());
            service.export(query, response.getOutputStream());
            return null; // already written to the response
        } finally {
            concurrent.release();
        }
    }

    /** An ISO instant, or a plain date read as 00:00 UTC. Null or blank means "not given". */
    static Instant parseInstant(String value, String name) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        try {
            return Instant.parse(v);
        } catch (Exception ignored) {
            // fall through to a plain date
        }
        try {
            return LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            throw new IllegalArgumentException(name + " must be an ISO instant (2026-08-01T00:00:00Z) or a date (2026-08-01)");
        }
    }

    private static Set<String> csvSet(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
