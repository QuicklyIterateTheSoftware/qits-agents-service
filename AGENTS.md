# qits-agents — working notes

Read `README.md` first: it is the design. This file is the conventions on top of it. Grow it with
the code.

## The rule that shapes everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior
`mvn install` elsewhere. `./mvnw verify` is the gate. The store is postgres and costs no docker:
`service/src/test/.../testdb/EmbeddedPg` spawns zonky's real binaries as a child process. **Never
Testcontainers, never a Quarkus dev service.**

The one address that is an exception is the platform Maven repository (`<repositories>` in the root
pom), where the qits-* jars live. Their versions are pinned in the root pom, exactly, and moved by
qits-maintenance's bumps — never by hand.

## Layout

- `domain/` — entities (`entity`), the agent reads and writes, the roster reconcile and the
  claims and turns from qits-projects' events (`control`: `Assignments`, `CommentNotices`), the
  published event and the consumer copies of `EntityAssigned` and `EntityCommented` (`events`).
  Owns the `agents` datasource, persistence unit and the Flyway lineage at `db/agents/migration`
  (schema `agents`). `control/ProjectDirectory` and
  `control/AgentCredentials` are the reconcile's two outbound dependencies, as interfaces.
- `service/` — the deployable: the REST surface (`api`), the two durable listeners (`bus`), the
  qits-projects client behind `ProjectDirectory` (`projects`), the qits-idp client behind
  `AgentCredentials` (`idp`), the periodic reconcile (`reconcile`), and every database-backed test.
- `service/.../eu/wohlben/qits/agents/runtime` (qits-2519): the AGENT leases at qits-system
  (`AgentLeases`, `AgentLeaseSweep`, `AgentSpecs`, `AgentSpecRoll`, the REST client `SystemLeasesApi`
  behind `SystemLeasesClient`). It reads and writes agents through `control/Agents` (`running()`,
  `byId()`, `retiredWithLease()`, `storeLease()`, `clearLease()`), never publishes
  `ProjectAgentChanged` itself and never moves a generation or queues a turn. It sits outside
  `eu.wohlben.qits.agents.service`, so every ArchUnit sweep here (`ArchRulesTest`,
  `EntityExposureTest`, `OutboundCallGuardTest`) names both packages.

## Conventions

- **Agent rows are read and written through `control/Agents`**, and `ProjectAgentChanged` is
  published from its one private `changed` method, inside the write transaction, after the flush.
  No payload field may be called `name` (it is `QitsEvent.name()`): the agent's is `agentName`.
- **The listeners are thin**: decode with `CanonicalJson.payloadTo`, hand to `control`, WARN and
  return on a payload that will not bind (poison), let a database failure throw (the event stays
  owed). Their `consumerId`s (`agents-assignments`, `agents-comments`) are storage keys: never
  rename them. Every listener is passed to `EventstreamPactTest`, every consumed record is listed in
  `api/ApiWireReflection`. Redelivery is idempotent by a read first, never by catching the unique
  index's violation (which would poison the claiming transaction).
- **The reconcile is idempotent**: an unchanged roster writes nothing, not even `updated_at`
  (`RosterReconcileTest.anUnchangedRosterWritesNothingAtAll`). qits-idp and qits-projects are
  called outside a row's transaction; a failure is retried by the next run.
- **Roles** are spelled out in each `@RolesAllowed` (see `api/Roles`): the READ set at class level,
  the runner set on each runner door, which REPLACES it, then `api/SelfOrOperator`. Where
  `qits:admin-agent` is admitted, so is `qits:desk-admin` (`DeskAdminParityTest`). `RoleMatrixTest`
  pins the matrix, the foreign-agent 403 included.
- **The caller** is the token's `sub` (`api/Callers`), never the principal name.
- **The token value** leaves this service in `launch` and in the agent's own lease spec
  (`QITS_TOKEN`, to qits-system) and nowhere else; never log it, nor a spec (`LeaseSpec.toString`
  prints the env's names only).
- **Outbound calls** go through a MicroProfile REST client whose every method is a row of that
  provider's consumer pact (`contracts/consumer/OutboundCallGuardTest`); the bearer to
  qits-projects is the `qits` client's (`projects/ServiceBearer`, the one class allowed an
  `OidcClient`); qits-idp is asked with this service's client id and secret as HTTP Basic.
- **Every served type** is a nested record of `api/AgentsWire` and is listed in
  `api/ApiWireReflection` (`ApiWireReflectionTest`), as are the outbound clients' records.
- **Tests with several callers** name each by its `Authorization` value
  (`support/ContractCallers`): `caller(subject, roles…)` for a plain caller, `bearer(value,
  subject, roles…)` for a token (an agent's own credential). A test with one caller uses
  `@TestSecurity`. qits-projects and qits-idp are stubbed by `support/StubDirectory` and
  `support/StubCredentials`, installed with `QuarkusMock`; qits-system's lease doors by
  `support/FakeSystem`.
- **`ProjectAgentChanged` in process**: `Agents.changed` fires it as a CDI event too, and
  `AgentLeaseSweep` observes it `AFTER_SUCCESS` and sweeps on its own thread. `%test` turns the
  runtime's triggers off (`qits.agents.runtime.enabled`); a suite drives `sweep()` or flips
  `enabledOverride`.

## Generated files, and how to regenerate each

Every one of these compares by default and fails with a diff; `-Dgolden.update=true` rewrites.

| File | Written by |
|---|---|
| `docs/openapi.yml` | `OpenApiSchemaExportTest` (always rewrites; the diff is the review) |
| `docs/entities.json` | `EntityExposureTest` |
| `golden-masters/**` | `contracts/GoldenMasterRecordingTest` |
| `pacts/*_qits-idp-service.json` | `contracts/consumer/IdpPactTest` (rows run by `IdpTokenMintPactTest`, `IdpTokensPactTest`) |
| `pacts/*_qits-projects-service.json` | `contracts/consumer/ProjectsPactTest` |
| `pacts/*_qits-system-service.json` | `contracts/consumer/SystemPactTest` |
| `pacts/*_qits-events-service.json` | `EventstreamPactTest` |
| `docs/database/agents.md` | `qits database diagram` (below) |

The entity diagram:

    ./mvnw -q -Dmaven.test.skip=true test-compile dependency:build-classpath \
      -Dmdep.outputFile=target/qits-classpath.txt -DincludeScope=runtime
    qits database diagram

A new door needs a golden master in the same commit: the release gate fails any operation of
`docs/openapi.yml` that `golden-masters/index.json` does not cover. A new provider state needs its
`@State` method on `ConsumerPactVerificationTest` (`ProviderStateCoverageTest`).

**A pending pact row.** `IdpPactTest.COMMISSION` names qits-idp's state `a project agent's token
being commissioned`, which no published `qits-idp-golden-masters` records yet (qits-2559). Until the
pin moves past one that does, the row is left out of the pact file, `IdpTokensPactTest` skips it,
and `OutboundCallGuardTest` accepts `IdpTokensApi.commissionToken` by its operationId. When the
bump lands, regenerate the pact with `-Dgolden.update=true`.

## The userflow catalogue

`stories/**IT` boot the PACKAGED service (`-DskipITs=false`) against `idp.MockIdp` with the OIDC
tenant on and the boot reconcile off, and write their reports under `service/target/userstories/`.
The java-service release recipe requires that directory and publishes it as `@userflows/qits-agents`.

## What the JVM suite cannot see

Three things broke only once deployed (qits-2518), each green in `./mvnw verify`. Keep the guards:

- **The roster in the native image.** Quarkus switches GraalVM's service-loader feature off, so
  `AgentRosters.load` found nothing in 2026.1123.194720. `quarkus.native.auto-service-loader-registration=true`
  fixes it; `RosterReadiness` (`agent-rosters`) is DOWN when no roster loads, and `AgentRuntimeIT`
  asserts it against the packaged artifact.
- **`exposure: private`.** qits-projects is private (qits-1980); a public deployment timed out on
  `projects.qits.<domain>` → 100.96.0.1. Never move this service back to public while it calls a
  private peer.
- **Listener writes in their own transaction.** qits-eventstream calls a durable listener inside
  its claim transaction on the `eventstream` datasource; joining it from the `agents` datasource
  failed every event. `Assignments` and `CommentNotices` are `REQUIRES_NEW`;
  `ClaimsAndTurnsFromEventsTest.aListenerCalledInsideTheClaimingTransactionStillWrites` reproduces
  the outer transaction. Any new listener follows the same rule.
