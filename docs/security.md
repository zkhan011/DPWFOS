# Security implementation and clearance gates

Production uses Spring Security OAuth2 resource server: issuer discovery, signature/expiry validation, required audience and route-specific scopes. Map authenticated JWT subjects to FMS/TOS in `fos.principals`; body userId/userName and source headers do not grant rights. Machine source ownership controls receipt reads. Support access is scoped and audited. Replay requires `fos.replay`, a reason and an actor-scoped idempotency token.

| Scope | Capability |
|---|---|
| fos.jobs.write | TOS job ingress, also requires subject→TOS mapping |
| fos.sessions.write | FMS login/logout ingress, also requires subject→FMS mapping |
| fos.support.read | Masked metadata and receipt reads |
| fos.replay | Restricted replay requests |
| fos.health | Health probes on internal management port |
| fos.metrics | Prometheus on internal management port |

Only bearer tokens are accepted outside the explicit local mock profile. APIs are stateless and do not issue cookies; CSRF is disabled for this token API. No human browser session/JSP authentication has been implemented. If a UI is added, define its separate session/CSRF protection and approved identity integration. CORS is not broadly enabled. All unmatched paths are denied.

Receiver URLs come only from trusted configuration, require an explicit host allowlist and HTTPS except in local development, prohibit user-info/query/fragment and never follow redirects. Egress network controls must also constrain DNS/IP destinations. TLS/hostname verification is never disabled. Static bearer tokens can be supplied through mounted secret configuration; automated OAuth client-credentials acquisition and rotation are not implemented.

Production API/backend 8443, management 8444, external LB 443. Mount a keystore using `server.ssl.key-store`, `server.ssl.key-store-password`, `server.ssl.enabled=true` and equivalent `management.server.ssl.*`. Set PostgreSQL `sslmode=verify-full` with the trusted root CA, Kafka SSL/SASL_SSL plus approved truststore/client authentication. Configure JWT issuer via `spring.security.oauth2.resourceserver.jwt.issuer-uri`. Never commit these values.

Flyway is disabled by default. Run migrations once with separate migration credentials; local development alone uses a shared role. Production runtime grants: SELECT/INSERT/UPDATE on inbox, delivery, ordering_stream and quarantine; SELECT/INSERT on attempts, replay_request and audit_event. Do not grant runtime UPDATE/DELETE on audit_event. Audit export, administrator policy changes, dedicated auditor UI/API, penetration testing, secret rotation, scan acceptance and DPW security clearance are outstanding.

Logs contain receipt IDs, target, attempt and categorized outcome; raw business payloads, tokens and operator names are excluded. Database/audit encryption, access, backups and log retention are platform responsibilities. No claim of production security approval is made.
