package com.dpw.fos.simulator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dpw.fos.config.FosProperties;
import com.dpw.fos.contract.ContractValidator;
import com.dpw.fos.domain.FosException;
import com.dpw.fos.modules.ModuleControl;
import com.dpw.fos.persistence.InboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.*;

class SimulatorConsoleTest {
  JdbcTemplate db;
  TransactionTemplate tx;
  InboxRepository inbox;
  ModuleControl modules;
  SimulatorConsole console;
  ObjectMapper mapper = new ObjectMapper();
  Map<String, Object> config;

  @BeforeEach
  void setup() {
    db = mock(JdbcTemplate.class);
    tx = mock(TransactionTemplate.class);
    inbox = mock(InboxRepository.class);
    modules = mock(ModuleControl.class);
    var fos = mock(FosProperties.class);
    when(fos.devAuth()).thenReturn(true);
    when(fos.maxBytes()).thenReturn(262144);
    when(fos.maxIdentifier()).thenReturn(128);
    when(fos.maxOperatorName()).thenReturn(256);
    when(fos.maxFutureSkew()).thenReturn(java.time.Duration.ofSeconds(30));
    var env =
        new MockEnvironment()
            .withProperty("fos.simulator.real-fms-allowed-hosts", "localhost,fms.example.test");
    env.setActiveProfiles("local");
    console =
        new SimulatorConsole(
            db,
            tx,
            new ContractValidator(fos),
            inbox,
            modules,
            new SimulatorProperties(
                true,
                8090,
                "http://127.0.0.1:8080",
                "tos-local",
                "fms-local",
                "test-password-only"),
            fos,
            env,
            mapper);
    doAnswer(
            i ->
                ((TransactionCallback<?>) i.getArgument(0))
                    .doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)))
        .when(tx)
        .execute(any());
    when(modules.enabled("simulator")).thenReturn(true);
    config =
        new HashMap<>(
            Map.of(
                "mode",
                "REAL",
                "fms_url",
                "http://fms.example.test/jobs",
                "revision",
                2L,
                "idempotency_confirmed",
                false));
    when(db.queryForMap(anyString())).thenReturn(config);
    when(db.queryForObject(anyString(), eq(Boolean.class), anyString(), anyString()))
        .thenReturn(true);
    when(inbox.accept(any(), any(), anyString()))
        .thenReturn(new InboxRepository.Receipt(UUID.randomUUID(), "job", "TOS", "PENDING", null));
  }

  String payload() throws Exception {
    return SimulationPayloads.create(mapper, "vehicle-job", "SIM-JOB-1", "REAL-TRUCK-1", "J1")
        .toString();
  }

  @Test
  void rejectsUnsafeOrUnapprovedUrls() {
    for (String url :
        List.of(
            "file:///tmp/test",
            "http://unknown.example/jobs",
            "http://user:pw@localhost/jobs",
            "http://localhost/jobs?token=x",
            "http://localhost/jobs#fragment"))
      assertThrows(FosException.class, () -> console.validateUrl(url));
    assertEquals(
        "https://fms.example.test/jobs", console.validateUrl("https://fms.example.test/jobs"));
  }

  @Test
  void loggedOutVehicleNeverCreatesJob() throws Exception {
    when(db.queryForObject(anyString(), eq(Boolean.class), anyString(), anyString()))
        .thenReturn(false);
    assertEquals(
        "VEHICLE_NOT_LOGGED_IN",
        assertThrows(FosException.class, () -> console.dispatch(payload(), "REAL", 2, "admin"))
            .code);
    verifyNoInteractions(inbox);
  }

  @Test
  void staleModeOrRevisionNeverCreatesJob() {
    assertEquals(
        "SETTINGS_CHANGED_REFRESH",
        assertThrows(FosException.class, () -> console.dispatch(payload(), "SIMULATED", 2, "admin"))
            .code);
    assertEquals(
        "SETTINGS_CHANGED_REFRESH",
        assertThrows(FosException.class, () -> console.dispatch(payload(), "REAL", 1, "admin"))
            .code);
    verifyNoInteractions(inbox);
  }

  @Test
  void acceptedJobSnapshotsDestination() throws Exception {
    assertEquals("PENDING", console.dispatch(payload(), "REAL", 2, "admin").state());
    verify(db)
        .update(
            startsWith("INSERT INTO simulator_dispatch"),
            any(UUID.class),
            eq("REAL"),
            eq("http://fms.example.test/jobs"),
            eq(false),
            eq("admin"));
  }

  @Test
  void conflictingExistingRouteIsRejected() {
    when(db.queryForList(startsWith("SELECT i.receipt_id"), anyString()))
        .thenReturn(
            List.of(
                Map.of("mode", "SIMULATED", "destination", "http://localhost:8090/vehicle-job")));
    assertEquals(
        "MESSAGE_ALREADY_ROUTED_USE_NEW_ID",
        assertThrows(FosException.class, () -> console.dispatch(payload(), "REAL", 2, "admin"))
            .code);
    verifyNoInteractions(inbox);
  }

  @Test
  void invalidJsonAndDisabledModuleCannotDispatch() {
    assertThrows(FosException.class, () -> console.dispatch("{broken", "REAL", 2, "admin"));
    when(modules.enabled("simulator")).thenReturn(false);
    assertEquals(
        "SIMULATOR_DISABLED",
        assertThrows(FosException.class, () -> console.dispatch(payload(), "REAL", 2, "admin"))
            .code);
    verifyNoInteractions(inbox);
  }
}
