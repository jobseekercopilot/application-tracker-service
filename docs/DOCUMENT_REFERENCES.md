# Canonical and application-used document references

Application Tracker stores references only. Document Store remains authoritative
for document ownership, type, lifecycle, content and family versioning.

## Validation

Create and replacement requests contain Document Store UUIDs. Application
Tracker resolves each UUID through the owner-scoped
`GET /api/v1/documents/{id}/reference` endpoint using its least-privilege reader
identity. It accepts a reference only when:

- Document Store confirms the same owner context;
- the reference is `APPROVED`;
- CV and cover-letter slots have the expected document type;
- the document job matches the application job; and
- family ID, positive version and content SHA-256 are present.

Missing, foreign, draft, wrong-job and wrong-type references return the same
non-enumerating application validation error. Store outages return `503` and do
not create or mutate an application.

## Current versus application-used

While an application is `SAVED` or `DOCUMENTS_GENERATED`, its current CV and
cover-letter references may be attached or replaced after revalidation. A
saved application remains `SAVED` as references are attached; the caller must
explicitly request `DOCUMENTS_GENERATED` after a complete pair exists.

The authoritative operation is
`PUT /api/v1/applications/{id}/document-selections`. It replaces both optional
slots atomically. `cvSelection` and `coverLetterSelection` are both required;
each is either `SELECTED` with one exact Document Store version ID or `OMITTED`
without an ID. This deliberate shape means a property omitted by an older or
partially deployed client is rejected instead of being mistaken for an
intentional clear. The command also requires the observed `expectedVersion`
and an owner-scoped `Idempotency-Key`. Stale writes return `409` with the
authoritative application record, exact retries return their stored original
outcome, and key reuse for another payload conflicts.

Selected versions may be uploaded or generated; both follow the same verifier
and response model. The owner, canonical beta job, type, `APPROVED` lifecycle
and availability checks come from the owner-scoped Document Store reference
endpoint. Family current is recommendation metadata only: Save persists the
exact selected IDs and later current changes do not rewrite them. A real atomic
change appends one content-free `DOCUMENT_REFERENCE_CHANGED` event. Clearing a
slot is represented in that event without storing document content or IDs in
the event reason.

The deprecated `PATCH /api/v1/applications/{id}/document-reference` remains
temporarily available for rolling deployments. It cannot express an omission;
new clients must use the complete atomic command.

On the first successful transition to `APPLIED`, Application Tracker
re-verifies every present exact selection and atomically copies it into
immutable application-used fields. Both optional frozen slots record
`SELECTED` or explicit `OMITTED`, so none, CV only, cover letter only and both
are valid. Existing ambiguous rows migrate as `UNKNOWN`; no legacy omission is
invented. One timestamp is used for the freeze and `appliedAt`.

Later current selection changes in Document Store cannot rewrite that snapshot.
Even if a separate status-lifecycle change later permits status regression, an
application with frozen references cannot replace them. Submitted applications
also require retention-aware deletion.

## Availability and purge projection

Document Store obtains an authoritative content-free association snapshot from
`GET /api/v1/applications/document/{documentId}/associations` before purge.
Each association is explicitly `DRAFT_SELECTED` or `FROZEN_USED`; an empty
owner-scoped list is authoritative and another owner's document remains
non-enumerable.

Store projects ordered lifecycle state through
`PUT /api/v1/applications/document/{documentId}/availability`. Application
responses expose `AVAILABLE`, `ARCHIVED`, `DELETED` or `PURGED`, a stable
unavailable reason and timestamp on every matching current and frozen exact
reference. `PURGED` is terminal and clears complete content hashes, evidence
payloads and grounding details from all matching slots. The document ID,
family ID, server version and type remain so application history never points
to a replacement. A stale update cannot overwrite newer lifecycle state.

Document replacement now reserves a Tracker-owned workflow before Store writes,
preserves the old reference until verification succeeds, and commits the new
reference and activity event atomically. See
`RECOVERABLE_DOCUMENT_WORKFLOWS.md`. Broader repair of pre-existing mismatches
remains APP-08 work.
