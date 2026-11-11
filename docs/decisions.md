# Contract decision register

All entries are **UNCONFIRMED**. Owners below describe required roles, not named approvals. The supplied attachment is version 0.1 of 4 November 2026. The original workbook/design and later revised plan were not included in this repository input; their inconsistencies are reproduced from the attachment, not independently resolved. Do not treat these development defaults as partner agreement.

| ID | Required owner | Development value | Impact before live use |
|---|---|---|---|
| D01 | FMS/TOS interface owners | `jobDetails` nonempty array | Resolve singular workbook sample and cancellation/finish shapes |
| D02 | FMS/TOS interface owners | Active position required; inactive omitted or null | Freeze omitted/null equivalence; hashes currently distinguish them |
| D03 | FMS/TOS owners | Login/logout FMS → FOS → TOS | Confirm inverted workbook direction |
| D04 | TOS/FMS/ECS owners | Operation status to mock TOS | Resolve ECS receiver; no crane safety controller |
| D05 | FMS/TOS API owners | Durable 202; polling receipt; matching HTTP 200/code 0 completion | Sign codes, async confirmation and receiver deduplication |
| D06 | TOS owner | Numeric string seqNo; no invented upper bound | Confirm priority and suspension interpretation |
| D07 | Container contract owner | String container number, no fixed length | Resolve workbook 12 vs sample 11 characters |
| D08 | FMS/TOS operations | Explicit offset, 30s future skew, operation freshness 60s | Confirm age/skew and stale replay/resolution rules |
| D09 | Platform/security | Java 21, Boot 3.5.16, HTTPS/JWT/TLS and 256 KiB | Approve versions, endpoints, scopes, principals, credentials and limits |
| D10 | FMS/TOS business owners | Per-source/type/vehicle accepted order; unresolved head blocks | Define cross-type/version and bypass policy |
| D11 | Platform/operations | Provisional sizing and retention only | Demonstrate capacity, SLA, DR and signed retention |

`fos.contract-approved=false` blocks production startup. Set it only after D01–D05 and D08 freshness decisions have documented approval; the flag itself is not approval evidence. Production also requires verified TLS configuration and receiver idempotency confirmation. Unconfigured routes quarantine rather than reach a default address.

The selected Spring Boot 3.5.16 supports Java 21 and Maven >=3.6.3 per [official requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html); this project requires Maven >=3.9. Runtime support/security acceptance by DPW remains D09, including a current dependency scan.

The 11 November extension adds the requested JSP admin interface, isolated Java simulator and Java UITV bridge. The newer workbook message inventory still needs reconciliation before expanding the four existing TOS business contracts.
