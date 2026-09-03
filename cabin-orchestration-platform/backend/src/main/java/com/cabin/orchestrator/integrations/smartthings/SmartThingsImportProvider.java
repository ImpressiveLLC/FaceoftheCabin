package com.cabin.orchestrator.integrations.smartthings;

import com.cabin.orchestrator.platformimport.PlatformImportProvider;
import com.cabin.orchestrator.platformimport.RawImportRecord;
import com.cabin.orchestrator.security.OAuthCredential;
import com.cabin.orchestrator.security.OAuthCredentialStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * WSJF #9. Vault entry name for this platform's OAuth token pair:
 * "smartthings_oauth" (CREDENTIAL_POINTER KnowledgeNode content, once an
 * admin actually completes OAuth -- see OAuthCredentialStore's own javadoc;
 * not seeded by CredentialPointerSeeder yet since the real vault entry
 * doesn't exist until then, and D5 forbids fabricating one ahead of that).
 *
 * The live HTTP call and the JSON-parsing logic are deliberately separate
 * methods: parseDevices() is a pure function, unit-testable with fixture
 * JSON and no live API call (see SmartThingsImportProviderTest) -- matching
 * this item's own success criterion.
 */
@Component
public class SmartThingsImportProvider implements PlatformImportProvider {

    public static final String VAULT_ENTRY_NAME = "smartthings_oauth";
    private static final String DEVICES_URL = "https://api.smartthings.com/v1/devices";
    private static final String LOCATION_URL = "https://api.smartthings.com/v1/locations/";

    /** SmartThings capability id -> D7 measurement_type. Extend as devices surface more; never guess a mapping that isn't in the spec. */
    private static final Map<String, String> CAPABILITY_TO_MEASUREMENT_TYPE = Map.of(
        "temperatureMeasurement", "temperature",
        "relativeHumidityMeasurement", "humidity",
        "motionSensor", "motion",
        "contactSensor", "contact",
        "waterSensor", "leak",
        "carbonMonoxideDetector", "co",
        "battery", "battery");

    private final OAuthCredentialStore credentialStore;
    private final RestTemplate http = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public SmartThingsImportProvider(OAuthCredentialStore credentialStore) {
        this.credentialStore = credentialStore;
    }

    @Override
    public String platform() {
        return "smartthings";
    }

    @Override
    public List<RawImportRecord> listDevices() {
        OAuthCredential credential = credentialStore.retrieve(VAULT_ENTRY_NAME)
            .orElseThrow(() -> new IllegalStateException(
                "No SmartThings OAuth credential in Vaultwarden (" + VAULT_ENTRY_NAME + ") -- complete OAuth first"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(credential.accessToken());
        String body = http.exchange(DEVICES_URL, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
        return resolveLocationNames(parseDevices(body), headers);
    }

    /** Pure, fixture-testable: turns a raw GET /v1/devices response body into RawImportRecords. originalLocation is the raw locationId here -- resolveLocationNames() (live path only) fills in the human name. */
    public List<RawImportRecord> parseDevices(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            List<RawImportRecord> records = new ArrayList<>();
            for (JsonNode item : root.path("items")) {
                String deviceId = item.path("deviceId").asText(null);
                String label = item.path("label").asText(null);
                String name = (label != null && !label.isBlank()) ? label : item.path("name").asText(null);
                String locationId = item.path("locationId").asText(null);
                List<String> measurementTypes = capabilityMeasurementTypes(item);
                @SuppressWarnings("unchecked")
                Map<String, Object> rawPayload = mapper.convertValue(item, Map.class);
                records.add(new RawImportRecord("smartthings", deviceId, name, locationId, measurementTypes, rawPayload));
            }
            return records;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse SmartThings device list response", e);
        }
    }

    private static List<String> capabilityMeasurementTypes(JsonNode item) {
        LinkedHashSet<String> measurementTypes = new LinkedHashSet<>();
        for (JsonNode component : item.path("components")) {
            for (JsonNode capability : component.path("capabilities")) {
                String id = capability.path("id").asText(null);
                String measurementType = CAPABILITY_TO_MEASUREMENT_TYPE.get(id);
                if (measurementType != null) measurementTypes.add(measurementType);
            }
        }
        return new ArrayList<>(measurementTypes);
    }

    /** Best-effort only -- a failed lookup leaves originalLocation as the raw locationId rather than failing the whole import. */
    private List<RawImportRecord> resolveLocationNames(List<RawImportRecord> records, HttpHeaders headers) {
        Map<String, String> locationNames = new HashMap<>();
        List<RawImportRecord> resolved = new ArrayList<>();
        for (RawImportRecord r : records) {
            String locationId = r.originalLocation();
            String name = locationId == null ? null : locationNames.computeIfAbsent(locationId, id -> fetchLocationName(id, headers));
            resolved.add(name != null
                ? new RawImportRecord(r.platform(), r.originalId(), r.originalName(), name, r.measurementTypeCandidates(), r.rawPayload())
                : r);
        }
        return resolved;
    }

    private String fetchLocationName(String locationId, HttpHeaders headers) {
        try {
            String body = http.exchange(LOCATION_URL + locationId, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
            return mapper.readTree(body).path("name").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}
