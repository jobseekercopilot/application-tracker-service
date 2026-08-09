# Application Tracker Service

## Role in Job Seeker Copilot

| Role | Called by | Calls | Data | Local port |
|---|---|---|---|---:|
| System of record for applications, lifecycle, immutable events and exact document references | Job Finder, Document Generation, Reporting and lifecycle coordinators | Document Store for recoverable document workflows | Own PostgreSQL database | 8088 |

See the central [application journey](https://docs.jobseekercopilot.com/journeys/applications/), [document journey](https://docs.jobseekercopilot.com/journeys/documents/), and [data ownership](https://docs.jobseekercopilot.com/data/ownership/).

Uploaded selections are accepted only after Document Store confirms the exact
approved immutable version belongs to the same owner, job, application, and
document type; Application Tracker commits the selection atomically.

Spring Boot service for the inherited Job Seeker Copilot application-record,
status and generated-document reference model.

This repository is a sanitised audit baseline, not a beta-ready application
tracking system. See [`docs/BETA_READINESS_AUDIT.md`](docs/BETA_READINESS_AUDIT.md).

Its ownership boundary with Job Search and Job Matching is defined in the
Infrastructure
[Job Search architecture ADR](https://github.com/jobseekercopilot/infrastructure/blob/develop/docs/adr/0001-job-search-architecture-and-ownership.md).
The approved application-domain ownership, trust, command/query, failure and
test boundaries are defined in
[`docs/APPLICATION_ARCHITECTURE_AND_OWNERSHIP.md`](docs/APPLICATION_ARCHITECTURE_AND_OWNERSHIP.md).
The authoritative generated, manual and external creation contract, including
owner-scoped retry and duplicate semantics, is documented in
[`docs/APPLICATION_CREATION.md`](docs/APPLICATION_CREATION.md).
The immutable event model, actual milestone timestamps and owner-scoped
paginated history contract are documented in
[`docs/APPLICATION_ACTIVITY_HISTORY.md`](docs/APPLICATION_ACTIVITY_HISTORY.md).

## Build

Java 17 and Maven are required.

```bash
mvn -B clean verify
```

The service builds and runs its tests from tracked source only. Non-test
runtime uses PostgreSQL with forward-only Flyway migrations and Hibernate
schema validation. H2 is restricted to isolated tests. The real PostgreSQL
verification covers migration, restart persistence, optimistic concurrency,
backup and restore without calling AWS or any paid service. See
[`docs/DATABASE_OPERATIONS.md`](docs/DATABASE_OPERATIONS.md).

Application status changes follow a documented forward-only lifecycle. Invalid
or stale updates return `409`, exact command replays are idempotent, and every
accepted mutation appends an immutable actor/source-attributed event in the
same transaction. Every response publishes the record's optimistic `version`.
See
[`docs/APPLICATION_LIFECYCLE.md`](docs/APPLICATION_LIFECYCLE.md).

Create commands support explicit `GENERATED`, `MANUAL` and `EXTERNAL`
provenance. Manual/external applications can be tracked without generated
documents. Owner-scoped idempotency keys and PostgreSQL uniqueness make lost
responses and concurrent retries converge on one application record.

The producer-owned OpenAPI contract is in `contracts/openapi.json`.
`OpenApiExportTest` generates the runtime document and fails `mvn clean verify`
on semantic drift. See [`contracts/README.md`](contracts/README.md) for the
reviewed update and consumer-pinning process. Run the complete Maven
verification before building the image. The Docker build compiles the test
sources but does not execute the PostgreSQL Testcontainers suite because a
standard image build must not receive the host Docker socket.

The version `4.9.0` contract retains the owner-scoped public list and lifecycle
concurrency contracts while replacing the unsafe raw-entity System Data routes
with a constrained, versioned, owner-and-scenario-scoped fixture boundary and
adds backward-compatible activity-history, recoverable generated-withdrawal
and durable document-replacement and document-reference reconciliation
operations. A withdrawal or replacement
returns a durable operation ID and either a completed `200` or recovery-pending
`202`; it never reports success after only part of a cross-service workflow. It
also preserves validated authoritative listing, application and attribution
metadata with each tracked job. NHS fixture URLs are accepted only in the
explicit fixture source mode; live mode accepts only the official vacancy path.
It also publishes owner-scoped reconciliation state, safely repairs missing
immutable reference metadata, and blocks lifecycle progression when references
are unverified. Current and application-used references now include immutable
profile, evidence-snapshot, claim-ledger and grounding provenance. It continues
to publish the explicit response and stable
authentication, authorization, conflict and owner-mismatch error schemas used
by consumers. Version `4.0.0` also aligns evidence-section values with the
canonical User Profile and Document Store taxonomy so approved generated
document references can be consumed without translation.

Version `4.6.0` adds content-free activity types for the first explicit
application document choice, later changed choices, and the existing exact
apply-time freeze. Retry replays and later commands that preserve both choices
do not add duplicate activity.

Version `4.7.0` records a newly created `SAVED` application as the explicit,
content-free `APPLICATION_SAVED` activity. Other creation modes retain
`APPLICATION_CREATED`, and idempotent creation replay does not duplicate either
event.

Version `4.8.0` adds owner, source, original-byte hash and selection time to
current and frozen exact-version descriptors. Apply preserves the selected
timestamp for zero, one or two slots; archive/delete/purge projections never
substitute another version. `PURGED` remains terminal and retains only the
minimal exact ID/version/hash/source/time tombstone plus availability metadata,
while content-bearing evidence provenance is removed.

Version `4.9.0` adds an isolated-E2E-only internal runtime-owner cleanup and
verification boundary for deterministic named-state identities. Existing
public operations and versioned fixture routes are unchanged.

Version `4.1.0` adds the authoritative `SAVED` lifecycle state. Existing
manual/external requests still default to `APPLIED`; callers opt into `SAVED`
explicitly and may attach validated documents before progressing.

Version `4.2.0` adds one retry-safe atomic Save command for the complete
optional CV and cover-letter selection. Both slots must explicitly be
`SELECTED` or `OMITTED`; the command rejects stale record versions, persists a
payload-fingerprinted owner-scoped idempotency outcome, and preserves the
single-slot endpoint as a deprecated rolling-deploy bridge.

Version `4.3.0` makes the first successful transition into `APPLIED` the only
document-freeze boundary. The command requires `expectedVersion` and an
owner-scoped `Idempotency-Key`, re-verifies each present exact version, and
atomically stores `SELECTED` or `OMITTED` for both frozen slots, one freeze
time, the applied status/time, a durable replay outcome, and content-free
freeze/status events. Existing ambiguous rows migrate as `UNKNOWN` rather than
inventing an omission.

Version `4.4.0` adds content-free exact-document association lookup and an
ordered availability projection for archive, recoverable deletion, restore and
purge. `PURGED` is terminal: Tracker retains exact application/family/version
identity and draft/frozen association state while scrubbing complete hashes and
evidence details. Version `4.8.0` corrects that legacy projection behaviour:
the approved minimal tombstone retains exact hashes, source and selection time
while content-bearing evidence details are scrubbed. Cross-owner lookups return
an empty authoritative snapshot.

Version `4.5.0` adds a no-store account export and an internal, idempotent
personal-data erasure step. The internal route accepts only a short-lived
account-lifecycle token with an operation ID; ordinary user tokens cannot call
it. A narrowly scoped PostgreSQL transaction override permits account erasure
without weakening the append-only event invariant for normal application code.

## Security boundary

Every public application request is authenticated. User requests require an
RS256 Bearer access token and derive ownership only from JWT `sub`. Approved
backend callers use distinct runtime-injected producer or reader credentials
with least-privilege routes and explicit owner context. System Data uses a
third independent credential and remains disabled unless explicitly enabled in
the single explicit `e2e` profile.

See [`docs/SECURITY_BOUNDARY.md`](docs/SECURITY_BOUNDARY.md) for the claims,
authorization matrix, denial semantics, configuration and rollout contract.
See [`docs/SYSTEM_DATA_BOUNDARY.md`](docs/SYSTEM_DATA_BOUNDARY.md) for the
versioned fixture schema, scenario isolation rules and operational runbook.

| Environment variable | Purpose |
| --- | --- |
| `AUTH_JWKS_URI` | Authentication Service JWKS endpoint |
| `APPLICATION_TRACKER_JWT_ISSUER` | Required access-token issuer |
| `APPLICATION_TRACKER_JWT_AUDIENCE` | Required access-token audience |
| `APPLICATION_TRACKER_PRODUCER_TOKEN` | Create/read/document-link service identity |
| `APPLICATION_TRACKER_READER_TOKEN` | Read-only service identity |
| `ENVIRONMENT_DATA_TOKEN` | Independent non-production fixture identity |
| `DOCUMENT_STORE_PRODUCER_TOKEN` | Tracker-owned document-workflow command identity |
| `APPLICATION_DOCUMENT_RECONCILIATION_INITIAL_DELAY_MS` | Delay before the first document-reference reconciliation pass |
| `APPLICATION_DOCUMENT_RECONCILIATION_DELAY_MS` | Delay between bounded reconciliation passes |
| `APPLICATION_TRACKER_DATABASE_URL` | PostgreSQL JDBC target |
| `APPLICATION_TRACKER_DATABASE_USERNAME` | Dedicated database role |
| `APPLICATION_TRACKER_DATABASE_PASSWORD` | Runtime-injected database secret |
| `APPLICATION_TRACKER_DATABASE_SSL_MODE` | Verified TLS mode (`verify-full` in production) |
| `APPLICATION_TRACKER_DATABASE_ENCRYPTION_AT_REST_ENABLED` | Production encryption attestation |
| `APPLICATION_TRACKER_DATABASE_ENCRYPTION_KEY_REFERENCE` | Non-secret managed key identifier |
| `APPLICATION_TRACKER_DATABASE_BACKUP_ENCRYPTION_ENABLED` | Encrypted-backup attestation |
| `APPLICATION_TRACKER_DATABASE_BACKUP_KEY_REFERENCE` | Non-secret backup key identifier |
| `ENVIRONMENT_DATA_ENABLED` | Must be `true` to enable fixture operations |
| `ENVIRONMENT_DATA_ALLOWED_ENVIRONMENTS` | Must contain exactly `e2e` |

All three service credentials must contain at least 32 bytes, must be distinct
and must be supplied at runtime. Do not put real values in source, examples,
logs or issue comments.

## Licence

Proprietary and confidential. See `LICENSE`.
