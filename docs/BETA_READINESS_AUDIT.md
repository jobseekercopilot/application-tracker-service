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
- APP-06 defines and enforces the forward-only lifecycle, makes repeated status
  commands idempotent, publishes record versions and returns stable `409`
  conflicts for invalid, stale or concurrent writes. Unit, HTTP and real
  PostgreSQL tests cover the complete matrix and competing writers.
- APP-11 hardens the System Data producer contract as `3.0.0`: only an explicit
  E2E runtime can use its independent identity; seed uses constrained DTOs; and
  deterministic seed, reset and verification are owner-and-scenario scoped.
  System Data and Infrastructure rollout remain required before integrated use.
- APP-01 approves the source-backed application-domain architecture:
  Application Tracker owns application identity, owner binding, current
  lifecycle, append-only history and application-used document references;
  Authentication, Job Service and Document Store retain authority for their
  own domains. It assigns trust, command/query, failure and test boundaries
  without implementing the linked delivery issues.
- APP-05 implements the explicit creation boundary in Application Tracker:
  generated, manual and external provenance; optional documents where allowed;
  immutable application-time job identity/snapshot fields; owner-scoped
  idempotency; and one database-enforced application per owner/canonical job.
  Real PostgreSQL tests prove concurrent identical commands converge on one
  record. Producer and client rollout remain on their coordinated issues.
- APP-07 stores creation and lifecycle activity as immutable actor/source
  attributed events with separate UTC occurrence and recording times. The
  owner-scoped history API is ordered and paginated, and PostgreSQL migration,
  trigger, retry, reconciliation, backup and restore tests protect the event
  chronology. Reporting and client adoption remain separate delivery work.

## Critical findings

### Ownership architecture approved and enforcement implemented; delivery incomplete

APP-03 closes the source-of-truth service boundary: user ownership comes only
from a validated access-token subject, every record and document lookup includes
that owner, producer and reader identities have separate least-privilege
credentials, and System Data has an independent credential. The service remains
a beta blocker until Job Finder, CV/cover-letter, document generation, Job
Matching, Reporting and Infrastructure adopt those credentials and owner
semantics and the integrated journeys pass.

APP-01 records the approved cross-service boundary in
`APPLICATION_ARCHITECTURE_AND_OWNERSHIP.md`. In particular, generation does
not imply application creation, edge gateways cannot own durable cleanup, and
Reporting/Matching consume derived read models rather than inventing
application facts. APP-05 now implements the Tracker-side create command;
APP-07, APP-08, APP-09 and the APP-05 consumer issues still implement the
remaining decisions.

### Durable repository storage implemented; deployed recovery evidence incomplete

APP-04 replaces the non-test in-memory database with PostgreSQL, introduces
forward-only Flyway migrations, makes Hibernate validate-only, disables the H2
console and SQL logging, and adds real PostgreSQL migration, upgrade, restart,
backup and restore evidence. Production configuration fails closed unless
verified TLS plus managed database/backup encryption references are declared.
Infrastructure must still provide the private managed database, secret
injection, automated encrypted recovery points and a deployed restore drill
before this service can be enabled for beta. APP-06 provides repository
row-versioning; deployment and consumer rollout remain dependencies.

### Lifecycle and immutable history implemented; consumers still pending

APP-06 rejects skipped, backward and post-terminal transitions, treats
same-status retries as no-op successes, and prevents stale or concurrent writes
from silently winning. APP-07 now appends every accepted mutation to
database-enforced immutable history and publishes actual interview, offer and
outcome milestone times through an ordered, owner-scoped API. Reporting and the
client must adopt the new contract before the full user-facing timeline is
beta-ready.

### Tracker creation is decoupled; producer and client rollout incomplete

APP-05 lets Application Tracker create generated, manual and external records
independently of generation. Documents are optional for manual/external
applications, retries are owner-scoped and idempotent, and PostgreSQL prevents
duplicate owner/canonical-job records. CV generation still automatically calls
this contract, and the client does not yet expose a manual/external creation
journey. CVCL-01, DOCGEN-17 and the client issue retain those rollout scopes.

### Cross-service consistency is only partially recoverable

CV generation still saves two documents and creates an application before
billing completion. Generated withdrawal is now a durable Tracker-owned
workflow: Store cleanup is atomic and replay-safe, the application remains
visible during recovery, and Job Finder no longer performs duplicate cleanup.
Document replacement now reserves durable Tracker state before Store writes,
preserves the old reference until approval, and reconciles lost final responses.
APP-08 remains open for broader application/document-link reconciliation and
integrated E2E evidence.

### Unsafe user deletion; System Data boundary implemented, rollout incomplete

The unrestricted delete endpoint permanently removes application history without
an archive, retention or dependent-document policy. APP-11 removes raw entity
fixture writes and owner-wide reset/verification, rejects default/unknown/mixed
profiles and retains the independent APP-03 identity. The producer is intentionally
incompatible with the old System Data caller; that consumer and the E2E
Infrastructure profile must adopt the current `3.3.0` contract before this
blocker can close.

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
coverage. APP-04 and APP-06 add real migrations, recovery, valid-transition and
concurrency coverage. APP-05 adds duplicate, replay and concurrent create
coverage. Tests still do not cover pagination or cleanup failures. Browser E2E
steps can wait and return without asserting
failure, allowing false-positive tracking journeys.

## Functional classification

| Capability | Result |
|---|---|
| Create tracked application | Tracker contract complete; producer/client rollout pending |
| User/application/document lookup | Owner-scoped in the service; consumer rollout pending |
| Status lifecycle | Repository enforcement and event history complete; client adoption pending |
| Activity history/timeline | Producer complete; reporting/client adoption pending |
| Document replacement | Tracker-owned recoverable workflow implemented; integrated E2E evidence pending |
| Withdrawal/deletion | Unsafe; best-effort cleanup and permanent removal |
| Manual/external application capture | Tracker contract complete; client experience pending |
| Search/filter/pagination | Absent |
| Durable storage/migrations | Repository implementation complete; AWS deployment/restore evidence pending |
| Observability/operations | Basic request logs and health only |

The Application Tracking epic contains focused follow-up issues. APP-01
supplies their shared architecture decision; APP-02 through APP-11 already
contain completed repository slices and explicit remaining dependencies. All
other findings remain separately tracked and this document is still not a
beta-readiness approval.
