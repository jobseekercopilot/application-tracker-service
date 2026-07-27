# Application Tracker Service

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
or stale updates return `409`, same-status retries are idempotent, and every
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

The version `3.1.0` contract retains the owner-scoped public list and lifecycle
concurrency contracts while replacing the unsafe raw-entity System Data routes
with a constrained, versioned, owner-and-scenario-scoped fixture boundary and
adds backward-compatible activity-history operations and timestamp fields. It
continues to publish the explicit response and stable authentication,
authorization, conflict and owner-mismatch error schemas used by consumers.

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
