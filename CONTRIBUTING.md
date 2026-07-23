# Contributing

This is a private, proprietary repository.

1. Start from `develop` and use a focused branch for an approved issue.
2. Keep one issue and one concern per pull request.
3. Never commit credentials, real applicant data, generated documents or runtime state.
4. Every application read or mutation requires authenticated ownership and cross-user tests.
5. Preserve application/document consistency with explicit failure and retry behavior.
6. Run `mvn -B clean verify` and relevant migration/security checks before review.
7. Open a pull request into `develop`; do not push implementation work directly.

Report security concerns using `SECURITY.md`.
