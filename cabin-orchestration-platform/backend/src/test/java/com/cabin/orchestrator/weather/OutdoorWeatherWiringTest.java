package com.cabin.orchestrator.weather;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * 2026-10-29: the first deploy of OutdoorWeatherService rolled back at the health check because it had two
 * constructors and neither was @Autowired, so Spring found "No default constructor". That is the same failure
 * AssistantInventoryService had on 2026-10-16. The unit tests build this class by hand with the package-private
 * constructor and could not see it. This builds the beans the way the application does, through a Spring context,
 * with no database and no network (the store is a mock; no application-ready event is ever published here, so the
 * start-up catch-up thread never runs).
 */
class OutdoorWeatherWiringTest {

    @Test
    void theWeatherBeansAreCreatedByTheContainerWithTheirDefaults() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("outdoorWeatherStore", mock(OutdoorWeatherStore.class));
            context.register(MesonetHttpFetcher.class, OutdoorWeatherService.class, WeatherController.class);
            context.refresh();

            assertNotNull(context.getBean(WeatherController.class));
            assertInstanceOf(MesonetHttpFetcher.class, context.getBean(WeatherFetcher.class));
            OutdoorWeatherService service = context.getBean(OutdoorWeatherService.class);
            // The default station list only appears if the container used the public, @Value-driven constructor.
            assertEquals(List.of("UBE", "RPD"), service.stationsFor("cabin"));
            assertEquals(List.of(), service.stationsFor("home"));
        }
    }

    @Test
    void theStationListAndTheSwitchComeFromConfiguration() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "cabin.weather.enabled", "false",
                "cabin.weather.stations", "cabin=AAA;home=BBB,CCC")));
            context.getBeanFactory().registerSingleton("outdoorWeatherStore", mock(OutdoorWeatherStore.class));
            context.getBeanFactory().registerSingleton("weatherFetcher", mock(WeatherFetcher.class));
            context.register(OutdoorWeatherService.class);
            context.refresh();

            OutdoorWeatherService service = context.getBean(OutdoorWeatherService.class);
            assertEquals(List.of("AAA"), service.stationsFor("cabin"));
            assertEquals(List.of("BBB", "CCC"), service.stationsFor("home"));
        }
    }
}
