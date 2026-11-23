CREATE TABLE endpoint_setting (
 route varchar(64) PRIMARY KEY, url varchar(1000), idempotency_confirmed boolean NOT NULL DEFAULT false,
 revision bigint NOT NULL DEFAULT 0, updated_by varchar(256) NOT NULL DEFAULT 'system',
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO endpoint_setting(route) VALUES ('vehicle-job'),('vehicle-login-logout'),('vehicle-yard-event'),('vehicle-operation-status');
ALTER TABLE audit_event ALTER COLUMN reason TYPE text;
ALTER TABLE delivery_attempt ADD COLUMN idempotency_confirmed boolean;
ALTER TABLE delivery_attempt ADD COLUMN destination varchar(1000);
ALTER TABLE delivery_attempt ADD COLUMN response_body text;
CREATE TABLE kafka_message_log (
 log_id uuid PRIMARY KEY, cluster_id varchar(128) NOT NULL, topic varchar(249) NOT NULL,
 partition_id integer NOT NULL, offset_id bigint NOT NULL, record_key text,
 broker_timestamp bigint NOT NULL, message_type varchar(32) NOT NULL,
 status varchar(32) NOT NULL, failure_code varchar(64), receipt_id uuid REFERENCES message_inbox,
 payload_hash char(64) NOT NULL, raw_bytes bytea, payload_size integer NOT NULL,
 received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(cluster_id,topic,partition_id,offset_id)
);
CREATE INDEX kafka_message_log_recent ON kafka_message_log(received_at DESC,log_id);
CREATE INDEX kafka_message_log_receipt ON kafka_message_log(receipt_id);
CREATE INDEX message_inbox_recent ON message_inbox(received_at DESC,receipt_id);
CREATE INDEX message_inbox_vehicle ON message_inbox(vehicle_id);
