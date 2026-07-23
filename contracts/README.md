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
