package com.dpw.fos.application;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "fos.retention.enabled", havingValue = "true")
public class PayloadRetention {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  public PayloadRetention(JdbcTemplate db, TransactionTemplate tx) {
    this.db = db;
    this.tx = tx;
  }

  @Scheduled(fixedDelayString = "${fos.retention.interval-ms:3600000}")
  public void purge() {
    tx.executeWithoutResult(
        s -> {
          int count =
              db.update(
                  """
WITH candidates AS(SELECT i.receipt_id FROM message_inbox i JOIN delivery d USING(receipt_id)
 WHERE NOT i.legal_hold AND i.payload IS NOT NULL AND d.state='DELIVERED'
  AND d.completed_at<clock_timestamp()-interval '7 days'
 LIMIT 500 FOR UPDATE OF i SKIP LOCKED)
UPDATE message_inbox SET payload=NULL,raw_payload=NULL WHERE receipt_id IN(SELECT receipt_id FROM candidates)
""");
          if (count > 0)
            db.update(
                "INSERT INTO audit_event(audit_id,actor,action,result)"
                    + " VALUES(?,'system','PURGE_PAYLOAD',?)",
                UUID.randomUUID(),
                Integer.toString(count));
        });
  }
}
