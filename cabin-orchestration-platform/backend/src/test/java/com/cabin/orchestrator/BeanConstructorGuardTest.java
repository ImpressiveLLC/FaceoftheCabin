package com.cabin.orchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repo-wide guard for a startup failure the unit tests cannot see. A Spring bean class with more than one
 * constructor needs exactly one marked {@code @Autowired}; with none marked, Spring falls back to a no-argument
 * constructor and, if there is none, stops with "No default constructor found". The application then never becomes
 * healthy and the deploy rolls back. It happened twice in thirteen days: AssistantInventoryService on 2026-10-16
 * and OutdoorWeatherService on 2026-10-29. Both classes had a public constructor for Spring and a package-private
 * one for tests, and their tests only ever called the second.
 *
 * This applies the same rule Spring does, to every stereotype-annotated class under com.cabin.orchestrator,
 * without starting a context.
 */
class BeanConstructorGuardTest {

    private static final Set<String> INJECTION_MARKERS =
        Set.of("org.springframework.beans.factory.annotation.Autowired", "jakarta.inject.Inject");

    @Test
    void everySpringBeanClassHasAConstructorSpringCanChoose() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (BeanDefinition definition : scanner.findCandidateComponents("com.cabin.orchestrator")) {
            Class<?> type = Class.forName(definition.getBeanClassName(), false, getClass().getClassLoader());
            checked++;
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length <= 1) continue;
            boolean marked = false;
            boolean noArgument = false;
            for (Constructor<?> constructor : constructors) {
                if (constructor.getParameterCount() == 0) noArgument = true;
                for (java.lang.annotation.Annotation annotation : constructor.getAnnotations()) {
                    if (INJECTION_MARKERS.contains(annotation.annotationType().getName())) marked = true;
                }
            }
            if (!marked && !noArgument) {
                problems.add(type.getName() + " (" + constructors.length + " constructors)");
            }
        }

        assertTrue(checked >= 20,
            "the scan found only " + checked + " bean classes, so this guard is not looking at the application");
        assertTrue(problems.isEmpty(),
            "Spring cannot start these beans (\"No default constructor found\"). Put @Autowired on the constructor "
                + "the container should use: " + problems);
    }
}
