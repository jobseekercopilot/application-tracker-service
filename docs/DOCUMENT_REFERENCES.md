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

On transition to `APPLIED`, Application Tracker copies a complete current pair
into immutable application-used fields and timestamps the freeze. A saved
application with no documents may also move directly to `APPLIED`, while a
partial pair is rejected.

Later current selection changes in Document Store cannot rewrite that snapshot.
Even if a separate status-lifecycle change later permits status regression, an
application with frozen references cannot replace them. Submitted applications
also require retention-aware deletion.

Document replacement now reserves a Tracker-owned workflow before Store writes,
preserves the old reference until verification succeeds, and commits the new
reference and activity event atomically. See
`RECOVERABLE_DOCUMENT_WORKFLOWS.md`. Broader repair of pre-existing mismatches
remains APP-08 work.
