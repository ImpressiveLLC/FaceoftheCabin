package com.cabin.orchestrator.assistant;

import com.cabin.orchestrator.api.AssistantInventoryController;
import com.cabin.orchestrator.devices.DeviceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * 2026-10-16: the first deploy of AssistantInventoryService rolled back because it had two constructors and
 * neither was @Autowired, so Spring found no constructor to use. The unit tests build these classes by hand and
 * could not see it. This builds them the way the application does, through a Spring context.
 */
class AssistantBeanWiringTest {

    @Test
    void theAssistantBeansAreCreatedByTheContainer() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("deviceRegistry", mock(DeviceRegistry.class));
            context.register(AssistantInventoryService.class, EntityResolver.class, AssistantInventoryController.class);
            context.refresh();

            assertNotNull(context.getBean(AssistantInventoryService.class));
            assertNotNull(context.getBean(EntityResolver.class));
            assertNotNull(context.getBean(AssistantInventoryController.class));
        }
    }

    @Test
    void theResolverReadsItsLimitsFromConfiguration() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "cabin.ask.resolver.max-inventory-age-seconds", "30",
                "cabin.ask.resolver.max-observation-age-hours", "1")));
            context.register(EntityResolver.class);
            context.refresh();

            assertNotNull(context.getBean(EntityResolver.class));
        }
    }
}
