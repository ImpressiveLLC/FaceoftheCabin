package com.cabin.orchestrator.api;

import com.cabin.orchestrator.alerts.AcknowledgeAlertRequest;
import com.cabin.orchestrator.alerts.ActiveAlertService;
import com.cabin.orchestrator.alerts.ActiveAlertsSnapshot;
import com.cabin.orchestrator.alerts.AlertAcknowledgmentPolicy;
import com.cabin.orchestrator.alerts.AlertHistoryService;
import com.cabin.orchestrator.alerts.JdbcAlertAcknowledgmentStore;
import com.cabin.orchestrator.alerts.JdbcAlertHistoryStore;
import com.cabin.orchestrator.automation.AutomationRuleService;
import com.cabin.orchestrator.events.CabinEventService;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Acknowledgment and history atomicity (Codex design, #244 comment 6970813730). Unlike AlertControllerTest, the controller
 * here is a Spring-managed transactional proxy over real PostgreSQL in a disposable Testcontainers database, so the test
 * exercises the actual @Transactional boundary. A history-insert failure is injected with a trigger in that throwaway
 * database only: the paired acknowledgment write must roll back with it.
 */
@Testcontainers
@SpringJUnitConfig(AlertControllerAtomicityTest.Config.class)
class AlertControllerAtomicityTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            return new SimpleDriverDataSource(new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) { return new DataSourceTransactionManager(dataSource); }
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean JdbcAlertHistoryStore historyStore(JdbcTemplate jdbc) { return new JdbcAlertHistoryStore(jdbc); }
        @Bean JdbcAlertAcknowledgmentStore acknowledgmentStore(JdbcTemplate jdbc, JdbcAlertHistoryStore history) {
            return new JdbcAlertAcknowledgmentStore(jdbc, history);
        }
        @Bean CabinEventService events(JdbcTemplate jdbc) { return new CabinEventService(jdbc); }
        @Bean ActiveAlertService activeAlerts() {
            ActiveAlertService active = mock(ActiveAlertService.class);
            when(active.snapshot()).thenReturn(new ActiveAlertsSnapshot(Instant.now(), List.of(), Map.of()));
            return active;
        }
        @Bean AlertController alertController(ActiveAlertService active, JdbcAlertAcknowledgmentStore acks,
                                              JdbcAlertHistoryStore historyStore, CabinEventService events) {
            return new AlertController(active, mock(AutomationRuleService.class), acks,
                new AlertHistoryService(historyStore, events), new AlertAcknowledgmentPolicy(active, events));
        }
    }

    @Autowired AlertController controller;
    @Autowired JdbcTemplate jdbc;

    private static final String KEY = "device:atomicity-test:alarm";

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_history_insert ON alert_history");
        jdbc.execute("TRUNCATE TABLE alert_acknowledgment");
        jdbc.execute("TRUNCATE TABLE alert_history");
    }

    @Test
    void theControllerIsATransactionalProxy() {
        assertTrue(AopUtils.isAopProxy(controller), "without a proxy @Transactional has no effect");
    }

    @Test
    void aSuccessfulAcknowledgmentCommitsBothRecords() {
        assertEquals(HttpStatus.OK, controller.acknowledge(ignore(), request()).getStatusCode());
        assertEquals(1, ackRows());
        assertEquals(1, historyRows());
    }

    @Test
    void aFailedHistoryInsertLeavesNoNewAcknowledgment() {
        failHistoryInserts();
        assertThrows(DataAccessException.class, () -> controller.acknowledge(ignore(), request()));
        assertEquals(0, ackRows(), "the alert must not stay silenced without its audit record");
        assertEquals(0, historyRows());
    }

    @Test
    void aFailedHistoryInsertOnUpdateKeepsThePriorAcknowledgment() {
        controller.acknowledge(ignore(), request());
        failHistoryInserts();
        assertThrows(DataAccessException.class, () -> controller.acknowledge(
            new AcknowledgeAlertRequest(KEY, "SNOOZED", Instant.now().plusSeconds(1800), null), request()));
        assertEquals("IGNORED", jdbc.queryForObject("SELECT mode FROM alert_acknowledgment WHERE alert_key = ?", String.class, KEY));
        assertEquals(1, historyRows(), "only the original IGNORED entry");
    }

    @Test
    void aFailedHistoryInsertOnClearKeepsTheOriginalAcknowledgment() {
        controller.acknowledge(ignore(), request());
        failHistoryInserts();
        assertThrows(DataAccessException.class, () -> controller.clearAcknowledgment(KEY, request()));
        assertEquals(1, ackRows(), "the alert stays acknowledged when its un-ignore cannot be recorded");
        assertEquals(1, historyRows());
    }

    @Test
    void aSuccessfulClearCommitsRemovalAndHistory() {
        controller.acknowledge(ignore(), request());
        assertEquals(HttpStatus.OK, controller.clearAcknowledgment(KEY, request()).getStatusCode());
        assertEquals(0, ackRows());
        assertEquals(List.of("IGNORED", "UN_IGNORED"),
            jdbc.queryForList("SELECT action FROM alert_history ORDER BY id", String.class));
    }

    @Test
    void anInvalidRequestWritesNothing() {
        assertEquals(HttpStatus.BAD_REQUEST,
            controller.acknowledge(new AcknowledgeAlertRequest(KEY, "MUTED", null), request()).getStatusCode());
        assertEquals(0, ackRows());
        assertEquals(0, historyRows());
    }

    /** Disposable test database only: every insert into alert_history raises an error. */
    private void failHistoryInserts() {
        jdbc.execute("""
            CREATE OR REPLACE FUNCTION fail_history_insert() RETURNS trigger AS $f$
            BEGIN RAISE EXCEPTION 'injected history failure'; END $f$ LANGUAGE plpgsql""");
        jdbc.execute("CREATE TRIGGER fail_history_insert BEFORE INSERT ON alert_history FOR EACH ROW EXECUTE FUNCTION fail_history_insert()");
    }

    private AcknowledgeAlertRequest ignore() { return new AcknowledgeAlertRequest(KEY, "IGNORED", null); }

    private int ackRows() { return jdbc.queryForObject("SELECT count(*) FROM alert_acknowledgment", Integer.class); }

    private int historyRows() { return jdbc.queryForObject("SELECT count(*) FROM alert_history", Integer.class); }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL, "atomicity@example.invalid");
        return request;
    }
}
