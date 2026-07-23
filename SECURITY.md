# Security policy

This repository is not yet approved to own private-beta application history.

Report suspected vulnerabilities privately to the repository owner. Do not put
user identifiers, application details, employer information, document identifiers,
interview/offer data, credentials or exploit details in ordinary issues or logs.

The public API now validates the platform RS256 access-token signature, issuer,
audience, expiry, nonblank subject and `token_type=access`. User ownership comes
only from `sub`; caller headers, path values and body `userId` cannot override
it. Repository reads and mutations include that owner, and missing versus
foreign records return the same redacted denial.

Backend integration is not trusted by network reachability. Producer, reader
and environment-data identities are distinct runtime secrets with separate
route permissions. Never forward a browser-provided service token. See
[`docs/SECURITY_BOUNDARY.md`](docs/SECURITY_BOUNDARY.md) for the exact contract
and outstanding consumer rollout.

Durable storage, lifecycle/concurrency controls and cross-service consistency
remain separate beta blockers. This change is an authenticated ownership
boundary, not a general security certification.

Rotate any exposed credential in the relevant system/provider. Removing a Git
commit does not revoke it.
