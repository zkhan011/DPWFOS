# Implementation handoff

## Implemented

- One Java 21 / Maven / Spring Boot executable JAR with separated packages.
- Four strict development contracts, canonical hashes, raw payload/source timestamp preservation, schema/examples and OpenAPI/AsyncAPI.
- REST job/session acceptance, source-owned receipts, paginated metadata and restricted audited replay.
- Flyway/PostgreSQL inbox/delivery/attempt/quarantine/audit/replay/ordering tables; atomic acceptance and uniqueness handling.
- Synchronous durable Kafka record acceptance/quarantine with manual offsets and non-skipping DB retry.
- Row-lock head-of-line claims, generation fencing, bounded per-receiver pools, simple circuit cooldown, outcome classification and receiver-idempotency-aware retries.
- Operation-status freshness hold, unknown and remote-accepted hold, conservative replay eligibility, payload-only purge preserving tombstones.
- JWT issuer/audience/scopes/source mapping, local-only mock identities, trusted receiver allowlist, fail-closed production configuration.
- Local PostgreSQL/Kafka Compose, loopback mock receiver, Dockerfile, CI tests/JAR/SBOM, runbook and decision/security registers.

## Source layout

`src/main/java/com/dpw/fos`: API, application, contract, domain, persistence, HTTP/Kafka adapters, security and config. `src/main/resources`: Flyway and profile YAML. `src/test`: contract, receiver, security, Kafka failure tests, plus real PostgreSQL/Kafka integration tests. `contracts`: published API/topic/schema contracts and synthetic fixtures. `scripts`: mock receiver. `.github/workflows`: verification and artifacts.

## Validation evidence

Local validation on 5 November 2026 using Java 21.0.13 and Maven 3.9.9:

- `mvn test`: **22 tests passed**, zero failures/errors/skips.
- Five synthetic examples pass their published Draft 2020-12 JSON Schemas; all YAML parses.
- `mvn verify`: unit tests passed and executable JAR packaged; PostgreSQL/Kafka integration tests **failed to initialize because this workspace has no Docker environment**. This is not an integration pass.
- The integration test sources compile. They must run successfully on a Docker-capable runner before merge.
- GitHub publication was authorized on 5 November 2026. Check the PR for remote integration-test results; local Docker tests remain unverified.
- CycloneDX JSON/XML SBOM generated successfully with `cyclonedx-maven-plugin:2.9.1:makeAggregateBom`; this is dependency inventory, not vulnerability scan evidence.
- No load, HA/DR, partner SIT, vulnerability scan or production clearance evidence exists.

No integration test is annotated to silently skip without Docker. CI is configured to produce an SBOM and test evidence once publication is authorized.

## Outstanding before production

- D01–D11 approval, partner schemas/response table and the intended newer-workbook/JSP scope.
- Counterpart confirmation/reconciliation protocol and API for UNKNOWN/REMOTE_ACCEPTED/held stream resolution.
- Human identity/DPWUM integration, viewer/admin/auditor experience and configuration change workflow.
- Token acquisition/rotation, cluster-wide admission/rate limits, enhanced capacity gauges, dashboards/alerts and retry-expiry policy for jobs/sessions.
- Signed finite tombstone/audit/metadata purge policy; current conservative indefinite retention requires storage monitoring.
- Full crash-injection suite (process termination between DB commit/offset or HTTP response), multi-node chaos/rebalance, receiver side-effect deduplication under delayed responses, JWT signature/issuer/audience negative integration matrix, and network fault tests.
- Sustained 440 msg/s for 60 minutes, 660 msg/s for 15 minutes, 256 KiB extremes, acceptance/delivery p95 evidence; no capacity/SLA claim is made.
- Dependency/container security scan evidence, penetration review, immutable digest pinning and security clearance.
- Demonstrated database backup/restore, failover, RPO/RTO, rollout/rollback and support ownership.

These are real gaps; the repository is a reviewable development implementation, not completion of partner SIT/UAT or the eight-week multi-team delivery program. The attachment's 68 person-day estimate remains unchanged: 28 FOS implementation, 22 shared, 18 counterpart work. No live deployment or receiver contact was performed.
