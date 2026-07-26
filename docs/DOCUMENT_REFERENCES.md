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

While an application is `DOCUMENTS_GENERATED`, its current CV and cover-letter
references may be replaced after revalidation. On the first transition to any
later status, Application Tracker copies both descriptors into immutable
application-used fields and timestamps the freeze.

Later current selection changes in Document Store cannot rewrite that snapshot.
Even if a separate status-lifecycle change later permits status regression, an
application with frozen references cannot replace them. Submitted applications
also require retention-aware deletion.

Durable database migration, transition policy, and cross-service recovery remain
owned by APP-04, APP-06 and APP-08 respectively.
