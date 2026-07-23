# Application Tracker Service beta-readiness audit

Audit date: 2026-07-23  
Decision: **Not ready for private beta**

This is an audit baseline only. It does not authorize application-tracking
enablement or represent a durable, access-controlled source of truth.

## Verified behavior

- Creates application records linked to a job, generated CV and cover letter.
- Retrieves records by record ID, supplied user ID or document ID.
- Updates the active document reference while the current status is
  `DOCUMENTS_GENERATED`.
- Updates the current application status and records the first `APPLIED` time.
- Withdraws generated-only records and attempts Document Store cleanup.
- Exposes System Data seed, reset and verification endpoints behind an
  environment flag.
- The inherited source passed 23 tests with no failures, errors or skips when its
  local generated Document Store client JAR was present.

## Critical findings

### Unauthenticated ownership and enumeration

The service has no authentication or authorization layer. Creation accepts a JSON
`userId`; listing accepts a user ID in the path; record, document, update, withdraw
and delete operations use only identifiers. Any caller able to reach the service
can read or mutate another user's application. Upstream Job Finder routes have
their own open ownership issue, but the source-of-truth service must also enforce
authenticated user and service boundaries.

### Non-durable database and schema

The default database is in-memory H2 with an enabled console,
`ddl-auto=create-drop` and SQL logging. All application history is lost on restart.
There are no versioned migrations, production database configuration, backup and
restore evidence, encryption decision, indexes, row-versioning or recovery test.

### No valid lifecycle or immutable history

Any supported status can replace any other status, including backward moves and
changes after terminal outcomes. Only the current status is stored. Interview,
offer, unsuccessful and withdrawal times are synthesized by clients from the
record's latest update time, so prior events disappear or are misdated. Concurrent
updates have no optimistic version or conflict behavior.

### Generation is coupled to tracking

Creating a record requires both generated-document IDs and always starts at
`DOCUMENTS_GENERATED`. The service cannot represent a manually entered or
externally submitted application without generated documents. Repeated generation
can create duplicate application rows because no authoritative idempotency key or
unique user/job/application identity exists.

### Cross-service consistency is not atomic

CV generation saves two documents and then creates an application before billing
completion. Replacement activates a Document Store version before updating the
tracker. Generated withdrawal swallows tracker-side document deactivation errors,
deletes the application, and is followed by a second best-effort hard delete in
Job Finder Gateway. Failures can leave orphaned documents, missing references or
an application state that cannot be reconciled.

### Unsafe deletion and environment-data control

The unrestricted delete endpoint permanently removes application history without
an archive, retention or dependent-document policy. System Data can seed arbitrary
entity graphs when enabled; allowed environments include `default`, and there is
no service authentication.

### Query, privacy and operational limits

User lists are unpaginated and unsorted. There are no indexes for user, job or
document lookups and document lookup loads all matches before selecting the first.
Logs include raw user, application, job and document identifiers. Only health is
exposed; no application-transition, downstream consistency, latency/error,
reconciliation or audit-event telemetry exists.

### Contracts, tests and licensing

The build uses a repository-local `systemPath` generated-client JAR even though
runtime code uses a manually constructed `RestTemplate`. A clean checkout cannot
resolve that JAR, and the Docker build copies the missing `libs` directory.
OpenAPI and README content drift from the implementation, including an MIT claim
that conflicts with the proprietary repository. Existing service tests do not
cover authentication, cross-user access, valid transition rules, duplicate create,
concurrency, migrations, pagination, cleanup failures, System Data isolation or
privacy redaction. Browser E2E steps can wait and return without asserting failure,
allowing false-positive tracking journeys.

## Functional classification

| Capability | Result |
|---|---|
| Create tracked application | Incomplete; generated-document-only and non-idempotent |
| User/application/document lookup | Unsafe; caller-selected ownership |
| Status lifecycle | Incomplete; unrestricted current-state replacement |
| Activity history/timeline | Absent; clients synthesize lossy events |
| Document replacement | Incomplete; cross-service consistency gap |
| Withdrawal/deletion | Unsafe; best-effort cleanup and permanent removal |
| Manual/external application capture | Absent |
| Search/filter/pagination | Absent |
| Durable storage/migrations | Absent but required |
| Observability/operations | Basic request logs and health only |

The Application Tracking epic contains focused follow-up issues. All remain
Backlog and no issue was implemented during this audit.
