# Local testing on Windows without Docker

Use JDK 21, PostgreSQL and Kafka installed locally. No JWT issuer, TLS certificate,
RabbitMQ or externally generated admin hash is required for the local profile.
Production defaults and approval/TLS checks remain enabled outside that profile.

## PostgreSQL

Create a database and login in pgAdmin or psql, using your own password:

```sql
CREATE USER fos WITH PASSWORD 'replace-with-your-local-password';
CREATE DATABASE fos OWNER fos;
```

An existing database/login is also supported. Set FOS_DB_USER and FOS_DB_PASSWORD
to that login; do not recreate it. Flyway uses these credentials unless separate
FOS_MIGRATION_USER/FOS_MIGRATION_PASSWORD values are supplied. Migrations are
enabled in the local profile. FOS_LOCAL_DB_PASSWORD remains a legacy fallback.
The bundled Flyway may warn about PostgreSQL 18; that compatibility warning is
not the admin/JWT startup failure. PostgreSQL 16.6 is the repository’s container test version.

## Kafka (KRaft, Kafka 4.x)

Extract the binary distribution to C:\kafka (short path, no spaces). In
config/server.properties set `log.dirs=C:/kafka/data`. For a NEW installation only:

```bat
cd /d C:\kafka
bin\windows\kafka-storage.bat random-uuid
bin\windows\kafka-storage.bat format --standalone -t PASTE_GENERATED_ID -c config\server.properties
```

Do not reformat initialized storage or delete it to resolve startup errors.
Start Kafka in CMD; the heap setting bypasses older scripts' WMIC detection:

```bat
set "KAFKA_HEAP_OPTS=-Xms512M -Xmx1G"
if exist config\log4j2.yaml set "KAFKA_LOG4J_OPTS=-Dlog4j2.configurationFile=C:/kafka/config/log4j2.yaml"
bin\windows\kafka-server-start.bat config\server.properties
```

Keep this window open. In another CMD, from C:\kafka:

```bat
bin\windows\kafka-topics.bat --create --if-not-exists --bootstrap-server localhost:9092 --topic fos.fms.vehicle-yard-event.v1 --partitions 1 --replication-factor 1
bin\windows\kafka-topics.bat --create --if-not-exists --bootstrap-server localhost:9092 --topic fos.fms.vehicle-operation-status.v1 --partitions 1 --replication-factor 1
```

Use Ctrl+C in the broker window to stop; no WMIC stop script is needed.

## Eclipse environment

Configure the **Java Application / FosApplication** launch, not only Maven Build.
Enter each name and value separately, without quotes or `NAME=` in the value.

| Name | Value |
|---|---|
| SPRING_PROFILES_ACTIVE | local |
| FOS_DB_URL | jdbc:postgresql://localhost:5432/fos |
| FOS_DB_USER | fos (or your existing login) |
| FOS_DB_PASSWORD | your database password |
| FOS_LOCAL_PASSWORD | your chosen password of at least 16 characters |
| FOS_KAFKA_BOOTSTRAP | localhost:9092 |

The URL must start with `jdbc:`. Remove stale SPRING_DATASOURCE_URL or program/VM
arguments overriding database settings. Check the startup log reports profile `local`.
Do not change production defaults in application.yml to make local development work.

Admin login uses username `admin` (FOS_ADMIN_USER can override it) and the same
FOS_LOCAL_PASSWORD as local API users. The local profile deliberately does not
inherit FOS_ADMIN_PASSWORD_HASH from production configuration. To use a separate
local admin hash, set FOS_LOCAL_ADMIN_PASSWORD_HASH. Both raw BCrypt and
`{bcrypt}`-prefixed hashes are supported; plaintext or quoted hashes are rejected.
Remove any direct `fos.admin.password-hash` launch argument when using the fallback.
Never commit actual passwords to YAML.

Build with `mvnw.cmd clean verify` (or Maven goals `clean verify` in Eclipse), then
run FosApplication. From CMD, `run-local.cmd` activates the local profile and checks
that database/local passwords are provided before launching. Docker integration tests are opt-in with `-Pdocker-it`; they
are skipped, not counted as passing, during ordinary local builds. CI explicitly
runs that profile. Unit and embedded JSP/security tests still run by default.

## Test and simulator

Open http://127.0.0.1:8080/admin/login. API port is HTTP 8080; health is HTTP 8081.
In CMD, replace the example password with your chosen FOS_LOCAL_PASSWORD:

```bat
curl.exe -u "support-local:YOUR_LOCAL_PASSWORD" http://127.0.0.1:8081/actuator/health
```

API users: tos-local (jobs), fms-local (sessions), support-local (operations,
replay, health and metrics). All use FOS_LOCAL_PASSWORD. Admin is a separate form
login with CSRF protection. No JWT issuer is needed with local development auth.

Enable the TOS/FMS simulator in the admin page before sending simulated messages.
The mock receivers listen on 8090 after enablement. Workers and Kafka ingestion
are enabled locally. UITV RabbitMQ availability defaults off; configure RabbitMQ
and set fos.uitv.available=true before enabling that bridge. Transformation preview
remains available without a broker.
