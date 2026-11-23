package com.dpw.fos.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OperationsLogRetention {
  private final JdbcTemplate db;
  private final int days;

  public OperationsLogRetention(
      JdbcTemplate db, @Value("${fos.operations-log.payload-retention-days:7}") int days) {
    if (days < 1 || days > 3650)
      throw new IllegalArgumentException("Log payload retention must be 1–3650 days");
    this.db = db;
    this.days = days;
  }

  @Scheduled(
      initialDelayString = "${fos.operations-log.cleanup-ms:3600000}",
      fixedDelayString = "${fos.operations-log.cleanup-ms:3600000}")
  public void purge() {
    db.update(
        """
UPDATE kafka_message_log SET raw_bytes=NULL WHERE log_id IN (
 SELECT k.log_id FROM kafka_message_log k LEFT JOIN message_inbox i USING(receipt_id)
 WHERE k.raw_bytes IS NOT NULL AND k.received_at<clock_timestamp()-(? * interval '1 day')
 AND NOT coalesce(i.legal_hold,false) LIMIT 500)
""",
        days);
    db.update(
        """
UPDATE delivery_attempt SET response_body=NULL WHERE attempt_id IN (
 SELECT a.attempt_id FROM delivery_attempt a JOIN delivery d USING(delivery_id) JOIN message_inbox i USING(receipt_id)
 WHERE a.response_body IS NOT NULL AND a.attempted_at<clock_timestamp()-(? * interval '1 day')
 AND NOT i.legal_hold LIMIT 500)
""",
        days);
  }
}
