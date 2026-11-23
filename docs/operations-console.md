# Endpoint settings and message diagnostics

Open `/admin` and use **Endpoint settings**, **Message history**, or **Kafka logs** in the navigation. These pages use the existing admin sign-in. Changes require ADMIN and a CSRF token; detail views and endpoint changes are audited. Navigation also works on mobile screens.

## Configure TOS and FMS receivers

| Route | Sender → receiver | Setting |
| --- | --- | --- |
| vehicle-job | FOS → FMS | Full FMS job receiver URL |
| vehicle-login-logout | FOS → TOS | Full TOS login/logout receiver URL |
| vehicle-yard-event | FOS → TOS | Full TOS yard-event receiver URL |
| vehicle-operation-status | FOS → TOS | Full TOS operation-status receiver URL |

1. Ensure the receiving host is approved in `fos.allowed-hosts`. The local profile supports `FOS_ALLOWED_HOSTS` as a comma-separated list. HTTPS is required outside the local profile. URLs may not contain credentials, query strings or fragments; redirects are not followed.
2. Open **Endpoint settings** and enter the full URL, including port and path.
3. Confirm message-ID deduplication only when agreed with the receiving team. Production requires this confirmation.
4. Enter a reason and save. The setting survives restarts. Conflicting edits from another administrator are rejected with a refresh instruction.
5. **Use deployment default** clears the saved override. Existing `fos.targets` values are the fallback; startup production gates still require approved deployment routes.

Changes apply to deliveries with no recorded destination yet. Retries retain the first attempted URL and its deduplication policy, avoiding accidental redelivery to a different system after an endpoint edit. In-flight requests are not redirected. The current host allowlist is rechecked when using saved routes or retry destinations; a removed host causes quarantine. Older attempts made before this upgrade do not have a destination snapshot and use the effective configured route.

Bearer credentials remain in `fos.targets.<route>.bearer-token` (external secret configuration). The UI only shows whether a credential is configured. A route keeps its configured credential when its URL changes: use only an approved receiver authorized for that credential. Saving does not send a probe or a business message. Validate delivery using approved test traffic and the message history.

Manual simulator dispatch retains its separate saved destination. In local deployments where the built-in simulator is available, `SIM-` message IDs are reserved for synthetic traffic and stay on the deployment's isolated simulator receivers unless a manual dispatch has an explicit destination snapshot. Do not use that prefix for live messages in this mode. Ordinary TOS/FMS API traffic uses the new route settings.

## Inspect message exchanges

**Message history** searches exact message IDs, vehicle IDs or receipt UUIDs, with receiver and delivery-state filters. Open a message to see:

- Original accepted JSON (the same body forwarded by FOS), source timestamp and receipt time.
- Source → FOS → receiver, delivery state and outcome, payload SHA-256, and ingress transport metadata.
- Every persisted attempt, the actual destination URL, HTTP status, receiver business code, duration and response body.
- A link to related Kafka records.

Response bodies are bounded to 64 KiB. Timeouts, incomplete/oversized responses and attempts before this upgrade may have no body. Requests contain no captured Authorization headers. Known password/token/API-key fields are masked in JSON previews, and all displayed content is HTML-escaped. Non-JSON bodies are displayed as text. These diagnostics contain operational payloads and should only be available to authorized support users.

The history contains accepted inbox messages; unauthenticated or contract-invalid HTTP requests do not create inbox entries. Kafka validation failures have their own log entries. A message may be accepted by FOS before delivery finishes: `ACCEPTED` in Kafka logs does not mean delivered to TOS. `UNKNOWN` and `REMOTE_ACCEPTED` still require counterpart reconciliation; this UI does not blindly replay them.

## Kafka message log

Every record processed by either FOS Kafka listener is durably logged before its offset is acknowledged, including quarantined malformed messages. Logs include cluster ID, topic, partition, offset, Kafka key, broker timestamp, received timestamp, message type, acceptance/quarantine status, validation failure, payload hash/size and retained bytes. Accepted records link to their FOS receipt and delivery responses.

The unique cluster/topic/partition/offset identity prevents duplicate entries on redelivery. If the log insert fails, FOS does not acknowledge the record. Acceptance may already have committed; replay is safe through the existing inbox deduplication. This log covers consumed records, not messages still waiting in Kafka or producer sends that failed before arriving at FOS.

Set `FOS_KAFKA_CLUSTER_ID` (default `primary`) to a stable name for the connected cluster. Change it when switching to a different cluster or recreating topics with reset offsets, to avoid identity collisions. Topic and offset metadata is captured per record. Kafka headers are not captured. Keys are capped at 1,024 characters; payloads exceeding `fos.max-bytes` retain metadata/hash but not bytes. The UI shows UTF-8 text, with replacement characters for invalid bytes.

## Retention and deployment

- Flyway migration **V4__operations_console.sql** adds endpoint settings, Kafka logs and delivery-response columns. Run the normal migration procedure before starting this application version. Existing V1–V3 data is preserved. Grant the runtime database account SELECT/INSERT on `kafka_message_log`, SELECT/UPDATE on `endpoint_setting`, and UPDATE on `kafka_message_log` for retention, alongside its existing permissions.
- `FOS_LOG_PAYLOAD_RETENTION_DAYS` / `fos.operations-log.payload-retention-days` defaults to **7** (allowed range 1–3650).
- Hourly cleanup removes Kafka payload bytes and HTTP response bodies older than that age, in batches of 500 per table. Related inbox legal holds prevent removal. Metadata remains searchable; operators must include these tables in capacity planning and backups. At large volumes, shorten `fos.operations-log.cleanup-ms` or arrange archival.
- This cleanup runs independently of `fos.retention.enabled`, which continues to control the existing accepted-inbox payload policy. Existing quarantine payload retention is unchanged.
- Kafka logs and response capture begin after this version is deployed; historical messages are not reconstructed.
