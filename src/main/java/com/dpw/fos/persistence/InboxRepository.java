package com.dpw.fos.persistence;

import com.dpw.fos.contract.ContractValidator.Validated;
import com.dpw.fos.domain.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class InboxRepository {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  public InboxRepository(JdbcTemplate db, TransactionTemplate tx) {
    this.db = db;
    this.tx = tx;
  }

  public record Receipt(
      UUID receiptId, String msgId, String source, String state, String outcome) {}

  public Receipt accept(MessageType type, Validated v, String metadata) {
    return tx.execute(
        s -> {
          UUID id = UUID.randomUUID();
          int inserted =
              db.update(
                  """
INSERT INTO message_inbox(receipt_id,source,message_type,msg_id,vehicle_id,source_timestamp,source_timestamp_text,payload_hash,payload,raw_payload,transport_metadata,contract_version)
VALUES(?,?,?,?,?,?,?,?,?::jsonb,?,?::jsonb,'development-v1') ON CONFLICT(source,message_type,msg_id) DO NOTHING
""",
                  id,
                  type.source,
                  type.name(),
                  v.msgId(),
                  v.vehicleId(),
                  v.time(),
                  v.timestamp(),
                  v.hash(),
                  v.raw(),
                  v.raw(),
                  metadata);
          if (inserted == 0) {
            var rows =
                db.queryForList(
                    "SELECT receipt_id,payload_hash FROM message_inbox WHERE source=? AND"
                        + " message_type=? AND msg_id=?",
                    type.source,
                    type.name(),
                    v.msgId());
            var existing = rows.getFirst();
            if (!v.hash().equals(existing.get("payload_hash")))
              throw new FosException(409, "IDENTITY_CONFLICT");
            return get((UUID) existing.get("receipt_id"));
          }
          String stream = type.source + ":" + type.name() + ":" + v.vehicleId();
          db.update(
              "INSERT INTO ordering_stream(stream_key) VALUES(?) ON CONFLICT DO NOTHING", stream);
          Long seq =
              db.queryForObject(
                  "UPDATE ordering_stream SET next_sequence=next_sequence+1 WHERE stream_key=?"
                      + " RETURNING next_sequence",
                  Long.class,
                  stream);
          db.update(
              "INSERT INTO delivery(delivery_id,receipt_id,target,state,stream_key,sequence)"
                  + " VALUES(?,?,?,'PENDING',?,?)",
              UUID.randomUUID(),
              id,
              type.target,
              stream,
              seq);
          return get(id);
        });
  }

  public Receipt get(UUID id) {
    var r =
        db.query(
            """
SELECT i.receipt_id,i.msg_id,i.source,d.state,d.last_outcome FROM message_inbox i JOIN delivery d USING(receipt_id) WHERE i.receipt_id=?
""",
            (rs, n) ->
                new Receipt(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5)),
            id);
    if (r.isEmpty()) throw new FosException(404, "NOT_FOUND");
    return r.getFirst();
  }

  public List<Map<String, Object>> list(int page, int size, String state) {
    if (page < 0 || page > 10000 || size < 1 || size > 100)
      throw new FosException(400, "INVALID_PAGE");
    return db.queryForList(
        """
SELECT i.receipt_id,i.message_type,i.received_at,d.target,d.state,d.attempt_count,d.last_outcome
FROM message_inbox i JOIN delivery d USING(receipt_id) WHERE (?::text IS NULL OR d.state=?)
ORDER BY i.received_at DESC,i.receipt_id LIMIT ? OFFSET ?
""",
        state,
        state,
        size,
        page * size);
  }

  public void audit(String actor, String action, UUID id, String reason, String result) {
    db.update(
        "INSERT INTO audit_event(audit_id,actor,action,receipt_id,reason,result)"
            + " VALUES(?,?,?,?,?,?)",
        UUID.randomUUID(),
        actor,
        action,
        id,
        reason,
        result);
  }

  public void replay(UUID id, String actor, String token, String reason) {
    tx.executeWithoutResult(
        s -> {
          // Serialize tokens across deliveries before taking a delivery lock: no check-then-insert
          // race.
          db.queryForObject(
              "SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",
              Boolean.class,
              actor + ":" + token);
          var prior =
              db.queryForList(
                  "SELECT receipt_id,reason FROM replay_request WHERE actor=? AND request_token=?",
                  actor,
                  token);
          if (!prior.isEmpty()) {
            if (!id.equals(prior.getFirst().get("receipt_id"))
                || !reason.equals(prior.getFirst().get("reason")))
              throw new FosException(409, "REPLAY_TOKEN_CONFLICT");
            return;
          }
          var rows =
              db.queryForList(
                  "SELECT d.state,i.message_type,i.raw_payload FROM delivery d JOIN message_inbox i"
                      + " USING(receipt_id) WHERE receipt_id=? FOR UPDATE OF d",
                  id);
          if (rows.isEmpty()) throw new FosException(404, "NOT_FOUND");
          var row = rows.getFirst();
          // Ambiguous, completed, expired and remote-accepted deliveries need counterpart
          // reconciliation, never blind replay.
          if (!Set.of("BUSINESS_REJECTED", "EXHAUSTED").contains(row.get("state"))
              || row.get("raw_payload") == null
              || "OPERATION_STATUS".equals(row.get("message_type")))
            throw new FosException(409, "REPLAY_REQUIRES_RECONCILIATION");
          db.update(
              "INSERT INTO replay_request(actor,request_token,receipt_id,reason) VALUES(?,?,?,?)",
              actor,
              token,
              id,
              reason);
          db.update(
              "UPDATE delivery SET"
                  + " state='PENDING',next_attempt_at=clock_timestamp(),last_outcome='MANUAL_REPLAY'"
                  + " WHERE receipt_id=?",
              id);
          audit(actor, "REPLAY", id, reason, "PENDING");
        });
  }
}
