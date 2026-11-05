# DPWFOS

Java 21 / Maven / Spring Boot 3.5.16 middleware for the attached **development-v1** FOS baseline. All four implemented message flows pass through FOS. This is a development implementation, not an approved production release.

| Input | Transport into FOS | Receiver |
|---|---|---|
| VehicleJob | REST from TOS | FMS REST |
| VehicleLogin-Logout | REST from FMS | TOS REST |
| VehicleYardEvent | Kafka from FMS | TOS REST |
| VehicleOperationStatus | Kafka from FMS | TOS REST (unconfirmed contract gate) |

No live receivers are configured. Production defaults fail closed. This attachment is version 0.1 dated 4 November; it does not include a JSP frontend or additional message families. See [decisions](docs/decisions.md) before extending its scope.

## Local mock integration

Requires JDK 21, Docker Compose, Python 3, and Maven Central access on first wrapper run.

```bash
export FOS_LOCAL_DB_PASSWORD='replace-with-a-local-database-password'
export FOS_LOCAL_PASSWORD='replace-with-a-local-test-password'
docker compose up -d postgres kafka
docker compose run --rm topics
python3 scripts/mock_receiver.py
# In another terminal with the same local environment variables:
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Local credentials: `tos-local`, `fms-local`, `support-local`; password comes from `FOS_LOCAL_PASSWORD` (minimum 16 characters). Mock authentication is accepted only with the `local` profile and `fos.production=false`. Loopback-only HTTP is for development. The PostgreSQL volume survives restarts.

```bash
curl -i -u "tos-local:$FOS_LOCAL_PASSWORD"   -H 'Content-Type: application/json'   --data-binary @contracts/examples/vehicle-job.json   http://localhost:8080/fos/api/v1/inbound/tos/vehicle-jobs

curl -u "tos-local:$FOS_LOCAL_PASSWORD"   'http://localhost:8080/fos/api/v1/messages/RECEIPT_UUID'

curl -u "support-local:$FOS_LOCAL_PASSWORD"   'http://localhost:8080/fos/api/v1/operations/deliveries?page=0&size=25'
```

Fresh operation events are required for forwarding. Historical fixture timestamps are deliberately preserved and will be held as `EXPIRED`; update a synthetic fixture timestamp explicitly when demonstrating a fresh event.

## Verify and package

```bash
./mvnw verify            # unit + real PostgreSQL/Kafka Testcontainers; requires Docker
./mvnw test              # unit tests only; not full integration verification
java -jar target/fos-integration-0.1.0.jar --spring.profiles.active=local
```

CI runs verification and uploads test reports, executable JAR, and CycloneDX SBOM. Container tests do not silently skip when Docker is absent. Dependency/security scanning and production performance evidence remain release gates.

## Delivery behavior

Acceptance commits inbox and delivery rows before returning `202` or acknowledging Kafka. Unique `(source,message_type,msgId)` identities reject different payloads with `409`. Key-sorted JSON hashes ignore object-key order and formatting; preserve arrays, nulls, string contents, and missing-vs-null distinctions. Accepted integer fields must use integral JSON tokens; `2.0` and `"2"` are rejected. Source timestamp text and original JSON text are forwarded unchanged.

Workers claim with PostgreSQL row locks and generation-fenced leases. Earlier unresolved messages block each source/type/vehicle stream. Downstream matching HTTP 200/code `"0"` completes delivery. HTTP 202 waits for agreed confirmation. Ambiguous outcomes and expired leases require reconciliation. Receiver idempotency is necessary; no exactly-once external effect guarantee is claimed.

## Documentation

- [OpenAPI](contracts/openapi.json), [AsyncAPI](contracts/asyncapi.json), [JSON Schemas](contracts/schemas), [fixtures](contracts/examples)
- [Architecture](docs/architecture.md), [contract decisions](docs/decisions.md)
- [Runbook](docs/runbook.md), [security](docs/security.md), [handoff and limitations](docs/handoff.md)
