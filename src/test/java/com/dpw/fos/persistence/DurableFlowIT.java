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

  @Test
  void endpointOverridesPersistAndConcurrentEditsAreRejected() {
    db.update("UPDATE endpoint_setting SET url=NULL,revision=0");
    var env = new org.springframework.mock.env.MockEnvironment();
    env.setActiveProfiles("local");
    var settings =
        new com.dpw.fos.admin.EndpointSettings(db, tx, TestProperties.create(), env, inbox);
    settings.save(
        "vehicle-job", "http://localhost:9999/jobs", true, 0, "UAT endpoint", "admin", false);
    assertEquals("http://localhost:9999/jobs", settings.resolve("vehicle-job").url());
    assertThrows(
        FosException.class,
        () ->
            settings.save(
                "vehicle-job",
                "http://localhost:8888/jobs",
                true,
                0,
                "stale edit",
                "admin",
                false));
    assertEquals("http://localhost:9999/jobs", settings.resolve("vehicle-job").url());
    settings.save("vehicle-job", "", false, 1, "reset", "admin", true);
    assertNull(settings.resolve("vehicle-job"));
  }

  @Test
  void historyIncludesResponsesAndKafkaLogsAndHonorsRetentionHold() throws Exception {
    var receipt = accept("history-test");
    var w = deliveries.claim("TOS", UUID.randomUUID()).orElseThrow();
    assertTrue(
        deliveries.finish(
            w,
            Outcome.of("DELIVERED", "COMPLETED")
                .exchange("https://localhost/login", "{\"code\":\"0\",\"token\":\"hidden\"}", 200),
            12));
    var history =
        new com.dpw.fos.admin.MessageHistory(db, new com.fasterxml.jackson.databind.ObjectMapper());
    var env = new org.springframework.mock.env.MockEnvironment();
    env.setActiveProfiles("local");
    var settings =
        new com.dpw.fos.admin.EndpointSettings(db, tx, TestProperties.create(), env, inbox);
    db.update(
        "UPDATE endpoint_setting SET url='http://localhost:9999/new',idempotency_confirmed=true"
            + " WHERE route='vehicle-login-logout'");
    assertEquals("https://localhost/login", settings.resolve(w).url());
    assertFalse(settings.resolve(w).idempotencyConfirmed());
    assertEquals(1, history.messages(0, "history-test", "DELIVERED", "TOS").size());
    assertEquals(0, history.messages(0, "missing", "", "TOS").size());
    assertTrue(
        history
            .detail(receipt.receiptId())
            .get("display_payload")
            .toString()
            .contains("history-test"));
    assertFalse(
        history
            .attempts(receipt.receiptId())
            .getFirst()
            .get("display_response")
            .toString()
            .contains("hidden"));
    var log = new com.dpw.fos.adapter.kafka.KafkaMessageLog(db, TestProperties.create(), "test");
    var record =
        new org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]>(
            "yard", 0, 9, "T501", "{}".getBytes(StandardCharsets.UTF_8));
    log.record(MessageType.YARD_EVENT, record, receipt.receiptId(), null);
    log.record(MessageType.YARD_EVENT, record, receipt.receiptId(), null);
    assertEquals(1, history.kafka(0, "T501", "ACCEPTED", "yard").size());
    log.record(
        MessageType.YARD_EVENT,
        new org.apache.kafka.clients.consumer.ConsumerRecord<>(
            "yard", 1, 9, "BAD", "invalid".getBytes(StandardCharsets.UTF_8)),
        null,
        "INVALID_CONTRACT");
    assertEquals(1, history.kafka(0, "", "QUARANTINED", "yard").size());
    db.update("UPDATE kafka_message_log SET received_at=clock_timestamp()-interval '10 days'");
    db.update("UPDATE delivery_attempt SET attempted_at=clock_timestamp()-interval '10 days'");
    db.update("UPDATE message_inbox SET legal_hold=true");
    var retention = new com.dpw.fos.application.OperationsLogRetention(db, 7);
    retention.purge();
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM kafka_message_log WHERE raw_bytes IS NOT NULL", Integer.class));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM delivery_attempt WHERE response_body IS NOT NULL",
            Integer.class));
    db.update("UPDATE message_inbox SET legal_hold=false");
    retention.purge();
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM kafka_message_log WHERE raw_bytes IS NOT NULL", Integer.class));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM delivery_attempt WHERE response_body IS NOT NULL",
            Integer.class));
  }
}
