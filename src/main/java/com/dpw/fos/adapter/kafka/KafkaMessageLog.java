package com.dpw.fos.adapter.kafka;

import com.dpw.fos.config.FosProperties;
import com.dpw.fos.domain.MessageType;
import java.security.*;
import java.util.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaMessageLog {
  private final JdbcTemplate db;
  private final FosProperties p;
  private final String cluster;

  public KafkaMessageLog(
      JdbcTemplate db, FosProperties p, @Value("${fos.kafka.cluster-id:primary}") String cluster) {
    if (cluster.isBlank() || cluster.length() > 128)
      throw new IllegalArgumentException("Invalid Kafka cluster ID");
    this.db = db;
    this.p = p;
    this.cluster = cluster;
  }

  public void record(
      MessageType type, ConsumerRecord<String, byte[]> r, UUID receipt, String failure) {
    byte[] raw = r.value() == null ? new byte[0] : r.value();
    try {
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
      db.update(
          """
INSERT INTO kafka_message_log(log_id,cluster_id,topic,partition_id,offset_id,record_key,broker_timestamp,message_type,status,failure_code,receipt_id,payload_hash,raw_bytes,payload_size)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(cluster_id,topic,partition_id,offset_id) DO NOTHING
""",
          UUID.randomUUID(),
          cluster,
          r.topic(),
          r.partition(),
          r.offset(),
          r.key() == null
              ? null
              : r.key().substring(0, Math.min(r.key().length(), 1024)).replace("\u0000", "\uFFFD"),
          r.timestamp(),
          type.name(),
          failure == null ? "ACCEPTED" : "QUARANTINED",
          failure,
          receipt,
          hash,
          raw.length <= p.maxBytes() ? raw : null,
          raw.length);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
