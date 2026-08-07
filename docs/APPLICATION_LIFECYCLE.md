# Application lifecycle and concurrency

## Public transition matrix

Application Tracker accepts only these forward status changes:

| Current status | Allowed next status |
| --- | --- |
| `SAVED` | `DOCUMENTS_GENERATED`, `APPLIED` |
| `DOCUMENTS_GENERATED` | `APPLIED` |
| `APPLIED` | `INTERVIEW`, `OFFER`, `UNSUCCESSFUL`, `WITHDRAWN` |
| `INTERVIEW` | `OFFER`, `UNSUCCESSFUL`, `WITHDRAWN` |
| `OFFER` | `ACCEPTED`, `REJECTED_BY_USER`, `UNSUCCESSFUL`, `WITHDRAWN` |
| `UNSUCCESSFUL` | None; terminal |
| `ACCEPTED` | None; terminal |
| `REJECTED_BY_USER` | None; terminal |
| `WITHDRAWN` | None; terminal |

All other changes return HTTP `409` without changing the record. There is no
public or administrative reopen path. A future reopen capability requires
explicit authorization and a new immutable reasoned event; it must
not bypass this matrix.

Generated-only withdrawal remains the dedicated
`POST /api/v1/applications/{id}/withdraw-generated` operation. Its destructive
document and retention behavior remains subject to APP-09 and is not a status
transition.

`SAVED` is the authoritative “saved to applications” state. It does not imply
that generation or submission occurred. A `SAVED` record may move to
`DOCUMENTS_GENERATED` only after both current references have been owner,
job, type and approval validated. It may move directly to `APPLIED` with no
documents, or with a complete validated pair that is frozen atomically as the
application-used evidence. A partial pair cannot progress.

## Idempotent retries

Sending the record's current status again returns HTTP `200` with the current
representation. It performs no write and does not change `version`, `updatedAt`
or `appliedAt`. This remains true when `expectedVersion` is stale: it lets a
caller safely retry a command whose first successful response was lost.

## Optimistic concurrency contract

Every `ApplicationRecordResponse` includes a monotonic `version`. A caller
should return the last observed value as optional `expectedVersion` when
patching status:

```json
{
  "status": "INTERVIEW",
  "expectedVersion": 3,
  "occurredAt": "2026-07-26T18:00:00Z",
  "reason": "First-stage interview confirmed"
}
```

If the stored version is no longer `3`, the service returns:

```json
{
  "status": 409,
  "message": "Application was changed by another request. Refresh and retry.",
  "timestamp": "2026-07-26T18:00:00"
}
```

`expectedVersion` is optional for backwards compatibility. Database-level JPA
optimistic locking still protects callers that omit it: if two transactions
load the same version, only the first commit succeeds and the other returns the
same stable `409`. Callers receiving this response must fetch the application,
show or reconcile the newer state, and submit a new intentional command.

An invalid transition also returns `409`, with a message identifying the
current and requested statuses. It never mutates the stored row.

## Timestamp rules

- `createdAt` is assigned once when the application record is created.
- `updatedAt` changes only when a permitted transition is successfully stored.
- `appliedAt` is assigned on the first successful transition to `APPLIED` and
  is never overwritten by later transitions.
- `SAVED` and `DOCUMENTS_GENERATED` never assign `appliedAt`.
- invalid, stale, concurrent-losing and same-status commands do not change
  lifecycle timestamps or append events.

Every accepted transition appends its actual `occurredAt` and separate
`recordedAt` to immutable history. Interview, offer and terminal milestone
dates therefore come from events, not from `updatedAt`. See
`APPLICATION_ACTIVITY_HISTORY.md`.

## Deployment and recovery

V5 adds optimistic record versions. V7 adds the append-only event table,
truthful legacy snapshots and the database mutation-rejection trigger. Both
are additive, forward-only migrations. Deploy the producer before consumers
begin sending `expectedVersion`, `occurredAt` or history queries.

V12 adds `SAVED` to the record and immutable-event constraints while preserving
all existing statuses and rows. It also permits non-generated records to enter
`DOCUMENTS_GENERATED` only when both current document IDs are present.

V13 adds the durable owner-scoped document-selection command ledger. It also
removes the database complete-pair constraints because an explicit atomic Save
may omit either optional slot while `SAVED` or `DOCUMENTS_GENERATED`; service
validation still enforces creation and lifecycle-transition prerequisites.
Deploy the V13-capable producer before clients send the new complete-selection
request. Older producers and clients continue to use the deprecated one-slot
operation during the rolling window and cannot interpret a missing new slot as
an intentional omission.

Rollback restores the previous compatible producer and database recovery point
according to `DATABASE_OPERATIONS.md`. Do not remove or reuse the V5 column in
an ad-hoc down migration. APP-04, Infrastructure database work and APP-09 must
still provide deployed backup, retention and restore evidence.
