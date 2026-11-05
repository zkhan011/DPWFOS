# Proposed pull request

**Title:** Add durable FOS development integration for FMS and TOS

Implements the attached four-message development baseline in the previously empty repository. FOS validates and durably accepts REST jobs/sessions and Kafka yard/operation events, then dispatches them with per-stream ordering, idempotency checks, bounded workers and explicit ambiguous outcomes. A downstream 202 remains pending confirmation; only matching HTTP 200/code 0 completes delivery.

Includes Flyway migrations, scoped JWT security, restricted audited replay, conservative payload retention, exact contract examples/schemas, API/topic definitions, local mocks, Docker packaging and CI. Live routes and production clearance remain disabled/unconfirmed.

Validation: 22 local unit/controller tests pass; five JSON fixtures pass published schemas; executable JAR packages. Full `verify` is blocked because Docker is unavailable locally; PostgreSQL/Kafka Testcontainers must pass in CI. Performance, HA/DR, scan and partner acceptance remain open in `docs/handoff.md`.

This draft follows the uploaded 4 November v0.1 attachment, not the newer workbook/JSP plan referenced in prior work. Review that scope before expanding the implementation.

Publication authorized on 5 November 2026. Prepared for a draft PR on `codex/fos-durable-integration`; main retains the initial README until review and merge.
