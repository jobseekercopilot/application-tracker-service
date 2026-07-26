# Application Tracker Service

Spring Boot service for the inherited Job Seeker Copilot application-record,
status and generated-document reference model.

This repository is a sanitised audit baseline, not a beta-ready application
tracking system. See [`docs/BETA_READINESS_AUDIT.md`](docs/BETA_READINESS_AUDIT.md).

Its ownership boundary with Job Search and Job Matching is defined in the
Infrastructure
[Job Search architecture ADR](https://github.com/jobseekercopilot/infrastructure/blob/develop/docs/adr/0001-job-search-architecture-and-ownership.md).

## Build

Java 17 and Maven are required.

```bash
mvn -B clean verify
```

The service builds and runs its tests from tracked source only. Non-test
runtime uses PostgreSQL with forward-only Flyway migrations and Hibernate
schema validation. H2 is restricted to isolated tests. The real PostgreSQL
verification covers migration, restart persistence, backup and restore without
calling AWS or any paid service. See
[`docs/DATABASE_OPERATIONS.md`](docs/DATABASE_OPERATIONS.md).

The producer-owned OpenAPI contract is in `contracts/openapi.json`.
`OpenApiExportTest` generates the runtime document and fails `mvn clean verify`
on semantic drift. See [`contracts/README.md`](contracts/README.md) for the
reviewed update and consumer-pinning process. The Docker build runs the same
clean verification before packaging.

The version `1.1.0` owner-scoped list operation explicitly publishes the
generated response model and stable authentication, authorization and
owner-mismatch error schemas used by read-only consumers such as Job Matching.

## Security boundary

Every public application request is authenticated. User requests require an
RS256 Bearer access token and derive ownership only from JWT `sub`. Approved
backend callers use distinct runtime-injected producer or reader credentials
with least-privilege routes and explicit owner context. System Data uses a
third independent credential and remains disabled unless explicitly enabled in
an allowed non-production environment.

See [`docs/SECURITY_BOUNDARY.md`](docs/SECURITY_BOUNDARY.md) for the claims,
authorization matrix, denial semantics, configuration and rollout contract.

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

All three service credentials must contain at least 32 bytes, must be distinct
and must be supplied at runtime. Do not put real values in source, examples,
logs or issue comments.

## Licence

Proprietary and confidential. See `LICENSE`.
