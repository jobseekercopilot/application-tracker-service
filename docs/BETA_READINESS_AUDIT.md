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
- APP-02 removes that unused binary dependency: the clean source build now owns
  its OpenAPI contract, semantically drift-checks it in Maven/CI, and makes the
  source-only Docker build compile the same tracked test sources. The complete
  verification gate runs before image packaging because PostgreSQL
  Testcontainers must not receive the host Docker socket during `docker build`.
- APP-03 adds fail-closed RS256 access-token verification, JWT-subject ownership,
  separate producer/reader/environment-data service credentials, owner-scoped
  repository queries and stable non-enumerating denial responses. The clean
  build passes 32 tests, including forged and invalid token cases, cross-user
  record/document/list/mutation attempts and least-privilege service access.
- MATCH-02 producer preparation versions the owner-scoped list contract as
  `1.1.0` and makes its guaranteed fields plus `401`, `403` and `404` models
  explicit. A focused policy test prevents that consumer boundary from
  silently weakening.

## Critical findings

### Ownership enforcement implemented; consumer rollout incomplete

APP-03 closes the source-of-truth service boundary: user ownership comes only
from a validated access-token subject, every record and document lookup includes
that owner, producer and reader identities have separate least-privilege
credentials, and System Data has an independent credential. The service remains
a beta blocker until Job Finder, CV/cover-letter, document generation, Job
Matching, Reporting and Infrastructure adopt those credentials and owner
semantics and the integrated journeys pass.

### Durable repository storage implemented; deployed recovery evidence incomplete

APP-04 replaces the non-test in-memory database with PostgreSQL, introduces
forward-only Flyway migrations, makes Hibernate validate-only, disables the H2
console and SQL logging, and adds real PostgreSQL migration, upgrade, restart,
backup and restore evidence. Production configuration fails closed unless
verified TLS plus managed database/backup encryption references are declared.
Infrastructure must still provide the private managed database, secret
injection, automated encrypted recovery points and a deployed restore drill
before this service can be enabled for beta. Row-versioning remains APP-06.

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
APP-03 removes raw user, application, job and document identifiers from its
request and service logs. Only health is exposed; no application-transition,
downstream consistency, latency/error, reconciliation or audit-event telemetry
exists.

### Contracts, tests and licensing

The inherited build used a repository-local `systemPath` generated-client JAR
even though runtime code uses a manually constructed `RestTemplate`. APP-02
removes the unused binary, makes the Docker build verify source, and
runtime-checks the tracked OpenAPI document. Its conflicting MIT metadata is
replaced with the repository's proprietary classification. APP-03 adds
authentication, cross-user access, System Data isolation and privacy-redaction
coverage. Tests still do not cover valid transition rules, duplicate create,
concurrency, migrations, pagination or cleanup failures. Browser E2E steps can
wait and return without asserting failure, allowing false-positive tracking
journeys.

## Functional classification

| Capability | Result |
|---|---|
| Create tracked application | Incomplete; generated-document-only and non-idempotent |
| User/application/document lookup | Owner-scoped in the service; consumer rollout pending |
| Status lifecycle | Incomplete; unrestricted current-state replacement |
| Activity history/timeline | Absent; clients synthesize lossy events |
| Document replacement | Incomplete; cross-service consistency gap |
| Withdrawal/deletion | Unsafe; best-effort cleanup and permanent removal |
| Manual/external application capture | Absent |
| Search/filter/pagination | Absent |
| Durable storage/migrations | Repository implementation complete; AWS deployment/restore evidence pending |
| Observability/operations | Basic request logs and health only |

The Application Tracking epic contains focused follow-up issues. APP-02 is the
first implementation slice; all other findings remain separately tracked and
this document is still not a beta-readiness approval.
