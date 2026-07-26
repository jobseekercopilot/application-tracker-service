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

The service builds and runs its tests from tracked source only. The inherited,
unused repository-local Document Store client dependency has been removed;
Document Store integration uses the repository-owned HTTP adapter.

The producer-owned OpenAPI contract is in `contracts/openapi.json`.
`OpenApiExportTest` generates the runtime document and fails `mvn clean verify`
on semantic drift. See [`contracts/README.md`](contracts/README.md) for the
reviewed update and consumer-pinning process. The Docker build runs the same
clean verification before packaging.

The version `2.0.0` contract retains the owner-scoped public list operation and
replaces the unsafe raw-entity System Data routes with a constrained,
versioned, owner-and-scenario-scoped fixture boundary. The list operation
explicitly publishes the
generated response model and stable authentication, authorization and
owner-mismatch error schemas used by read-only consumers such as Job Matching.

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
| `ENVIRONMENT_DATA_ENABLED` | Must be `true` to enable fixture operations |
| `ENVIRONMENT_DATA_ALLOWED_ENVIRONMENTS` | Must contain exactly `e2e` |

All three service credentials must contain at least 32 bytes, must be distinct
and must be supplied at runtime. Do not put real values in source, examples,
logs or issue comments.

## Licence

Proprietary and confidential. See `LICENSE`.
