# Recoverable application document workflows

Application Tracker owns the durable outcome of cross-service application
commands. The first delivered APP-08 workflow is generated-only withdrawal.
Document replacement and application/document-link reconciliation remain
tracked by APP-08 and DOCGEN-17.

## Generated withdrawal

`POST /api/v1/applications/{id}/withdraw-generated` accepts an owner-scoped
command only while the application is `DOCUMENTS_GENERATED`. Tracker snapshots
the selected document IDs and records one stable operation ID before calling
Document Store. A repeated request for the same owner and application loads the
same workflow.

Tracker asks Document Store to soft-delete only document versions that are not
referenced by another application for that owner. Document Store processes the
complete list atomically and records the operation ID, so a lost response can
be replayed without duplicating lifecycle events. Tracker deletes the generated
application only after that command succeeds. Until then, the application
remains visible and status, document-reference and deletion commands are
rejected.

```mermaid
sequenceDiagram
    participant User
    participant Finder as Job Finder
    participant Tracker as Application Tracker
    participant Store as Document Store

    User->>Finder: withdraw generated application
    Finder->>Tracker: POST withdraw-generated
    Tracker->>Tracker: persist PENDING operation and document snapshot
    Tracker->>Store: atomic cleanup(operationId, applicationId, documentIds)
    alt cleanup committed
        Store-->>Tracker: 204
        Tracker->>Tracker: append event, remove application, mark COMPLETED
        Tracker-->>Finder: 200 COMPLETED
    else dependency or validation failure
        Store-->>Tracker: error
        Tracker->>Tracker: mark RECOVERY_REQUIRED
        Tracker-->>Finder: 202 RECOVERY_REQUIRED
    end
    Finder-->>User: preserve Tracker status and operation fields
```

## State and response contract

| State | Meaning | HTTP result |
| --- | --- | --- |
| `PENDING` | The operation is durable but cleanup has not been claimed | `202` |
| `RUNNING` | A caller or recovery worker claimed an attempt | `202` if observed |
| `RECOVERY_REQUIRED` | Cleanup failed; `retryable` and `recoveryCode` explain the next action | `202` |
| `COMPLETED` | Document cleanup succeeded and the generated application was removed | `200` |

The response includes `operationId`, `operationStatus`, `retryable`,
`recoveryCode` and `completedAt`, while retaining the existing
`applicationId`, `status`, `withdrawn` and `message` fields. The same state is
available from `GET /api/v1/applications/{id}/withdraw-generated`.

Transient Store failures use `DOCUMENT_STORE_UNAVAILABLE` and are retried by a
bounded scheduled worker. A rejected cleanup, malformed stored reference or
Tracker state mismatch is non-retryable and remains visible for reviewed
repair. The worker processes at most 50 oldest retryable workflows per pass.

## Operational recovery

1. Query the owner-scoped status endpoint and record the operation status and
   recovery code. Do not log owner or document identifiers.
2. For `DOCUMENT_STORE_UNAVAILABLE`, restore the dependency and either replay
   the original POST or allow the scheduled worker to retry the same operation.
3. For a non-retryable code, inspect Tracker and Store audit evidence before a
   narrowly reviewed repair. Do not delete the application or documents by
   hand to make the status disappear.
4. Confirm `COMPLETED`, the withdrawal activity event and the absence of the
   generated application. Document Store must show at most one soft-delete
   event per cleaned document with the operation ID as its case reference.

## Verification boundary

Local integration tests prove failure-before-cleanup, visible pending state,
blocked concurrent application mutation, same-operation replay, atomic
two-document cleanup, rollback when any document is missing, owner isolation,
changed-payload rejection and completed replay without duplicate lifecycle
events. PostgreSQL migration tests prove that workflow state survives restart.
No AWS resource, paid provider or GitHub Actions execution is required for this
evidence.

