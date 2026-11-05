package com.dpw.fos.adapter.kafka;

import com.dpw.fos.application.AcceptanceService;
import com.dpw.fos.config.FosProperties;
import com.dpw.fos.contract.ContractValidator;
import com.dpw.fos.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.*;
import java.util.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "fos.kafka.enabled", havingValue = "true")
public class EventListener {
  private final AcceptanceService acceptance;
  private final ContractValidator validator;
  private final JdbcTemplate db;
  private final FosProperties p;
  private final ObjectMapper mapper;

  public EventListener(
      AcceptanceService acceptance,
      ContractValidator validator,
      JdbcTemplate db,
      FosProperties p,
      ObjectMapper mapper) {
    this.acceptance = acceptance;
    this.validator = validator;
    this.db = db;
    this.p = p;
    this.mapper = mapper;
  }

  @KafkaListener(topics = "${fos.kafka.yard-topic}")
  public void yard(ConsumerRecord<String, byte[]> r, Acknowledgment ack) {
    consume(MessageType.YARD_EVENT, r, ack);
  }

  @KafkaListener(topics = "${fos.kafka.operation-topic}")
  public void operation(ConsumerRecord<String, byte[]> r, Acknowledgment ack) {
    consume(MessageType.OPERATION_STATUS, r, ack);
  }

  public void consume(MessageType type, ConsumerRecord<String, byte[]> r, Acknowledgment ack) {
    byte[] raw = r.value() == null ? new byte[0] : r.value();
    try {
      var v = validator.validate(type, raw);
      if (!v.vehicleId().equals(r.key())) throw new FosException(400, "INVALID_KAFKA_KEY");
      acceptance.accept(
          type,
          raw,
          mapper.writeValueAsString(
              Map.of("topic", r.topic(), "partition", r.partition(), "offset", r.offset())));
    } catch (FosException e) {
      quarantine(r, raw, e.code);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Transport metadata serialization failed", e);
    }
    // Called only on consumer thread after DB transaction or quarantine insert has committed.
    ack.acknowledge();
  }

  private void quarantine(ConsumerRecord<String, byte[]> r, byte[] raw, String code) {
    try {
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
      db.update(
          "INSERT INTO"
              + " quarantine(quarantine_id,topic,partition_id,offset_id,payload_hash,raw_bytes,failure_code)"
              + " VALUES(?,?,?,?,?,?,?) ON CONFLICT(topic,partition_id,offset_id) DO NOTHING",
          UUID.randomUUID(),
          r.topic(),
          r.partition(),
          r.offset(),
          hash,
          raw.length <= p.maxBytes() ? raw : null,
          code);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
