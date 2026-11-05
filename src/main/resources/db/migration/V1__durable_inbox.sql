CREATE TABLE ordering_stream (
 stream_key varchar(512) PRIMARY KEY, next_sequence bigint NOT NULL DEFAULT 0 CHECK(next_sequence>=0)
);
CREATE TABLE message_inbox (
 receipt_id uuid PRIMARY KEY, source varchar(16) NOT NULL, message_type varchar(32) NOT NULL,
 msg_id varchar(128) NOT NULL, vehicle_id varchar(128) NOT NULL,
 source_timestamp timestamptz NOT NULL, source_timestamp_text varchar(128) NOT NULL,
 received_at timestamptz NOT NULL DEFAULT clock_timestamp(), payload_hash char(64) NOT NULL,
 payload jsonb, raw_payload text, transport_metadata jsonb NOT NULL, contract_version varchar(32) NOT NULL,
 legal_hold boolean NOT NULL DEFAULT false,
 UNIQUE(source,message_type,msg_id)
);
CREATE TABLE delivery (
 delivery_id uuid PRIMARY KEY, receipt_id uuid NOT NULL REFERENCES message_inbox,
 target varchar(16) NOT NULL, state varchar(32) NOT NULL CHECK(state IN
 ('PENDING','IN_FLIGHT','DELIVERED','RETRY_WAIT','BUSINESS_REJECTED','UNKNOWN','QUARANTINED','REMOTE_ACCEPTED','EXPIRED','EXHAUSTED')),
 attempt_count integer NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 stream_key varchar(512) NOT NULL REFERENCES ordering_stream, sequence bigint NOT NULL,
 lease_owner uuid, lease_until timestamptz, lease_generation bigint NOT NULL DEFAULT 0,
 last_outcome varchar(64), completed_at timestamptz,
 UNIQUE(receipt_id,target), UNIQUE(stream_key,sequence)
);
CREATE INDEX delivery_due ON delivery(target,next_attempt_at) WHERE state IN ('PENDING','RETRY_WAIT');
CREATE INDEX delivery_stream ON delivery(stream_key,sequence) WHERE state <> 'DELIVERED';
CREATE TABLE delivery_attempt (
 attempt_id uuid PRIMARY KEY, delivery_id uuid NOT NULL REFERENCES delivery,
 attempt_number integer NOT NULL, attempted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 http_code integer, business_code varchar(128), duration_ms bigint NOT NULL, outcome varchar(64) NOT NULL,
 UNIQUE(delivery_id,attempt_number)
);
CREATE TABLE quarantine (
 quarantine_id uuid PRIMARY KEY, topic varchar(249) NOT NULL, partition_id integer NOT NULL, offset_id bigint NOT NULL,
 payload_hash char(64) NOT NULL, raw_bytes bytea, failure_code varchar(64) NOT NULL,
 received_at timestamptz NOT NULL DEFAULT clock_timestamp(), review_status varchar(32) NOT NULL DEFAULT 'OPEN',
 UNIQUE(topic,partition_id,offset_id)
);
CREATE TABLE audit_event (
 audit_id uuid PRIMARY KEY, actor varchar(256) NOT NULL, action varchar(64) NOT NULL,
 receipt_id uuid, reason varchar(1024), created_at timestamptz NOT NULL DEFAULT clock_timestamp(), result varchar(64) NOT NULL
);
CREATE TABLE replay_request (
 actor varchar(256) NOT NULL, request_token varchar(128) NOT NULL, receipt_id uuid NOT NULL REFERENCES message_inbox,
 reason varchar(1024) NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(actor,request_token)
);
