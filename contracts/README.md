# Application Tracker OpenAPI contract

`openapi.json` is the producer-owned contract generated from the running
Application Tracker Spring application. It is reviewed source, not a generated
binary or a separately edited specification.

## Update process

1. Change the controller, DTO or OpenAPI metadata in this repository.
2. Generate the candidate contract:

   ```bash
   mvn -B -Dtest=OpenApiExportTest \
     -DapplicationTracker.updateContract=true test
   ```

3. Review `contracts/openapi.json` semantically and confirm that security,
   request, response and error changes are intentional.
4. Run `mvn -B clean verify` and the source-only Docker build.
5. Merge the producer change before updating any consumer's pinned contract or
   generated client. Consumers must record the immutable producer commit and
   contract checksum they use.

The default test mode compares the generated runtime document with the tracked
contract as JSON and fails CI on semantic drift.

## Versioning and compatibility

The OpenAPI `info.version` follows semantic versioning:

- patch: documentation or schema clarification that does not change runtime
  compatibility;
- minor: backward-compatible operations, fields or response definitions;
- major: removed or renamed operations/fields, narrowed values, changed
  authentication, or any other consumer-breaking change.

The version `2.1.0` contract retains the owner-scoped list operation's explicit
service-token/Bearer alternatives, success model, stable `401`, `403` and `404`
error models, and guaranteed application fields. It also adds the required
response `version`, optional request `expectedVersion`, and stable `409` model
for status-transition conflicts. These are backwards-compatible additive
fields; consumers can opt into deterministic stale-write protection.
`ApplicationContractPolicyTest` fails closed if either boundary is weakened
accidentally.

Breaking changes require an explicit producer review, a major version, and a
coordinated consumer release. Rollback restores the previous tracked contract
and compatible producer implementation together; consumers continue using
their last reviewed immutable producer revision.
