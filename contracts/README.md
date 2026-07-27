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

The contract is version `3.0.0`. It retains the explicitly defined owner-scoped
list boundary and lifecycle concurrency metadata from `2.1.0`, and replaces
raw-entity, owner-wide System Data operations with a constrained versioned seed
request and scenario-scoped seed/reset/verify operations.

The owner-scoped list operation explicitly defines its service-token/Bearer
alternatives, success model, stable `401`, `403` and `404` error models, and
guaranteed application fields. Status mutation publishes response `version`,
optional request `expectedVersion`, and a stable `409` model. The System Data
route replacement is intentionally breaking and therefore requires the major
version. `ApplicationContractPolicyTest` fails closed if any of these
boundaries is weakened accidentally.

Breaking changes require an explicit producer review, a major version, and a
coordinated consumer release. Rollback restores the previous tracked contract
and compatible producer implementation together; consumers continue using
their last reviewed immutable producer revision.
