# Development and deployment runbook

## Start and observe

Follow README for loopback mocks. `./mvnw verify` needs Docker for PostgreSQL and Kafka tests. `docker compose down` stops dependencies without deleting the named database volume. Do not use `down -v` as a recovery procedure.

Local health: `curl -u "support-local:$FOS_LOCAL_PASSWORD" http://localhost:8081/actuator/health/readiness`. Readiness checks durable DB capability; a temporary downstream outage does not make ingress unready. Liveness checks local application state. Health details are hidden. Configure authenticated probes and internal-only ingress in production.

## Production preparation (not performed)

1. Resolve/sign the contract register and security review. Reconcile the newer workbook/plan if it is intended to supersede this attachment.
2. Freeze dependency/container versions and digests after vulnerability/license scans. CI creates CycloneDX BOM; a BOM is not a scan pass.
3. Provision HA PostgreSQL and Kafka separately; enforce topic ACLs, TLS, monitoring and dedicated environment consumer groups. Proposed production topics: six partitions, replication three, min ISR two, retention 72h. Local Compose is not an HA template.
4. Back up database, run Flyway once using migration role, then run app with least-privilege runtime role and migrations disabled. Migration V1 is additive; no destructive rollback migration is supplied.
5. Mount approved secrets/certificates and external YAML. Supply subject mappings, audience/issuer, four allowlisted target URLs and tokens. Set idempotency-confirmed on each approved receiver. Enable workers/Kafka only for the approved environment. Confirm contract flag after signed decisions.
6. Configure API TLS 8443, management TLS 8444, LB 443; do not expose management publicly. Deploy immutable artifact as non-root. Restrict receiver/broker/DB egress. All Kafka advertised listeners must be reachable.
7. Run SIT, failure/restart, HA and backup/restore tests. Measure load and sizing before release approval. Do not connect this development baseline to live equipment merely by changing URLs.

## Pause, reconcile, recover

Disable `fos.workers.enabled` and restart gracefully to stop new claims; disable `fos.kafka.enabled` to stop event intake. Pause upstream senders when durable storage is unavailable. HTTP store failure returns 503; Kafka store failure leaves offsets uncommitted. Never clear the inbox or rewind offsets as generic recovery.

Inspect paginated operational metadata and approved DB diagnostics. UNKNOWN can mean the receiver completed a request after a local timeout/lease expiry. REMOTE_ACCEPTED means remote completion is unconfirmed. Compare original msgId and receiver result with the counterpart. No automatic resolution API exists yet; establish an approved reconciliation workflow before production.

Restricted replay endpoint, only for non-operation BUSINESS_REJECTED/EXHAUSTED:

```bash
curl -H "Authorization: Bearer $SUPPORT_TOKEN" -H 'Content-Type: application/json'  --data '{"requestToken":"UNIQUE_REQUEST_TOKEN","reason":"Documented counterpart correction and approved replay"}'  'https://APPROVED_FOS_HOST/fos/api/v1/operations/deliveries/RECEIPT_UUID/replay'
```

Replay preserves original ID/payload. Same actor/token/receipt/reason is idempotent; changed values conflict. Delivered, pending, ambiguous, expired, remote-accepted and operation-status records cannot be replayed through this endpoint. Correcting payload requires the source's agreed new-message/version protocol, never database mutation to conceal a conflict.

## Storage, backup and rollback

Alert on DB disk/WAL growth, pending age, retry/unknown/quarantine counts, consumer lag, receiver latency/errors, expired leases and database saturation. Implement infrastructure dashboards/alerts before release; only base application counters and framework metrics are provided now. Raw volume assumption is about 38 GB/day at 220 messages/s × 2 KB, before indexes/WAL.

Payload purge is opt-in, 500 records per run, successful records older than seven days only; legal-hold and unresolved records are retained. Tombstones/audit are retained indefinitely until a signed purge policy exists. Monitor and provision for this limit.

Use approved PostgreSQL physical backup/PITR tooling; restore into an isolated environment with outbound adapters disabled. Reconcile accepted msgIds with counterpart outcomes before enabling delivery. No RPO/RTO is claimed until measured. Roll back to a schema-compatible binary; keep migrations forward-compatible. Protect backup copies as payload-bearing data. Renew mounted certificates/secrets under change control and restart/drain safely.
