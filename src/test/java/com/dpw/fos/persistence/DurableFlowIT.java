package com.dpw.fos.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.dpw.fos.TestProperties;
import com.dpw.fos.adapter.http.ReceiverClient.Outcome;
import com.dpw.fos.application.PayloadRetention;
import com.dpw.fos.contract.ContractValidator;
import com.dpw.fos.domain.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class DurableFlowIT {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine");

  JdbcTemplate db;
  TransactionTemplate tx;
  InboxRepository inbox;
  DeliveryRepository deliveries;
  ContractValidator validator;

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    db = new JdbcTemplate(ds);
    db.execute(
        "TRUNCATE"
            + " replay_request,audit_event,delivery_attempt,delivery,message_inbox,ordering_stream,quarantine"
            + " CASCADE");
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    inbox = new InboxRepository(db, tx);
    deliveries = new DeliveryRepository(db, tx, TestProperties.create());
    validator = new ContractValidator(TestProperties.create());
  }

  InboxRepository.Receipt accept(String id) throws Exception {
    String s =
        Files.readString(Path.of("contracts/examples/vehicle-login-logout.json"))
            .replace("LOGIN-20261022-000001", id);
    return inbox.accept(
        MessageType.LOGIN_LOGOUT,
        validator.validate(MessageType.LOGIN_LOGOUT, s.getBytes(StandardCharsets.UTF_8)),
        "{}");
  }

  @Test
  void concurrentDuplicatesHaveOneDeliveryAndConflictsNeverOverwrite() throws Exception {
    try (var executor = Executors.newFixedThreadPool(8)) {
      var calls = new ArrayList<Callable<UUID>>();
      for (int i = 0; i < 16; i++) calls.add(() -> accept("same").receiptId());
      var results = executor.invokeAll(calls);
      Set<UUID> ids = new HashSet<>();
      for (var r : results) ids.add(r.get());
      assertEquals(1, ids.size());
    }
    assertEquals(1, db.queryForObject("SELECT count(*) FROM delivery", Integer.class));
    String s =
        Files.readString(Path.of("contracts/examples/vehicle-login-logout.json"))
            .replace("LOGIN-20261022-000001", "same")
            .replace("Operations User", "Changed");
    assertEquals(
        409,
        assertThrows(
                FosException.class,
                () ->
                    inbox.accept(
                        MessageType.LOGIN_LOGOUT,
                        validator.validate(
                            MessageType.LOGIN_LOGOUT, s.getBytes(StandardCharsets.UTF_8)),
                        "{}"))
            .status);
  }

  @Test
  void orderingUnknownAndGenerationFence() throws Exception {
    var first = accept("first");
    accept("second");
    var work = deliveries.claim("TOS", UUID.randomUUID()).orElseThrow();
    assertEquals(first.receiptId(), work.receipt());
    assertTrue(deliveries.claim("TOS", UUID.randomUUID()).isEmpty());
    db.update(
        "UPDATE delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE delivery_id=?",
        work.id());
    assertTrue(deliveries.claim("TOS", UUID.randomUUID()).isEmpty());
    assertFalse(deliveries.finish(work, Outcome.of("DELIVERED", "COMPLETED"), 1));
    assertEquals("UNKNOWN", inbox.get(first.receiptId()).state());
  }

  @Test
  void deliveredHeadAllowsNextAndStoresAttempt() throws Exception {
    accept("first");
    var second = accept("second");
    var work = deliveries.claim("TOS", UUID.randomUUID()).orElseThrow();
    assertTrue(deliveries.finish(work, Outcome.of("DELIVERED", "COMPLETED"), 10));
    assertEquals(
        second.receiptId(), deliveries.claim("TOS", UUID.randomUUID()).orElseThrow().receipt());
    assertEquals(1, db.queryForObject("SELECT count(*) FROM delivery_attempt", Integer.class));
  }

  @Test
  void failedTransactionDoesNotAccept() throws Exception {
    db.execute("ALTER TABLE delivery ADD CONSTRAINT injected_failure CHECK(false) NOT VALID");
    try {
      assertThrows(Exception.class, () -> accept("failed"));
      assertEquals(0, db.queryForObject("SELECT count(*) FROM message_inbox", Integer.class));
    } finally {
      db.execute("ALTER TABLE delivery DROP CONSTRAINT injected_failure");
    }
  }

  @Test
  void replayTokenIsIdempotentAndBoundToReasonAndReceipt() throws Exception {
    var a = accept("a");
    db.update("UPDATE delivery SET state='BUSINESS_REJECTED' WHERE receipt_id=?", a.receiptId());
    inbox.replay(a.receiptId(), "support", "token", "reviewed");
    inbox.replay(a.receiptId(), "support", "token", "reviewed");
    assertEquals(1, db.queryForObject("SELECT count(*) FROM audit_event", Integer.class));
    assertThrows(
        FosException.class, () -> inbox.replay(a.receiptId(), "support", "token", "different"));
    assertThrows(
        FosException.class, () -> inbox.replay(a.receiptId(), "support", "other", "reviewed"));
  }

  @Test
  void purgeKeepsTombstoneAndUnresolvedPayload() throws Exception {
    var a = accept("done");
    var b = accept("pending");
    db.update(
        "UPDATE delivery SET state='DELIVERED',completed_at=clock_timestamp()-interval '8 days'"
            + " WHERE receipt_id=?",
        a.receiptId());
    new PayloadRetention(db, tx).purge();
    assertNull(
        db.queryForObject(
            "SELECT raw_payload FROM message_inbox WHERE receipt_id=?",
            String.class,
            a.receiptId()));
    assertNotNull(
        db.queryForObject(
            "SELECT raw_payload FROM message_inbox WHERE receipt_id=?",
            String.class,
            b.receiptId()));
    assertEquals(a.receiptId(), accept("done").receiptId());
  }
}
