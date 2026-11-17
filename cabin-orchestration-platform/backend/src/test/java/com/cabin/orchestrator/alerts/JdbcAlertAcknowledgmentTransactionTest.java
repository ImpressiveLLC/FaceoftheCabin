package com.cabin.orchestrator.alerts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the production annotation through Spring's transaction interceptor.
 * JDBC is mocked: PostgreSQL statement/persistence behavior needs the container suite. */
class JdbcAlertAcknowledgmentTransactionTest {
    private Connection connection;
    private JdbcAlertHistoryStore history;
    private JdbcAlertAcknowledgmentStore store;

    @BeforeEach
    void setUp() throws Exception {
        DataSource source = mock(DataSource.class);
        connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        history = mock(JdbcAlertHistoryStore.class);
        ResultSet expired = mock(ResultSet.class);
        when(expired.getString("alert_key")).thenReturn("device:test:alarm");
        when(expired.getTimestamp("snoozed_until"))
            .thenReturn(Timestamp.from(Instant.parse("2026-11-16T00:00:00Z")));
        doAnswer(call -> {
            call.getArgument(1, RowCallbackHandler.class).processRow(expired);
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));
        ProxyFactory proxy = new ProxyFactory(new JdbcAlertAcknowledgmentStore(jdbc, history));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
            new AnnotationTransactionAttributeSource()));
        store = (JdbcAlertAcknowledgmentStore) proxy.getProxy();
    }

    @Test
    void historyFailureRollsBackTheExpiryTransaction() throws Exception {
        doThrow(new IllegalStateException("history unavailable")).when(history).append(any());
        assertThrows(IllegalStateException.class, () -> store.activeAcknowledgments());
        verify(connection).rollback();
        verify(connection, never()).commit();
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void successfulHistoryWriteParticipatesInTheExpiryTransaction() throws Exception {
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            AlertHistoryEntry entry = call.getArgument(0);
            assertEquals("SNOOZE_EXPIRED", entry.action());
            assertEquals(Instant.parse("2026-11-16T00:00:00Z"), entry.occurredAt());
            return null;
        }).when(history).append(any());
        store.activeAcknowledgments();
        verify(history).append(any());
        verify(connection).commit();
        verify(connection, never()).rollback();
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
}
