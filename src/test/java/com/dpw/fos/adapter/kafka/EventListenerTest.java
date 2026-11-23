package com.dpw.fos.adapter.kafka;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dpw.fos.TestProperties;
import com.dpw.fos.application.AcceptanceService;
import com.dpw.fos.contract.ContractValidator;
import com.dpw.fos.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;

class EventListenerTest {
  @Test
  void databaseFailureDoesNotAcknowledgeValidRecord() throws Exception {
    var service = mock(AcceptanceService.class);
    var db = mock(JdbcTemplate.class);
    var ack = mock(Acknowledgment.class);
    var p = TestProperties.create();
    var listener =
        new EventListener(
            service,
            new ContractValidator(p),
            db,
            p,
            new ObjectMapper(),
            mock(KafkaMessageLog.class));
    when(service.accept(any(), any(), any()))
        .thenThrow(new DataAccessResourceFailureException("down"));
    var r =
        new ConsumerRecord<String, byte[]>(
            "yard",
            0,
            0,
            "T501",
            Files.readAllBytes(Path.of("contracts/examples/vehicle-yard-event.json")));
    assertThrows(
        DataAccessResourceFailureException.class,
        () -> listener.consume(MessageType.YARD_EVENT, r, ack));
    verifyNoInteractions(ack);
  }

  @Test
  void quarantineFailureDoesNotAcknowledgePoison() {
    var service = mock(AcceptanceService.class);
    var db = mock(JdbcTemplate.class);
    var ack = mock(Acknowledgment.class);
    var p = TestProperties.create();
    when(db.update(anyString(), any(Object[].class)))
        .thenThrow(new DataAccessResourceFailureException("down"));
    var listener =
        new EventListener(
            service,
            new ContractValidator(p),
            db,
            p,
            new ObjectMapper(),
            mock(KafkaMessageLog.class));
    assertThrows(
        DataAccessResourceFailureException.class,
        () ->
            listener.consume(
                MessageType.YARD_EVENT,
                new ConsumerRecord<>("yard", 0, 1, "T501", new byte[0]),
                ack));
    verifyNoInteractions(ack);
  }

  @Test
  void journalFailurePreventsAcknowledgmentAfterAcceptance() throws Exception {
    var p = TestProperties.create();
    var service = mock(AcceptanceService.class);
    var db = mock(JdbcTemplate.class);
    var journal = mock(KafkaMessageLog.class);
    var ack = mock(Acknowledgment.class);
    var id = java.util.UUID.randomUUID();
    when(service.accept(any(), any(), any()))
        .thenReturn(
            new com.dpw.fos.persistence.InboxRepository.Receipt(id, "M", "FMS", "PENDING", null));
    var listener =
        new EventListener(service, new ContractValidator(p), db, p, new ObjectMapper(), journal);
    var record =
        new ConsumerRecord<String, byte[]>(
            "yard",
            0,
            4,
            "T501",
            Files.readAllBytes(Path.of("contracts/examples/vehicle-yard-event.json")));
    doThrow(new DataAccessResourceFailureException("journal down"))
        .when(journal)
        .record(any(), any(), any(), any());
    assertThrows(
        DataAccessResourceFailureException.class,
        () -> listener.consume(MessageType.YARD_EVENT, record, ack));
    verifyNoInteractions(ack);
    doNothing().when(journal).record(any(), any(), any(), any());
    listener.consume(MessageType.YARD_EVENT, record, ack);
    var order = inOrder(journal, ack);
    order.verify(journal, times(2)).record(MessageType.YARD_EVENT, record, id, null);
    order.verify(ack).acknowledge();
  }

  @Test
  void poisonIsJournaledBeforeAcknowledgment() {
    var p = TestProperties.create();
    var journal = mock(KafkaMessageLog.class);
    var ack = mock(Acknowledgment.class);
    var listener =
        new EventListener(
            mock(AcceptanceService.class),
            new ContractValidator(p),
            mock(JdbcTemplate.class),
            p,
            new ObjectMapper(),
            journal);
    var record = new ConsumerRecord<String, byte[]>("yard", 0, 7, "T501", new byte[0]);
    listener.consume(MessageType.YARD_EVENT, record, ack);
    var order = inOrder(journal, ack);
    order.verify(journal).record(eq(MessageType.YARD_EVENT), eq(record), isNull(), anyString());
    order.verify(ack).acknowledge();
  }
}
