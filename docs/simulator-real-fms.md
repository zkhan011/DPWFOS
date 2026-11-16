# Live simulator console: TOS → FOS → FMS

The simulator panel supports two saved modes: **Simulated FMS** and **Real FMS**.
It is available only in a local, non-production deployment with development auth,
workers and Kafka enabled. Existing production routes are unchanged.

## Configure a real receiver

1. Set `FOS_SIMULATOR_FMS_HOSTS` to a comma-separated list of approved receiver
   hostnames or IP addresses (no schemes or ports), for example `localhost,10.20.30.40`.
   Restart FOS after changing the allowlist. Default hosts are localhost and 127.0.0.1.
2. If the FMS needs bearer authentication, set `FOS_SIMULATOR_FMS_TOKEN` on the
   server. Tokens are never returned to the browser, stored with jobs or written
   to audit records. Arbitrary authentication headers are not editable in the UI.
3. Enable the simulator module, select **Real FMS**, and enter its complete job
   endpoint, e.g. `http://10.20.30.40:8080/vehicle-job`. Save the settings.
4. Enable the deduplication checkbox only when the real FMS guarantees deduplication
   by message ID. Ambiguous responses otherwise require reconciliation rather
   than blind retry. The response contract remains `{"msgId":"...","code":"0"}`
   for successful completion; HTTP 202 is tracked as remote acceptance.

Only HTTP/HTTPS URLs with allowlisted hosts are accepted; URL credentials, query
strings and fragments are rejected. Redirects are not followed. Both a URL and
its mode are snapshotted when FOS accepts a console job. Subsequent mode/URL
changes never reroute previously queued jobs or retries.

## Receive real login/logout events

The real FMS posts to the existing endpoint:

```text
POST http://FOS_HOST:8080/fos/api/v1/inbound/fms/vehicle-login-logout
Content-Type: application/json
Authorization: Basic <base64 of fms-local:FOS_LOCAL_PASSWORD>
```

Use the configured local password; the literal text `FOS_LOCAL_PASSWORD` is not
sent as a password. Supply a unique msgId and current timestamp with an offset:

```json
{
  "msgId": "FMS-LOGIN-000001",
  "eventTimestamp": "2026-11-16T14:00:00+04:00",
  "vehicleId": "TT-001",
  "action": "IN",
  "userId": "operator123",
  "userName": "Operations User"
}
```

For logout send `action: "OUT"` with a new msgId and eventTimestamp. The sample
above is a schema example; replace its timestamp. Credentials and source mapping
are checked by the existing API. `IN`/`OUT` status is updated only after the event
has been validated and committed in PostgreSQL. It does not wait for delivery to
TOS. The local mock TOS receiver can acknowledge events for real vehicle IDs.

The local profile binds to 127.0.0.1 by default. An FMS on a different machine
cannot call that address. For an isolated integration-test network, bind
`SERVER_ADDRESS` to the host's test-network IP and allow TCP 8080 only from the
partner/test clients; keep management on loopback. Use an HTTPS front end when
credentials cross a network. Do not expose local development authentication to
the public internet.

## Edit and dispatch

- The panel refreshes vehicle status, the latest 100 session events and the latest
  50 console deliveries every second, without refreshing the page or the draft.
- Select a logged-in vehicle, click **New job template**, then edit any contract
  field in the JSON textarea. **Apply vehicle to draft** changes only vehicleId.
- **Format JSON** and **Validate payload** are available before sending. The server
  always validates again on submission, including duplicate JSON keys, message
  identifiers, timestamps and the full nested job contract.
- Sending is disabled in the UI if status is unavailable, settings are unsaved,
  the module is disabled, or the draft vehicle is not logged in for that mode.
  The server independently rejects these invalid dispatches. Real dispatch has
  a confirmation displaying its destination.
- The exact edited payload enters the durable FOS inbox and normal delivery worker;
  the browser does not call the real FMS directly. The receipt and delivery state
  are shown below the editor. Use a new msgId for each new job; retries of an
  identical message reuse its receipt. Reusing an ID with a changed payload or
  destination is rejected.
- A queued console job is checked again immediately before its HTTP attempt. A
  logout or disabled simulator moves it to `WAITING_SESSION`. It resumes only after
  a matching login and simulator enablement, retaining its original destination. An HTTP attempt already in flight
  cannot be recalled; the FMS must still enforce its own dispatch/session rules.

## Simulated sessions and mode switching

Simulated mode provides explicit **Simulate login** and **Simulate logout** buttons
for IDs beginning `SIM-`. Real mode disables these controls and automatic synthetic
round trips. Synthetic sessions cannot authorize a job in real mode, even if
vehicle identifiers collide. The existing automatic scenario runs only in simulated
mode and uses the same login gate and immutable destination snapshot.

All accepted session events and current state persist across restart. Older event
timestamps cannot reverse newer state; at equal timestamps logout wins. Duplicate
messages do not add another event. Status represents the latest accepted login or
logout, not heartbeat connectivity; there is no inferred logout timeout. On upgrade,
only newly accepted events populate the new session tables, so have each FMS send
fresh login events. Flyway migration V3 creates the console, sessions and routing
snapshot tables. Existing pre-upgrade queued messages retain their existing routing.

Real receiver settings and sends are audited, and browser mutations retain CSRF
protection. The editor and tables render untrusted values as text. Static same-origin
JavaScript and same-origin polling are permitted by CSP; inline scripts remain blocked.

## Validation

`mvnw.cmd verify` runs unit and embedded JSP/security tests without Docker. The
optional `-Pdocker-it` suite additionally exercises persisted session ordering,
logout gating and destination snapshots against PostgreSQL. A real partner FMS
still needs its own network/authentication and contract acceptance test.

The focused database regression can also run **without Docker** against a fresh,
disposable native PostgreSQL database. It applies migrations and creates test
messages; never point it at a production or shared FOS database:

```bat
set "FOS_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/fos_simulator_test"
set "FOS_TEST_DB_USER=your_test_user"
set "FOS_TEST_DB_PASSWORD=your_test_password"
mvnw.cmd -Dtest=SimulatorConsoleIT test
```
