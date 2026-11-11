CREATE TABLE module_control (
 name varchar(32) PRIMARY KEY CHECK (name IN ('simulator','uitv')),
 enabled boolean NOT NULL DEFAULT false,
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_by varchar(256) NOT NULL DEFAULT 'system'
);
INSERT INTO module_control(name) VALUES ('simulator'),('uitv');
