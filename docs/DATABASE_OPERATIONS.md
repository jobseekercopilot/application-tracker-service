# Application Tracker database operations

## Storage boundary

Application Tracker uses a dedicated PostgreSQL database in every non-test
environment. H2 is a test-scoped dependency, its console is disabled, and it is
never valid persistence or recovery evidence. Flyway owns schema changes;
Hibernate validates the migrated schema and is not allowed to create, update or
drop it.

The intended AWS production binding is a private managed PostgreSQL service such
as RDS or Aurora PostgreSQL. The repository can require configuration and
retain executable migration/recovery evidence, but it cannot prove that an AWS
control was applied. Infrastructure must retain the deployment evidence.

## Required runtime settings

The service refuses to migrate when the production safety check is enabled
unless all of these conditions hold:

- the JDBC target is PostgreSQL and transport uses `sslmode=verify-full`;
- a dedicated non-superuser role and a password of at least 32 characters are
  injected at runtime;
- Flyway is enabled, Flyway clean is disabled and Hibernate is validate-only;
- H2 console access and SQL/bind-value logging are disabled;
- managed encryption at rest and encrypted backups are declared; and
- non-secret managed key references are supplied for the database and backups.

Required variables:

| Variable | Purpose |
| --- | --- |
| `APPLICATION_TRACKER_DATABASE_URL` | PostgreSQL JDBC URL |
| `APPLICATION_TRACKER_DATABASE_USERNAME` | Dedicated service role |
| `APPLICATION_TRACKER_DATABASE_PASSWORD` | Runtime-injected secret |
| `APPLICATION_TRACKER_DATABASE_SSL_MODE` | `verify-full` in production |
| `APPLICATION_TRACKER_DATABASE_ENCRYPTION_AT_REST_ENABLED` | Production attestation |
| `APPLICATION_TRACKER_DATABASE_ENCRYPTION_KEY_REFERENCE` | Non-secret managed key identifier |
| `APPLICATION_TRACKER_DATABASE_BACKUP_ENCRYPTION_ENABLED` | Production backup attestation |
| `APPLICATION_TRACKER_DATABASE_BACKUP_KEY_REFERENCE` | Non-secret backup key identifier |

Local and E2E Compose may set
`APPLICATION_TRACKER_DATABASE_PRODUCTION_SAFETY_CHECK=false` only for isolated
synthetic evidence. They must still use PostgreSQL and a dedicated named volume.
That switch is forbidden in a production deployment.

## Migration and rollback policy

1. Confirm the latest encrypted backup and restore drill meet the recovery
   objectives below.
2. Take and identify an encrypted pre-release recovery point.
3. Review every new
   `src/main/resources/db/migration/common/V*__*.sql` file. Never edit an
   applied migration.
4. Before V6, run the duplicate preflight against the private database:

   ```sql
   SELECT user_id, canonical_job_id, COUNT(*)
   FROM application_records
   WHERE canonical_job_id IS NOT NULL
     AND fixture_scenario_id IS NULL
   GROUP BY user_id, canonical_job_id
   HAVING COUNT(*) > 1;
   ```

   Stop if any row is returned. APP-05 deliberately does not choose or delete a
   user's duplicate history. Resolve each duplicate through an approved,
   backed-up data decision before retrying the migration.
5. Run `mvn -B --no-transfer-progress clean verify`; the verification includes
   real PostgreSQL migration, JPA mapping, restart, backup and restore evidence.
6. Deploy one instance, allow Flyway to migrate, then require health and
   migration validation before the rollout continues.

Migrations are forward-only. V2, V3, V4 and V5 are additive and the recovery test
proves that a V1 reader can still select its original columns after upgrade. A
later incompatible change must be rolled forward with a corrective migration.
If a safe roll-forward is impossible, stop writes and restore the encrypted
pre-release database into a new target; never use `flyway clean` or an ad-hoc
down migration.

V2 backfills inherited job identity without guessing the original provider:
`provider=LEGACY`, while canonical and external job IDs retain the inherited
job ID. V3 reserves nullable `fixture_scenario_id`; existing/ordinary rows stay
`NULL`, and only scenario rows participate in its partial owner/scenario index.
V4 adds nullable immutable document-version metadata and application-used
references. Existing rows remain readable and are not assigned invented
document versions or checksums.
V5 adds a non-negative `record_version`, backfilled to zero, for JPA optimistic
locking. It does not change the columns consumed by pre-upgrade readers.
V6 records creation provenance and owner-scoped idempotency, relaxes current
document IDs for approved manual/external applications, and adds partial unique
indexes for owner/idempotency and non-fixture owner/canonical-job identity.
Inherited rows are marked `GENERATED`; their unknown command key/fingerprint
remain `NULL`. V6 fails rather than silently choosing among pre-existing
owner/canonical-job duplicates.
V7 adds `application_events`, one event per resulting application record
version, chronological owner/application indexes and a PostgreSQL trigger that
rejects event updates and deletes. Existing rows receive one explicit
`LEGACY_SNAPSHOT` of their known current status/version; the migration does not
invent prior transitions. Event rows do not cascade with current-row deletion;
APP-09 remains responsible for approved retention and privacy handling.
V8 adds durable generated-withdrawal workflow state and locks an application
while cross-service cleanup is incomplete. V9 extends the same workflow model
for document replacement with immutable source/replacement identity and request
hashes. V10 adds one application/document-reference reconciliation row per
current application, backfills inherited rows as `PENDING`, cascades that
derived health row when its current application is removed, and extends the
immutable event constraints for system-attributed safe repairs.
V14 adds explicit `SELECTED`, `OMITTED` and legacy `UNKNOWN` application-used
slot states plus the durable owner-scoped apply-command ledger. It also permits
one immutable event of each type for a resulting record version so the
content-freeze fact and lifecycle transition are independently auditable.
V15 adds one owner/document availability projection with ordered lifecycle
time, stable unavailable reason and terminal `PURGED` state. Purge handling
scrubs complete hashes and evidence details from matching current and frozen
references while retaining exact family/version identity.

## Recovery objectives

Private-beta operational targets are:

- **RPO:** no more than 15 minutes of committed application-record changes;
- **RTO:** restore validated read/write service within 60 minutes; and
- **backup retention:** at least 7 days of encrypted recovery points, subject
  to the final APP-09 privacy/retention policy.

The AWS design must enable encrypted automated backups and point-in-time
recovery at a cadence that meets the RPO. Monitoring must alert before backup
or restore-drill age breaches these targets.

## Backup and restore evidence

`PostgresApplicationRecoveryIntegrationTest` uses only synthetic data and:

- creates the inherited V1 schema and record;
- rejects an invalid status and invalid credentials;
- upgrades through V2-V14, including guarded creation, append-only history,
  recoverable document workflows and reconciliation state;
- proves inherited rows receive no invented immutable document reference;
- proves inherited rows receive version zero;
- proves inherited rows receive `GENERATED` provenance without invented
  idempotency facts;
- proves ordinary data is not classified as fixture data;
- proves inherited records receive one truthful legacy snapshot;
- proves inherited records receive one pending reconciliation record without
  invented document metadata;
- repeats the startup migration path against the same database;
- creates a PostgreSQL custom-format backup and restores it to a fresh database;
- validates Flyway history and exact representative fields after restore;
- proves the pre-upgrade reader remains compatible;
- proves event update/delete is rejected before and after restore; and
- deletes the restored current record while preserving its event evidence and
  cascading only its derived reconciliation row.

`PostgresJpaSchemaIntegrationTest` proves Flyway's final PostgreSQL schema
matches the JPA entity, preserves service-assigned UUIDs, stores an approved
manual application without documents, makes simultaneous identical create
commands return one application identity and one creation event, reconciles
current state with the latest event, rejects event rewrites/deletion, and
rejects the second of two writers that loaded the same record version.

This local drill does not constitute AWS backup, encryption or disaster-recovery
evidence. Before beta enablement, an operator must restore an approved
synthetic canary into an isolated private database, validate Flyway, prove the
canary through the owner-scoped service path, record duration and recovery-point
metadata without personal data, remove the isolated restore, and retain the
redacted evidence under the Infrastructure recovery issue.

## Incident constraints

- Stop writes before point-in-time recovery or full restore.
- Restore into a new database; do not overwrite the only failed copy.
- Preserve failed storage and audit evidence until incident ownership approves
  disposal.
- Never log credentials, JDBC URLs containing credentials, SQL bind values,
  job/application text or user identifiers.
- Rotate a database credential by updating the managed secret, rolling the
  service, proving health, and revoking the old role/secret only after success.
