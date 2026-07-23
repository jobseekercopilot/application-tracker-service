# Application Tracker security boundary

## User access tokens

`/api/v1/applications/**` accepts platform access tokens issued by
Authentication Service. Validation is fail closed and requires:

- an RS256 signature from the configured JWKS
- the configured issuer and audience
- a current expiry time
- a nonblank `sub`
- `token_type=access`

The service never receives an authentication private key. Keep previous public
keys published for at least the access-token lifetime, allowed clock skew and
JWKS cache window during rotation.

User ownership is always JWT `sub`. `X-User-Id`, `X-Application-Owner`, URL
values and JSON `userId` have no independent authority. The legacy
`/user/{userId}` route is retained for consumer compatibility but is strictly
bound to `sub`. Create requests must carry the same owner in their existing
schema; the stored owner is still derived from the authenticated subject.

## Service identities

Approved backend callers send exactly one `X-Service-Token` value injected from
runtime configuration. Browser input must never be copied into that header.
Tokens have a minimum length of 32 bytes and all security tokens must be
distinct.

ID- and document-based service calls also send `X-Application-Owner`. This is
trusted only after service authentication and is included in the repository
query. List calls use the owner-bound path, and create calls use the existing
body owner.

| Identity | Allowed operations | Intended callers |
| --- | --- | --- |
| Bearer user | Own create, list, record/document read, status, document-reference, withdraw and delete | Job Finder delegated user path |
| Producer | Create, owner-scoped read and document-reference update | CV/cover-letter and document-generation services |
| Reader | Owner-scoped read only | Job Matching and Reporting |
| Environment Data | `/internal/system-data/**` only | Controlled non-production fixture orchestration |

The producer identity cannot change application status, withdraw or delete.
The reader identity cannot mutate. The environment-data credential cannot use
the public API, and Bearer/service identities cannot use System Data.

## Denial semantics

- Missing, malformed, expired, forged, wrong-algorithm, wrong-issuer,
  wrong-audience, wrong-type and subjectless Bearer tokens receive the same
  redacted `401 AUTHENTICATION_REQUIRED`.
- An authenticated identity without route permission receives redacted
  `403 ACCESS_DENIED`.
- Foreign and missing user-owned records receive the same
  `404 Application record not found.` response.
- A service request that omits required owner context receives a non-enumerating
  `400`.
- Token values, JWT/parser diagnostics, owners, record IDs, document IDs and
  raw application paths are not logged.

## Configuration

Production-like startup requires:

```text
AUTH_JWKS_URI
APPLICATION_TRACKER_JWT_ISSUER
APPLICATION_TRACKER_JWT_AUDIENCE
APPLICATION_TRACKER_PRODUCER_TOKEN
APPLICATION_TRACKER_READER_TOKEN
ENVIRONMENT_DATA_TOKEN
```

The repository uses synthetic, clearly test-only values in test resources.
Real credentials belong in the approved runtime secret store. Rotate a
credential if it may have been disclosed; removing it from Git does not revoke
it.

## Consumer rollout

- Job Finder forwards the validated user Bearer token.
- CV/cover-letter and document-generation callers inject the producer token and
  explicit owner context where required.
- Job Matching and Reporting inject the reader token and use owner-scoped read
  routes.
- Infrastructure injects credentials without writing their values into Compose
  files, shell history, CI logs or repository content.

Until those consumers are updated and integration-tested, APP-03 remains a beta
blocker even when the Application Tracker implementation itself is green.
