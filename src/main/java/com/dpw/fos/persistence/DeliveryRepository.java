package com.dpw.fos.persistence;

import com.dpw.fos.adapter.http.ReceiverClient.Outcome;
import com.dpw.fos.config.FosProperties;
import com.dpw.fos.domain.MessageType;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class DeliveryRepository {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final FosProperties p;

  public DeliveryRepository(JdbcTemplate db, TransactionTemplate tx, FosProperties p) {
    this.db = db;
    this.tx = tx;
    this.p = p;
  }

  public record Work(
      UUID id,
      UUID receipt,
      MessageType type,
      String msgId,
      String payload,
      int attempt,
      long generation,
      UUID owner,
      Instant sourceTime) {}

  public Optional<Work> claim(String target, UUID owner) {
    return tx.execute(
        s -> {
          // A process dying during HTTP leaves an ambiguous effect. Expired leases always require
          // reconciliation.
          db.update(
              "UPDATE delivery SET"
                  + " state='UNKNOWN',last_outcome='LEASE_EXPIRED',lease_generation=lease_generation+1"
                  + " WHERE target=? AND state='IN_FLIGHT' AND lease_until<clock_timestamp()",
              target);
          var rows =
              db.queryForList(
                  """
SELECT d.delivery_id FROM delivery d WHERE d.target=? AND d.state IN ('PENDING','RETRY_WAIT') AND d.next_attempt_at<=clock_timestamp()
AND NOT EXISTS(SELECT 1 FROM delivery h WHERE h.stream_key=d.stream_key AND h.sequence<d.sequence AND h.state<>'DELIVERED')
ORDER BY d.next_attempt_at,d.delivery_id FOR UPDATE OF d SKIP LOCKED LIMIT 1
""",
                  target);
          if (rows.isEmpty()) return Optional.empty();
          UUID id = (UUID) rows.getFirst().get("delivery_id");
          db.update(
              "UPDATE delivery SET"
                  + " state='IN_FLIGHT',attempt_count=attempt_count+1,lease_owner=?,lease_until=clock_timestamp()+(?"
                  + " * interval '1 millisecond'),lease_generation=lease_generation+1 WHERE"
                  + " delivery_id=?",
              owner,
              p.leaseDuration().toMillis(),
              id);
          return Optional.of(
              db.queryForObject(
                  """
SELECT d.delivery_id,i.receipt_id,i.message_type,i.msg_id,i.raw_payload,d.attempt_count,d.lease_generation,i.source_timestamp
FROM delivery d JOIN message_inbox i USING(receipt_id) WHERE d.delivery_id=?
""",
                  (rs, n) ->
                      new Work(
                          rs.getObject(1, UUID.class),
                          rs.getObject(2, UUID.class),
                          MessageType.valueOf(rs.getString(3)),
                          rs.getString(4),
                          rs.getString(5),
                          rs.getInt(6),
                          rs.getLong(7),
                          owner,
                          rs.getTimestamp(8).toInstant()),
                  id));
        });
  }

  public boolean finish(Work w, Outcome o, long duration) {
    return Boolean.TRUE.equals(
        tx.execute(
            s -> {
              String state = o.state();
              if (state.equals("RETRY_WAIT") && w.attempt() >= p.maxAttempts()) state = "EXHAUSTED";
              int n =
                  db.update(
                      """
UPDATE delivery SET state=?,last_outcome=?,next_attempt_at=clock_timestamp()+(? * interval '1 millisecond'),
 lease_owner=null,lease_until=null,completed_at=CASE WHEN ?='DELIVERED' THEN clock_timestamp() ELSE NULL END
WHERE delivery_id=? AND state='IN_FLIGHT' AND lease_owner=? AND lease_generation=? AND lease_until>clock_timestamp()
""",
                      state,
                      o.category(),
                      o.delay().toMillis(),
                      state,
                      w.id(),
                      w.owner(),
                      w.generation());
              if (n == 0) return false;
              db.update(
                  "INSERT INTO"
                      + " delivery_attempt(attempt_id,delivery_id,attempt_number,http_code,business_code,duration_ms,outcome)"
                      + " VALUES(?,?,?,?,?,?,?)",
                  UUID.randomUUID(),
                  w.id(),
                  w.attempt(),
                  o.httpCode(),
                  o.businessCode(),
                  duration,
                  o.category());
              return true;
            }));
  }
}
