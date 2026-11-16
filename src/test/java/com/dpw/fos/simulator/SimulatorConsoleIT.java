package com.dpw.fos.simulator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dpw.fos.config.FosProperties;
import com.dpw.fos.contract.ContractValidator;
import com.dpw.fos.domain.*;
import com.dpw.fos.modules.ModuleControl;
import com.dpw.fos.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class SimulatorConsoleIT {
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine");

  private static DriverManagerDataSource dataSource;

  @BeforeAll
  static void database() {
    String url = System.getenv("FOS_TEST_JDBC_URL");
    if (url == null || url.isBlank()) {
      postgres.start();
      dataSource =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    } else {
      dataSource =
          new DriverManagerDataSource(
              url, System.getenv("FOS_TEST_DB_USER"), System.getenv("FOS_TEST_DB_PASSWORD"));
    }
  }

  @AfterAll
  static void stopDatabase() {
    if (postgres.isRunning()) postgres.stop();
  }

  @Test
  void persistedSessionsGateJobsAndRoutesSurviveModeChanges() throws Exception {
    var ds = dataSource;
    Flyway.configure().dataSource(ds).load().migrate();
    var db = new JdbcTemplate(ds);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    var inbox = new InboxRepository(db, tx);
    var modules = new ModuleControl(db, tx, inbox);
    var fos = mock(FosProperties.class);
    when(fos.devAuth()).thenReturn(true);
    when(fos.maxBytes()).thenReturn(262144);
    when(fos.maxIdentifier()).thenReturn(128);
    when(fos.maxOperatorName()).thenReturn(256);
    when(fos.maxFutureSkew()).thenReturn(Duration.ofSeconds(30));
    when(fos.leaseDuration()).thenReturn(Duration.ofSeconds(45));
    var env = new MockEnvironment();
    env.setActiveProfiles("local");
    var mapper = new ObjectMapper();
    var validator = new ContractValidator(fos);
    var console =
        new SimulatorConsole(
            db,
            tx,
            validator,
            inbox,
            modules,
            new SimulatorProperties(
                true,
                8090,
                "http://127.0.0.1:8080",
                "tos-local",
                "fms-local",
                "integration-test-password"),
            fos,
            env,
            mapper);
    modules.set("simulator", true, "test", "integration test");
    console.configure("REAL", "http://localhost:19090/jobs", false, 0, "test");
    var job = SimulationPayloads.create(mapper, "vehicle-job", "SIM-REAL-JOB", "TRUCK-1", "JOB-1");
    assertEquals(
        "VEHICLE_NOT_LOGGED_IN",
        assertThrows(FosException.class, () -> console.dispatch(job.toString(), "REAL", 1, "test"))
            .code);
    var login =
        SimulationPayloads.create(mapper, "vehicle-login-logout", "REAL-LOGIN", "TRUCK-1", "");
    var validated = validator.validate(MessageType.LOGIN_LOGOUT, mapper.writeValueAsBytes(login));
    inbox.accept(MessageType.LOGIN_LOGOUT, validated, "{\"transport\":\"HTTP\"}");
    var receipt = console.dispatch(job.toString(), "REAL", 1, "test");
    assertEquals(
        receipt.receiptId(), console.dispatch(job.toString(), "REAL", 1, "test").receiptId());
    var deliveries = new DeliveryRepository(db, tx, fos);
    var work = deliveries.claim("FMS", UUID.randomUUID()).orElseThrow();
    assertEquals("http://localhost:19090/jobs", work.simulatorUrl());
    assertTrue(deliveries.simulatorReady(work));
    login.put("msgId", "REAL-LOGOUT");
    login.put("action", "OUT");
    login.put("eventTimestamp", OffsetDateTime.now().toString());
    inbox.accept(
        MessageType.LOGIN_LOGOUT,
        validator.validate(MessageType.LOGIN_LOGOUT, mapper.writeValueAsBytes(login)),
        "{}");
    assertFalse(deliveries.simulatorReady(work));
    assertTrue(
        deliveries.finish(
            work,
            com.dpw.fos.adapter.http.ReceiverClient.Outcome.of("WAITING_SESSION", "LOGGED_OUT"),
            0));
    assertTrue(deliveries.claim("FMS", UUID.randomUUID()).isEmpty());
    // Duplicate and out-of-order logins do not undo logout or add duplicate events.
    inbox.accept(MessageType.LOGIN_LOGOUT, validated, "{}");
    assertFalse(deliveries.simulatorReady(work));
    login.put("msgId", "OLD-LOGIN");
    login.put("action", "IN");
    login.put("eventTimestamp", OffsetDateTime.now().minusMinutes(1).toString());
    inbox.accept(
        MessageType.LOGIN_LOGOUT,
        validator.validate(MessageType.LOGIN_LOGOUT, mapper.writeValueAsBytes(login)),
        "{}");
    assertFalse(deliveries.simulatorReady(work));
    assertEquals(3, db.queryForObject("SELECT count(*) FROM vehicle_session_event", Integer.class));
    assertEquals(
        "REAL_MODE_REQUIRES_REAL_FMS_EVENTS",
        assertThrows(FosException.class, () -> console.session("SIM-1", "IN", "test")).code);
    console.configure("SIMULATED", "http://localhost:19090/jobs", false, 1, "test");
    console.session("SIM-1", "IN", "test");
    assertEquals(
        "http://localhost:19090/jobs",
        db.queryForObject("SELECT destination FROM simulator_dispatch", String.class));
    assertEquals(
        "SETTINGS_CHANGED_REFRESH",
        assertThrows(FosException.class, () -> console.dispatch(job.toString(), "REAL", 1, "test"))
            .code);
    login.put("msgId", "REAL-NEW-LOGIN");
    login.put("eventTimestamp", OffsetDateTime.now().toString());
    inbox.accept(
        MessageType.LOGIN_LOGOUT,
        validator.validate(MessageType.LOGIN_LOGOUT, mapper.writeValueAsBytes(login)),
        "{}");
    var resumed = deliveries.claim("FMS", UUID.randomUUID()).orElseThrow();
    assertEquals(work.receipt(), resumed.receipt());
    assertEquals(work.simulatorUrl(), resumed.simulatorUrl());
    modules.set("simulator", false, "test", "stop");
    assertFalse(deliveries.simulatorReady(work));
  }
}
