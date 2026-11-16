CREATE TABLE simulator_console (
 id integer PRIMARY KEY CHECK(id=1), mode varchar(16) NOT NULL DEFAULT 'SIMULATED' CHECK(mode IN ('SIMULATED','REAL')),
 fms_url text NOT NULL DEFAULT '', idempotency_confirmed boolean NOT NULL DEFAULT false,
 revision bigint NOT NULL DEFAULT 0, updated_by varchar(256) NOT NULL DEFAULT 'system',
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO simulator_console(id) VALUES(1);
CREATE TABLE vehicle_session (
 origin varchar(16) NOT NULL CHECK(origin IN ('REAL','SIMULATED')), vehicle_id varchar(128) NOT NULL,
 action varchar(3) NOT NULL CHECK(action IN ('IN','OUT')), user_id varchar(128) NOT NULL,
 user_name varchar(256) NOT NULL, event_time timestamptz NOT NULL,
 received_at timestamptz NOT NULL DEFAULT clock_timestamp(), receipt_id uuid NOT NULL REFERENCES message_inbox,
 PRIMARY KEY(origin,vehicle_id)
);
CREATE TABLE vehicle_session_event (
 receipt_id uuid PRIMARY KEY REFERENCES message_inbox, origin varchar(16) NOT NULL,
 vehicle_id varchar(128) NOT NULL, action varchar(3) NOT NULL,
 user_id varchar(128) NOT NULL, user_name varchar(256) NOT NULL,
 event_time timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX vehicle_session_event_recent ON vehicle_session_event(received_at DESC);
CREATE TABLE simulator_dispatch (
 receipt_id uuid PRIMARY KEY REFERENCES message_inbox, mode varchar(16) NOT NULL,
 destination text NOT NULL, idempotency_confirmed boolean NOT NULL,
 actor varchar(256) NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
ALTER TABLE delivery DROP CONSTRAINT delivery_state_check;
ALTER TABLE delivery ADD CONSTRAINT delivery_state_check CHECK(state IN
 ('PENDING','IN_FLIGHT','DELIVERED','RETRY_WAIT','BUSINESS_REJECTED','UNKNOWN','QUARANTINED','REMOTE_ACCEPTED','EXPIRED','EXHAUSTED','WAITING_SESSION'));
CREATE INDEX delivery_waiting_session ON delivery(target,next_attempt_at) WHERE state='WAITING_SESSION';
