package com.cabin.orchestrator.locations;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Same JdbcTemplate + CREATE TABLE IF NOT EXISTS self-migration pattern as
 * FamilyProfileService/FamilyNoteService/ChoreCompletionService — no
 * migration framework in this project yet.
 *
 * Soft delete (active=false), same rationale as FamilyProfileService: a
 * device's `location` field (DeviceDescriptor.location) is a free-standing
 * string reference to a location id, so hard-deleting a location a device
 * still points at would silently orphan it. list() only returns active
 * locations; byId() (used when a device/dashboard needs to resolve one
 * even if archived) returns archived ones too.
 *
 * Seeded once, from this instance's own env-var-driven defaults, so an
 * existing deployment's first startup writes exactly what the frontend's
 * LOCATIONS constant already assumed for cabin/home -- not a hardcoded
 * literal here, so this stays honest across forks per REPLICATION.md.
 */
@Service
public class HubLocationService {

    private final JdbcTemplate jdbc;

    @Value("${cabin.locations.cabin.apiBase:http://cabin-hub:8090}")      private String cabinApiBase;
    @Value("${cabin.locations.cabin.wsBase:ws://cabin-hub:9001}")         private String cabinWsBase;
    @Value("${cabin.locations.cabin.grafanaUrl:http://cabin-hub:3002}")   private String cabinGrafanaUrl;
    @Value("${cabin.locations.cabin.noderedUrl:http://cabin-hub:1880}")   private String cabinNoderedUrl;
    @Value("${cabin.locations.cabin.haUrl:http://cabin-hub:8123}")        private String cabinHaUrl;
    @Value("${cabin.locations.cabin.frigateUrl:http://cabin-hub:5000}")   private String cabinFrigateUrl;
    @Value("${cabin.locations.cabin.z2mUrl:http://cabin-hub:8080}")       private String cabinZ2mUrl;
    @Value("${cabin.locations.cabin.familyHubUrl:}")                     private String cabinFamilyHubUrl;

    @Value("${cabin.locations.home.apiBase:http://home-hub:8080}")        private String homeApiBase;
    @Value("${cabin.locations.home.wsBase:ws://home-hub:9001}")           private String homeWsBase;
    @Value("${cabin.locations.home.grafanaUrl:http://home-hub:3000}")     private String homeGrafanaUrl;
    @Value("${cabin.locations.home.noderedUrl:http://home-hub:1880}")     private String homeNoderedUrl;
    @Value("${cabin.locations.home.haUrl:http://home-hub:8123}")          private String homeHaUrl;
    @Value("${cabin.locations.home.frigateUrl:http://home-hub:5000}")     private String homeFrigateUrl;
    @Value("${cabin.locations.home.familyHubUrl:}")                      private String homeFamilyHubUrl;

    public HubLocationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS hub_locations (
              id            VARCHAR(64) PRIMARY KEY,
              label         VARCHAR(128) NOT NULL,
              api_base      VARCHAR(255),
              ws_base       VARCHAR(255),
              grafana_url   VARCHAR(255),
              nodered_url   VARCHAR(255),
              ha_url        VARCHAR(255),
              frigate_url   VARCHAR(255),
              z2m_url       VARCHAR(255),
              family_hub_url VARCHAR(255),
              sort_order    INTEGER NOT NULL DEFAULT 0,
              active        BOOLEAN NOT NULL DEFAULT true,
              created_at    BIGINT NOT NULL,
              updated_at    BIGINT NOT NULL
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_hub_locations_active ON hub_locations (active, sort_order)");
        seedIfEmpty();
    }

    private void seedIfEmpty() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM hub_locations", Integer.class);
        if (count != null && count > 0) return;

        long now = System.currentTimeMillis();
        insertRow("cabin", "Cabin", cabinApiBase, cabinWsBase, cabinGrafanaUrl, cabinNoderedUrl,
            cabinHaUrl, cabinFrigateUrl, cabinZ2mUrl, blankToNull(cabinFamilyHubUrl), 0, now);
        insertRow("home", "Home", homeApiBase, homeWsBase, homeGrafanaUrl, homeNoderedUrl,
            homeHaUrl, homeFrigateUrl, null, blankToNull(homeFamilyHubUrl), 1, now);
    }

    private String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s; }

    private void insertRow(String id, String label, String apiBase, String wsBase, String grafanaUrl,
                            String noderedUrl, String haUrl, String frigateUrl, String z2mUrl,
                            String familyHubUrl, int sortOrder, long now) {
        jdbc.update("""
            INSERT INTO hub_locations
              (id, label, api_base, ws_base, grafana_url, nodered_url, ha_url, frigate_url, z2m_url,
               family_hub_url, sort_order, active, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,true,?,?)
            """,
            id, label, apiBase, wsBase, grafanaUrl, noderedUrl, haUrl, frigateUrl, z2mUrl,
            familyHubUrl, sortOrder, now, now);
    }

    public List<HubLocation> list() {
        return jdbc.queryForList(
                "SELECT * FROM hub_locations WHERE active = true ORDER BY sort_order, created_at")
            .stream().map(this::fromRow).toList();
    }

    public HubLocation byId(String id) {
        List<HubLocation> rows = jdbc.queryForList("SELECT * FROM hub_locations WHERE id = ?", id)
            .stream().map(this::fromRow).toList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public HubLocation create(HubLocation loc) {
        long now = System.currentTimeMillis();
        Integer maxSort = jdbc.queryForObject("SELECT COALESCE(MAX(sort_order), -1) FROM hub_locations", Integer.class);
        int sortOrder = (maxSort == null ? -1 : maxSort) + 1;
        jdbc.update("""
            INSERT INTO hub_locations
              (id, label, api_base, ws_base, grafana_url, nodered_url, ha_url, frigate_url, z2m_url,
               family_hub_url, sort_order, active, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,true,?,?)
            ON CONFLICT (id) DO UPDATE SET
              label = EXCLUDED.label, api_base = EXCLUDED.api_base, ws_base = EXCLUDED.ws_base,
              grafana_url = EXCLUDED.grafana_url, nodered_url = EXCLUDED.nodered_url,
              ha_url = EXCLUDED.ha_url, frigate_url = EXCLUDED.frigate_url, z2m_url = EXCLUDED.z2m_url,
              family_hub_url = EXCLUDED.family_hub_url, active = true, updated_at = EXCLUDED.updated_at
            """,
            loc.id(), loc.label(), loc.apiBase(), loc.wsBase(), loc.grafanaUrl(), loc.noderedUrl(),
            loc.haUrl(), loc.frigateUrl(), loc.z2mUrl(), loc.familyHubUrl(), sortOrder, now, now);
        return byId(loc.id());
    }

    /** Partial update — only non-null fields on the incoming record are applied. */
    public HubLocation update(String id, HubLocation patch) {
        HubLocation existing = byId(id);
        if (existing == null) return null;
        HubLocation merged = new HubLocation(
            id,
            patch.label() != null ? patch.label() : existing.label(),
            patch.apiBase() != null ? patch.apiBase() : existing.apiBase(),
            patch.wsBase() != null ? patch.wsBase() : existing.wsBase(),
            patch.grafanaUrl() != null ? patch.grafanaUrl() : existing.grafanaUrl(),
            patch.noderedUrl() != null ? patch.noderedUrl() : existing.noderedUrl(),
            patch.haUrl() != null ? patch.haUrl() : existing.haUrl(),
            patch.frigateUrl() != null ? patch.frigateUrl() : existing.frigateUrl(),
            patch.z2mUrl() != null ? patch.z2mUrl() : existing.z2mUrl(),
            patch.familyHubUrl() != null ? patch.familyHubUrl() : existing.familyHubUrl(),
            existing.sortOrder(), true, existing.createdAt(), System.currentTimeMillis()
        );
        jdbc.update("""
            UPDATE hub_locations SET
              label=?, api_base=?, ws_base=?, grafana_url=?, nodered_url=?, ha_url=?, frigate_url=?,
              z2m_url=?, family_hub_url=?, updated_at=?
            WHERE id = ?
            """,
            merged.label(), merged.apiBase(), merged.wsBase(), merged.grafanaUrl(), merged.noderedUrl(),
            merged.haUrl(), merged.frigateUrl(), merged.z2mUrl(), merged.familyHubUrl(), merged.updatedAt(), id);
        return merged;
    }

    /** Reorders in one pass so a drag-drop reorder (§3) is a single call, not N round-trips. */
    public void reorder(List<String> orderedIds) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < orderedIds.size(); i++) {
            jdbc.update("UPDATE hub_locations SET sort_order = ?, updated_at = ? WHERE id = ?", i, now, orderedIds.get(i));
        }
    }

    /** Soft delete — archives rather than removing, so devices still pointing at this id don't orphan. */
    public boolean archive(String id) {
        int rows = jdbc.update(
            "UPDATE hub_locations SET active = false, updated_at = ? WHERE id = ?",
            System.currentTimeMillis(), id);
        return rows > 0;
    }

    private HubLocation fromRow(Map<String, Object> row) {
        return new HubLocation(
            (String) row.get("id"),
            (String) row.get("label"),
            (String) row.get("api_base"),
            (String) row.get("ws_base"),
            (String) row.get("grafana_url"),
            (String) row.get("nodered_url"),
            (String) row.get("ha_url"),
            (String) row.get("frigate_url"),
            (String) row.get("z2m_url"),
            (String) row.get("family_hub_url"),
            ((Number) row.get("sort_order")).intValue(),
            (Boolean) row.get("active"),
            ((Number) row.get("created_at")).longValue(),
            ((Number) row.get("updated_at")).longValue());
    }
}
