# Application Tracker Service

Spring Boot service for the inherited Job Seeker Copilot application-record,
status and generated-document reference model.

This repository is a sanitised audit baseline, not a beta-ready application
tracking system. See [`docs/BETA_READINESS_AUDIT.md`](docs/BETA_READINESS_AUDIT.md).

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

## Licence

Proprietary and confidential. See `LICENSE`.
