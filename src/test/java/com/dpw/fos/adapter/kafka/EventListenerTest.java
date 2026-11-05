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
    var listener = new EventListener(service, new ContractValidator(p), db, p, new ObjectMapper());
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
    var listener = new EventListener(service, new ContractValidator(p), db, p, new ObjectMapper());
    assertThrows(
        DataAccessResourceFailureException.class,
        () ->
            listener.consume(
                MessageType.YARD_EVENT,
                new ConsumerRecord<>("yard", 0, 1, "T501", new byte[0]),
                ack));
    verifyNoInteractions(ack);
  }
}
