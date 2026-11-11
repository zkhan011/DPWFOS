# DPWFOS

Java 21 / Maven / Spring Boot FOS integration with a **JSP admin console**, **Java TOS/FMS simulator**, and **Java UITV RabbitMQ telemetry bridge**. Version 0.2 packages as an executable **WAR** to support JSP with embedded Tomcat. No external Tomcat or Python runtime is needed.

## Start the local workspace

Requires JDK 21 and local PostgreSQL/Kafka (Docker Compose setup below). Use only synthetic data with the local profile.

```bash
export FOS_LOCAL_DB_PASSWORD='choose-a-local-database-password'
export FOS_LOCAL_PASSWORD='choose-a-local-password-at-least-16-characters'
docker compose up -d postgres kafka
docker compose run --rm topics
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Open **http://localhost:8080/admin**. Local username: **admin**. Password: `FOS_LOCAL_PASSWORD`.

- Enable **TOS / FMS simulator**, enter a reason, refresh, and choose **Run simulation**.
- Inspect message flow history and durable delivery states in the same console.
- Paste source telemetry into **UITV transformation preview**; preview works while the bridge is disabled and never publishes.
- Enable/disable UITV independently after configuring RabbitMQ. See [UITV setup](docs/uitv.md).

Both switches default **off** on first migration and persist in PostgreSQL. Requested state and actual runtime state are shown separately. Changes are audited. Browser mutations require authenticated admin access and CSRF tokens.

## What the simulator exercises

`TOS REST job → FOS durable inbox → mock FMS HTTP acknowledgement → FMS Kafka yard/operation events → FOS durable inbox → mock TOS HTTP acknowledgement`.

Each successful scenario sends one login, one job, one yard event and operation statuses 1–6. Rejection mode returns a nonzero FMS business code and emits no job execution events. The simulator is a Java module, available only in an isolated local configuration with loopback receiver targets. It does not alter live routes. Stop the old Python mock if it was running on port 8090.

The existing contract returns a receipt to TOS and tracks the FMS acknowledgement through the status API. There is no invented production JobResponse callback. FMS execution events return to TOS through the existing contracts.

## UITV bridge

This is a Java port of the supplied Python transformation: fuel/battery aliases and CAN fallback, inclusive 20% thresholds, location mapping, availability flags, current machine timestamp with offset, and array output. It uses RabbitMQ mandatory persistent publishing with confirms; input is acknowledged only after routed output is confirmed. Failed input is requeued with backoff.

Broker address, username, password and TLS mode are external configuration. No credentials from the pasted script are included. See [configuration and behavior](docs/uitv.md).

## Build and test

```bash
./mvnw test       # Unit tests, Python-parity fixtures, actual embedded-Tomcat JSP/security tests
./mvnw verify     # Also real PostgreSQL, Kafka, RabbitMQ and simulator round-trip tests; Docker required
java -jar target/fos-integration-0.2.0.war --spring.profiles.active=local
```

CI uploads the WAR, test reports and CycloneDX SBOM. Production contract/security approval, partner SIT, load and HA/DR remain separate release gates. Production admin UI defaults off and requires an externally supplied `{bcrypt}` password hash; simulator availability is forbidden in production.

## Contracts and operations

| Message | Input to FOS | Receiver |
|---|---|---|
| VehicleJob | TOS REST | FMS REST |
| VehicleLogin-Logout | FMS REST | TOS REST |
| VehicleYardEvent | FMS Kafka | TOS REST |
| VehicleOperationStatus | FMS Kafka | TOS REST (contract approval pending) |
| UITV telemetry | RabbitMQ queue | RabbitMQ business exchange |

- [Admin and simulator guide](docs/admin-simulator.md)
- [UITV Java bridge](docs/uitv.md)
- [OpenAPI](contracts/openapi.json), [AsyncAPI](contracts/asyncapi.json), [schemas](contracts/schemas)
- [Architecture](docs/architecture.md), [decisions](docs/decisions.md), [runbook](docs/runbook.md), [security](docs/security.md), [handoff](docs/handoff.md)
