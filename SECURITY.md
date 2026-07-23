# Security policy

This repository is not ready to own private-beta application history.

Report suspected vulnerabilities privately to the repository owner. Do not put
user identifiers, application details, employer information, document identifiers,
interview/offer data, credentials or exploit details in ordinary issues or logs.

The audit found unauthenticated caller-controlled ownership, unrestricted
record/document lookups and mutations, unsafe environment-data controls,
non-durable storage and cross-service consistency gaps. Do not treat direct
service access as trusted until authenticated user/service boundaries and
cross-user tests are complete.

Rotate any exposed credential in the relevant system/provider. Removing a Git
commit does not revoke it.
