# Application Tracker System Data boundary

Application fixtures are a controlled E2E-test facility, not an administration
API and not a production data-import path. The boundary is disabled unless all
of these conditions are true:

- `ENVIRONMENT_DATA_ENABLED=true`
- exactly one Spring profile is active
- that profile is `e2e`
- `ENVIRONMENT_DATA_ALLOWED_ENVIRONMENTS` contains exactly `e2e`
- the caller supplies the independent `X-Environment-Data-Token`

The default, local, test, demo, unknown, mixed, `prod` and `production` profile
sets all fail closed. Expanding the hard safe-profile set requires a reviewed
Application Tracker change; runtime configuration alone cannot enable a new
environment.

## Contract

The producer OpenAPI version is `3.2.0`. Version `3.2.0` adds backward-compatible
durable generated-withdrawal status. Version `3.0.0` introduced the breaking
major change that removes the
legacy raw-entity and owner-wide operations:

- `POST /internal/system-data/seed/applications`
- `GET /internal/system-data/verify/applications/{userId}`
- `DELETE /internal/system-data/scenario/{scenarioId}/applications/{userId}`

The replacement operations are:

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/internal/system-data/v1/application-scenarios` | Atomically replace one owner/scenario fixture set |
| `GET` | `/internal/system-data/v1/application-scenarios/{scenarioId}/owners/{userId}` | Verify only that owner/scenario fixture set |
| `DELETE` | `/internal/system-data/v1/application-scenarios/{scenarioId}/owners/{userId}` | Reset only that owner/scenario fixture set |

The seed envelope schema is `2.0.0`; it requires exact synthetic document
family, version and checksum evidence in addition to the document version IDs.

Example seed envelope:

```json
{
  "schemaVersion": "2.0.0",
  "scenarioId": "demo-ready-v1",
  "userId": "018d04b1-ec4a-7fd1-87f8-53854be1a296",
  "applications": [
    {
      "id": "018d04b2-1fe0-7761-8090-2bc31eb5bf51",
      "jobId": "fixture-job-1",
      "canonicalJobId": "fixture-job-1",
      "provider": "FIXTURE",
      "externalJobId": "fixture-1",
      "jobTitle": "Software Developer",
      "companyName": "Example Ltd",
      "location": "Remote",
      "cvDocumentId": "018d04b2-5549-72ac-9b17-22c2ffaf69a8",
      "cvDocumentFamilyId": "018d04b2-5549-72ac-9b17-22c2ffaf69a9",
      "cvDocumentVersion": 1,
      "cvDocumentContentSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "coverLetterDocumentId": "018d04b2-759c-7058-a9e1-8c97cef799d8",
      "coverLetterDocumentFamilyId": "018d04b2-759c-7058-a9e1-8c97cef799d9",
      "coverLetterDocumentVersion": 1,
      "coverLetterDocumentContentSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      "status": "DOCUMENTS_GENERATED",
      "createdAt": "2026-07-01T09:00:00",
      "updatedAt": "2026-07-01T09:00:00",
      "appliedAt": null
    }
  ]
}
```

The envelope is authoritative for ownership and scenario. Application entries
cannot supply `userId`, `fixtureScenarioId` or other JPA/persistence fields.
Unknown fields, unknown schema versions, duplicate IDs, invalid UUIDs, more
than 100 records, invalid lengths, impossible timestamp order and inconsistent
status timestamps are rejected before mutation.

Both document references carry the exact synthetic Document Store family,
version and content checksum used by the scenario. A progressed fixture freezes
those references as its application-used evidence; a generated-only fixture
leaves application-used evidence unset until its first accepted transition.

## Data isolation and repeatability

`fixtureScenarioId` is internal persistence metadata and is never present in
the public application response or the fixture input. It is nullable so normal
user-created records remain outside every fixture scenario.

Seed is deterministic and idempotent:

- supplied application UUIDs are retained exactly
- reseeding replaces only the matching owner/scenario records
- an empty application list clears only that scenario
- an ID collision with a normal record, another owner or another scenario
  rejects the complete request
- reset and verification always query both owner and scenario
- response details contain counts, scenario and status summary, not owner IDs

The operation is transactional. A validation or collision error cannot leave a
partially replaced scenario.

## Operational use

System Data must adopt this `3.2.0` producer contract before the integrated E2E
journey can use APP-11. Infrastructure must activate the explicit `e2e` profile
only in the isolated E2E Compose overlay. Base/developer Compose must leave this
boundary disabled.

APP-04's forward-only PostgreSQL migrations include nullable
`fixture_scenario_id` plus the partial owner/scenario query index. Inherited and
ordinary user-created rows remain outside every fixture scenario. APP-11 does
not make fixture data a production capability.

Rollback restores the previous producer and its matching System Data consumer
together. Do not route a `3.x` request to an older producer or re-enable the
removed unsafe endpoints for compatibility.
