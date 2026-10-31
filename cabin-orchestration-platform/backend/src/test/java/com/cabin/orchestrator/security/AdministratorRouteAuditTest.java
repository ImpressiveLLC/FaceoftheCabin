package com.cabin.orchestrator.security;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * "Signed-in admin only" for account and access management, enforced per route and kept enforced (safety order 2026-10-31).
 * Every mapped route of CabinAccessTokensController and ManagedUsersController must refuse a caller who is not an
 * ADMINISTRATOR with 403 before it does anything, except the one documented exemption. A route added later without the check
 * fails this test: it is called with a non-administrator request and must throw 403, so a route that forgets the check, or
 * does not even take the request, fails the build.
 */
class AdministratorRouteAuditTest {

    /** The single-use magic-link consume: the one-time token is its credential, a managed user has no Google account. */
    private static final Set<String> EXEMPT = Set.of("ManagedUsersController.consumeMagicLink");

    private static final List<Object> CONTROLLERS = List.of(
        new CabinAccessTokensController(mock(CabinAccessTokenService.class)),
        new ManagedUsersController(mock(ManagedUserService.class)));

    private static final List<HouseholdRole> NOT_ADMIN = Arrays.stream(HouseholdRole.values())
        .filter(r -> r != HouseholdRole.ADMINISTRATOR).toList();

    private static List<Method> routes(Object controller) {
        return Arrays.stream(controller.getClass().getDeclaredMethods())
            .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
            .filter(m -> AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class) != null)
            .toList();
    }

    private static String name(Object controller, Method m) {
        return controller.getClass().getSimpleName() + "." + m.getName();
    }

    private static MockHttpServletRequest requestWith(HouseholdRole role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL, "someone@example.com");
        if (role != null) request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE, role);
        return request;
    }

    private static Object[] argsFor(Method m, HttpServletRequest request) {
        Parameter[] parameters = m.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Class<?> type = parameters[i].getType();
            if (HttpServletRequest.class.isAssignableFrom(type)) args[i] = request;
            else if (type == String.class) args[i] = "x";
            else if (type == int.class) args[i] = 30;
            else if (type == long.class) args[i] = 30L;
            else if (type == boolean.class) args[i] = false;
            else if (Map.class.isAssignableFrom(type)) args[i] = new HashMap<>();
            else args[i] = null;
        }
        return args;
    }

    @Test
    void theAuditSeesTheRoutesItShould() {
        List<String> found = new ArrayList<>();
        for (Object c : CONTROLLERS) for (Method m : routes(c)) found.add(name(c, m));
        assertTrue(found.containsAll(List.of("CabinAccessTokensController.list", "CabinAccessTokensController.activity",
            "CabinAccessTokensController.create", "CabinAccessTokensController.revoke", "ManagedUsersController.list",
            "ManagedUsersController.create", "ManagedUsersController.deactivate", "ManagedUsersController.reactivate",
            "ManagedUsersController.invite", "ManagedUsersController.consumeMagicLink")), found.toString());
        assertTrue(found.containsAll(EXEMPT), "the exemption names a real route");
    }

    @Test
    void everyRouteRefusesAnyoneWhoIsNotAnAdministratorWith403() {
        for (Object controller : CONTROLLERS) {
            for (Method m : routes(controller)) {
                String route = name(controller, m);
                if (EXEMPT.contains(route)) continue;
                assertTrue(Arrays.stream(m.getParameterTypes()).anyMatch(HttpServletRequest.class::isAssignableFrom),
                    route + " takes no HttpServletRequest, so it cannot check the caller: add it and call AdministratorOnly.require");
                List<HouseholdRole> callers = new ArrayList<>(NOT_ADMIN);
                callers.add(null);   // an unresolved role is refused too
                for (HouseholdRole role : callers) {
                    Throwable thrown = assertThrows(InvocationTargetException.class,
                        () -> m.invoke(controller, argsFor(m, requestWith(role))), route + " as " + role).getCause();
                    assertInstanceOf(ResponseStatusException.class, thrown, route + " as " + role + ": " + thrown);
                    assertEquals(HttpStatus.FORBIDDEN, ((ResponseStatusException) thrown).getStatusCode(), route + " as " + role);
                }
            }
        }
    }

    @Test
    void anAdministratorGetsPastTheCheckOnEveryRoute() {
        for (Object controller : CONTROLLERS) {
            for (Method m : routes(controller)) {
                String route = name(controller, m);
                if (EXEMPT.contains(route)) continue;
                try {
                    m.invoke(controller, argsFor(m, requestWith(HouseholdRole.ADMINISTRATOR)));
                } catch (InvocationTargetException e) {
                    // The services are mocks, so a route may fail on its arguments; what must NOT happen is a 403.
                    if (e.getCause() instanceof ResponseStatusException r) {
                        fail(route + " refused an administrator: " + r.getStatusCode());
                    }
                } catch (ReflectiveOperationException e) {
                    fail(route + ": " + e);
                }
            }
        }
    }

    @Test
    void theMagicLinkConsumeStaysOpenWithNoCaller() {
        Method consume = routes(CONTROLLERS.get(1)).stream().filter(m -> m.getName().equals("consumeMagicLink")).findFirst().orElseThrow();
        try {
            consume.invoke(CONTROLLERS.get(1), "not-a-real-token");
        } catch (InvocationTargetException e) {
            assertFalse(e.getCause() instanceof ResponseStatusException, "the consume route must not demand an administrator: " + e.getCause());
        } catch (ReflectiveOperationException e) {
            fail(e);
        }
    }
}
