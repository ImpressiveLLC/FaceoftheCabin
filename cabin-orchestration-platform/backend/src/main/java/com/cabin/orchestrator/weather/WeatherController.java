package com.cabin.orchestrator.weather;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/weather/outdoor-history?location=cabin&days=30 -- daily outdoor temperature,
 * dew point and relative humidity from the location's configured airport station(s).
 * Public weather data with no device or household detail, open like telemetry-history so the
 * sensor chart works for the same viewers. Capped at the shared history ceiling
 * (cabin.history.max-days) like every other history route.
 */
@RestController
@RequestMapping("/api/weather")
public class WeatherController {

    private final OutdoorWeatherService service;

    @Value("${cabin.history.max-days:60}")
    private int maxHistoryDays = 60;

    public WeatherController(OutdoorWeatherService service) { this.service = service; }

    @GetMapping("/outdoor-history")
    public OutdoorHistoryResponse outdoorHistory(
            @RequestParam(name = "location") String location,
            @RequestParam(name = "days", required = false, defaultValue = "30") int days) {
        return service.history(location, days, maxHistoryDays);
    }
}
