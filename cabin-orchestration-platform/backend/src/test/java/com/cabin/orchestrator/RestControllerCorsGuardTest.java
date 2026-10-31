package com.cabin.orchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The UI is served from one origin (cabin.) and calls the API on another (api.), so a controller without
 * {@code @CrossOrigin} works in curl and in every unit test but is blocked by the browser. The outdoor weather
 * baseline shipped that way on 2026-10-29: the checkbox was on, the data was in the API, and the chart showed
 * nothing. This fails the build for the next controller that forgets it.
 */
class RestControllerCorsGuardTest {

    @Test
    void everyRestControllerAllowsCrossOriginCalls() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (BeanDefinition definition : scanner.findCandidateComponents("com.cabin.orchestrator")) {
            Class<?> type = Class.forName(definition.getBeanClassName(), false, getClass().getClassLoader());
            checked++;
            if (AnnotatedElementUtils.findMergedAnnotation(type, CrossOrigin.class) == null) missing.add(type.getName());
        }
        assertTrue(checked >= 20, "the scan found only " + checked + " controllers, so this guard is not looking at the application");
        assertTrue(missing.isEmpty(), "REST controllers without @CrossOrigin (the browser blocks their responses): " + missing);
    }
}
