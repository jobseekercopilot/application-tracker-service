# Authoritative application creation

Status: **Implemented in Application Tracker for APP-05**  
Contract owner: `jobseekercopilot/application-tracker-service`  
Database boundary: PostgreSQL migration `V6`

## Decision

Creating an application is an explicit Application Tracker command. Document
generation does not define application identity and is not required for manual
or external applications.

Every public create command is owner-scoped by the authenticated boundary:

- a user command is owned only by the access-token `sub`;
- an approved producer supplies explicit owner context using its
  least-privilege service identity;
- the `userId` body field is compatibility context and cannot override either
  authenticated owner.

The tracker stores a snapshot of the job at application time. Job Service
remains authoritative for canonical and provider job identity. Title, company
and location are presentation snapshots and are never used as duplicate
identity.

## Creation modes

| `provenance` | Initial status | Document rule | Intended use |
| --- | --- | --- | --- |
| `GENERATED` | `DOCUMENTS_GENERATED` or `APPLIED` | Both approved CV and cover-letter version references are required | A deliberate application command using generated documents |
| `MANUAL` | `APPLIED` | CV and cover-letter references are independently optional | An application the user records after applying outside the product |
| `EXTERNAL` | `APPLIED` | CV and cover-letter references are independently optional | An application submitted through another system or provider |

`MANUAL` and `EXTERNAL` commands must carry `canonicalJobId`, `provider` and
`externalJobId`. Values are normalized and syntax checked. Existing generated
producers may omit provider identity during their migration window; the
tracker records an explicit `LEGACY` fallback instead of presenting that value
as Job Service-verified provenance.

When a command starts as `APPLIED`, any supplied approved document versions are
frozen as the application-used versions in the same database commit. No
document content is copied into Application Tracker.

## Idempotency and duplicate policy

Callers should send an `Idempotency-Key` header containing a stable key for the
logical application command. Keys:

- are scoped to the authenticated owner;
- contain 1 to 128 safe ASCII identifier characters;
- are stored with a SHA-256 fingerprint of normalized command facts;
- return the existing application with HTTP `200` when the same key and
  command are replayed;
- return HTTP `409` when a key is reused for different command facts.

The initial commit returns HTTP `201`. To preserve safe rollout for current
generated clients, a request without the header receives a deterministic
`legacy-<fingerprint>` key. Identical legacy retries therefore replay one
record, but consumers should adopt explicit keys through their own contract
issues.

Private beta uses one application record per owner and canonical job. A second
idempotency key for that pair returns HTTP `409`; it does not create a second
row. Reapplication policy can be extended later only through an explicit
product and migration decision.

PostgreSQL enforces:

- unique non-null `(user_id, idempotency_key)`;
- unique non-fixture `(user_id, canonical_job_id)`;
- paired key/fingerprint presence;
- valid provenance values and provenance/status combinations;
- both generated-document references for `GENERATED` records.

Concurrent requests may reach the unique constraint together. The losing
transaction rolls back, opens a clean transaction and returns the committed
record only when its fingerprint matches. It cannot continue using a
transaction marked for rollback.

## Failure semantics

| Outcome | HTTP | Persistence |
| --- | --- | --- |
| New valid command | `201` | One record committed |
| Same owner, key and normalized command | `200` | Existing record returned unchanged |
| Same key, different command | `409` | No change |
| Different key, same owner/canonical job | `409` | No duplicate row |
| Invalid provenance, initial state, identity or key | `400` | No row |
| Ineligible document reference | `400` | No row |
| Document Store unavailable before a new commit | `503` | No row |

A replay is resolved before Document Store validation, so a lost successful
response can still be recovered while Document Store is temporarily
unavailable.

## Verification boundary

Local evidence covers:

- generated, manual and external domain rules;
- no-document and partial-document applications where allowed;
- authentication-derived ownership;
- explicit and compatibility idempotency;
- conflicting keys and duplicate canonical jobs;
- concurrent identical commands against real PostgreSQL;
- migration from the legacy schema, restart, backup and restore;
- OpenAPI drift and error semantics.

Consumer rollout remains on CVCL-01, DOCGEN-17 and the client creation
experience. APP-05 does not remove the CV generation producer's current
automatic call, implement a new client screen, or change document workflow,
history, archive or retention behavior.
