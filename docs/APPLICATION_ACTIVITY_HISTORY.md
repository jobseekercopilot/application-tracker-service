# Application activity history

Application Tracker is the authoritative source for current application state
and its immutable activity chronology. Accepted creation, status,
document-reference, APPLIED freeze, generated-withdrawal, document-reference
reconciliation and deletion commands append events in the same transaction as the
current-record mutation.

Retries do not invent activity:

- replaying a successful create returns the original record and event;
- replaying the exact successful APPLIED command returns its durable outcome
  and appends no event; and
- rejected validation, ownership, transition, version or database commands
  append no event.

## Event model

Each event contains:

- stable event and application identifiers plus owner scope;
- event type and previous/current status;
- actor type (`USER`, `SERVICE` or `SYSTEM`) and non-empty actor identity;
- source (`USER`, `SERVICE`, `SYSTEM_DATA` or `MIGRATION`);
- `occurredAt`, the UTC time the activity actually happened;
- `recordedAt`, the separate UTC ingestion time;
- optional reason; and
- the resulting optimistic application `recordVersion`.

Callers may supply `occurredAt` and a reason with a status command. Omitted
times default to receipt time. An explicit time before creation or more than
five minutes in the future is rejected. The first accepted `APPLIED` command
sets `appliedAt` and `applicationUsedAt` to that actual time and appends one
content-free `APPLICATION_DOCUMENTS_FROZEN` event plus the status event.

## Query contract

`GET /api/v1/applications/{id}/history?page=0&size=50` returns an owner-scoped
chronological page. Page size is limited to 100. Bearer callers derive the
owner from `sub`; approved service readers must supply
`X-Application-Owner`.

The response publishes current status/version and `reconciled`. Reconciliation
is true only when the latest event has the same status and record version as
the current application. Consumers must surface a false value as incomplete or
unavailable data and must not invent missing activity from `updatedAt`.

## Immutability and legacy data

Flyway V7 creates `application_events`; V14 permits one event of each type per
application record version and retains the PostgreSQL trigger that rejects event `UPDATE`
and `DELETE`. There is deliberately no foreign key to the mutable current row,
so audit evidence is not cascaded away by the present deletion boundary.
APP-09 still owns the approved archive, retention and privacy policy.

V7 gives each inherited current record exactly one `LEGACY_SNAPSHOT`. It
records the known current status and version and explicitly says earlier
history is unavailable. It does not fabricate past transitions or dates.

The local PostgreSQL recovery suite proves V1-to-V10 migration, restart,
backup/restore survival, legacy snapshot accuracy, workflow and reconciliation
state persistence, and trigger-enforced append-only behavior. This is
repository evidence, not a substitute for the AWS managed-database restore and
retention drill.
