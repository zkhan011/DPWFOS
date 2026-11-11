# JSP administration and Java simulator

## Admin console

`GET /admin` displays module control, process counters, UITV preview, simulator trace (latest 200 events) and delivery metadata in pages of 25. JSP output is escaped using JSTL, including untrusted telemetry values. No JavaScript/CDN dependency is required. The WAR contains Jasper/JSTL and runs using `java -jar`.

The browser uses its own session-based Spring Security filter chain. Form login/logout and every POST have CSRF protection. API routes retain their stateless machine authentication. Admin sessions do not grant API machine credentials. Module mutations and simulator/preview actions require `ROLE_ADMIN`; viewer access does not grant mutation rights. Session inactivity timeout is 20 minutes, HttpOnly and SameSite=Strict cookies. Production TLS remains mandatory.

Local profile: `admin` / `FOS_LOCAL_PASSWORD`. For an approved deployment set `fos.admin.enabled=true`, `FOS_ADMIN_USER`, and `FOS_ADMIN_PASSWORD_HASH` to a DelegatingPasswordEncoder-compatible `{bcrypt}...` value from a secret store. The local plaintext-password fallback cannot run in production. This provides explicit local administrative authentication; enterprise SSO/DPWUM is not implemented.

| Browser operation | Method/path |
|---|---|
| Sign in | GET/POST `/admin/login` |
| Dashboard | GET `/admin?page=0` |
| Module switch | POST `/admin/modules/{simulator,uitv}`; enabled, reason, CSRF |
| Run jobs | POST `/admin/simulator/run`; count 1–10, mode SUCCESS/REJECT, CSRF |
| UITV preview | POST `/admin/uitv/preview`; payload, CSRF |
| Sign out | POST `/admin/logout`; CSRF |

`module_control` stores desired states. Runtime reconciliation occurs every second. Application configuration controls **availability**, an upper limit on what the UI can enable. If a module is unavailable, disabling is still allowed, enabling returns a clear error. An enabled module may show connecting/retry status when dependencies are down. Grant the runtime DB role SELECT/UPDATE on module_control; use the migration role for V2. The state and audit event commit together.

Disabling stops new work after reconciliation; already-confirmed work may finish, and ambiguous broker work may be redelivered. UITV closes its connection to release unacknowledged input. Simulator tasks check the switch before each new send. Switches persist across restarts; a previously enabled module resumes when its deployment configuration permits it. No silent live-route switching occurs.

## Simulator isolation and protocol

The Java plugin uses a loopback HttpServer (default 8090), a bounded job executor, actual authenticated FOS HTTP ingress and Kafka producer. It requires non-production mock authentication, active Kafka consumers and workers, a loopback FOS base URL, and four loopback target URLs using its port. Enabling availability with live target URLs fails startup. Its endpoints reject IDs not prefixed `SIM-`.

A successful scenario generates a unique vehicle/job ID, login and TOS job. The mock FMS returns HTTP 200/code `0`; it emits a yard event and operation statuses 1 through 6 over Kafka. FOS consumes those events normally, persists them and forwards them to mock TOS. A successful run creates nine delivered records. TOS reads the job acknowledgement through FOS receipt status; no additional response contract is assumed.

REJECT mode returns HTTP 200/nonzero code for the job. The job enters BUSINESS_REJECTED and produces no execution events. The login is independent and can succeed.

Simulator receivers deduplicate up to 1,000 synthetic messages per process, then return 429 rather than silently evict identities. Restart only when tests are drained to start a fresh demo. The trace/counters are process-local; FOS inbox and deliveries are durable. This is a single-host development tool, not a durable counterpart simulator or a production equipment controller. A failed/disrupted simulation is shown in history; rerun a new scenario after fixing dependencies.

## Verification

AdminPagesTest starts real embedded Tomcat and renders the JSPs, checks login, CSRF rejection, authorized toggle, preview escaping and no switch mutation during preview. UITV golden fixtures are generated from the transformation functions in the supplied Python source, excluding all credentials and connection code. SimulatorRoundTripIT uses real PostgreSQL and Kafka and verifies nine completed deliveries before checking disable behavior.
