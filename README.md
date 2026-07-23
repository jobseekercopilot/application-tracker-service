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

The inherited source passes 23 tests when its repository-local generated
Document Store client JAR is present. A clean checkout intentionally exposes that
unreproducible dependency until the contract/client build is corrected.

The captured OpenAPI contract is in `contracts/openapi.json`.

## Licence

Proprietary and confidential. See `LICENSE`.
