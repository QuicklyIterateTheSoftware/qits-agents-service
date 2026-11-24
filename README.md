# qits-agents-service

The **control plane of a project's long-running agents** (epic qits-2518). A project declares its
agents as code — an `AgentRoster` in its own `<projectslug>-agents-roster-javalib`, against
[qits-agents-contract](https://github.com/QuicklyIterateTheSoftware/qits-agents-contract-javalib) —
and this service turns each declaration into a row: which generation of it should run, what the
runtime last reported, the agent's credential, the items it holds and the turns waiting for it.

It runs no agent. The runtime (qits-2519) leases a container per RUNNING agent, reads its `launch`,
writes its `report` and hands it its turns; qits-2520's CLI asks `GET /me` who the caller is. The
design pages are on the epic's dossier (`design-and-boundaries`,
`qits-agents-service-skeleton-tables-and-doors`).

The application is `qits-agents`, image `qits/qits-agents`, served at
`https://agents.qits.<domain>/agents`: the API under `/agents/api`, health and the API document
under `/agents/q`. The deployment is `exposure: private`, served by qits-edge-private, because it
calls qits-projects, which is private (qits-1980). Every door takes a bearer.
It reaches qits-projects only through the `--dns` qits-deployments hands a private service
(the private edge's VIP, then an upstream): a task created without one resolves
`projects.qits.<domain>` to the public tunnel address and every call times out (qits-2586).

```
 roster jars (ServiceLoader) ──▶ RosterReconcile ──▶ agent / agent_turn (RECOVERY)
                                   │  slug → id: qits-projects GET /projects/api/projects
                                   │  credential: qits-idp POST/DELETE /idp/api/tokens
                                   ▼
                              ProjectAgentChanged ──▶ qits-events
 runtime (qits-2519) ──launch / report / turns──▶ /agents/api/projects/{project}/agents/{name}/…
```

## The roster reconcile

`control/RosterReconcile` loads every roster on the classpath (`AgentRosters.load`) at boot. A roster
that breaks a rule of the contract throws `InvalidAgentRosterException` and **the process does not
start**, so a broken roster never deploys. The qits project's own roster rides this service as a
compile dependency (`qits-agents-roster`): a roster release is a release of this service.

With `qits.agents.roster-reconcile-at-boot=true` (off in `%test`, where the suites drive it), each
roster is then applied:

1. The project's id from qits-projects by slug (`GET /projects/api/projects`, with the `qits`
   client's bearer). An unknown slug is a WARN and the roster is skipped; an unreachable
   qits-projects changes nothing.
2. Each declared agent, by `hash = AgentRosterDocument.hash(declaration)`:
   - **no row**: insert at generation 1, RUNNING, reported UNKNOWN; no turn (a first start has no
     recovery);
   - **equal hash**: nothing is written, not even `updated_at`;
   - **different hash**: the declaration copied, generation + 1, a `RECOVERY` turn with the
     declaration's `recoveryPrompt`;
   - **RETIRED and declared again**: as a different hash, RUNNING again, and a new token.
3. Every RUNNING agent the roster no longer declares is RETIRED and its token revoked. Rows are
   never deleted; a retired agent's claims and turns stay for the record.
4. A row without a token gets one: `POST {qits.agents.idp.url}/api/tokens`, HTTP Basic as this
   service's own client, `{"contextKind":"project-agent","contextId":"<projectSlug>-<name>",
   "claims":{"project":"<projectId>"},"gitRefs":[]}`. qits-idp answers the fixed subject
   `tok-project-agent-<contextId>` (`tok-project-agent-qits-developer`); commissioning again
   rotates the value. Revocation is `DELETE {idp}/api/tokens/{tokenId}`.

Every write that moves a generation, a desired state or a reported state publishes
`ProjectAgentChanged` from `control/Agents`, in the row's transaction. qits-idp and qits-projects
are called outside it; a failed call is retried by the next run. `reconcile/RosterReconcileTrigger`
re-runs the reconcile every `qits.agents.roster-reconcile-interval` (10 minutes), which writes
nothing over an unchanged roster.

## The data

`domain/src/main/resources/db/agents/migration/V1__agents.sql`, schema `agents`, diagram in
`docs/database/`:

- `agent` — one declared agent: its declaration (`description`, `brief`, `recovery_prompt`,
  `model`, `effort`, `declaration_hash`), `generation`, `desired_state` (RUNNING, RETIRED), the
  runtime's report (`reported_state` UNKNOWN until the first, then STARTING, IDLE, BUSY, EXITED or
  FAILED; `reported_generation`, `session_id`, `reported_detail`, `reported_at`) and its credential
  (`token_id`, `token_subject`, `token_value`). Unique per project and name.
- `agent_claim` — an item the agent holds, open while `released_at` is null; at most one open per
  agent and item. Fed from qits-projects' `EntityAssigned` (below).
- `agent_turn` — the agent's inbox: RECOVERY (from the reconcile), ASSIGNED and COMMENTED (from
  qits-projects' events, keyed by `source_event_id`), acknowledged by `delivered_at`.

## The doors (`/agents/api`)

`{project}` is the project's slug or id, `{name}` the agent's name.

| Door | Answer | Roles |
|---|---|---|
| `GET /projects/{project}/agents` | the agents by name: `name, description, generation, desiredState, reportedState, reportedGeneration, sessionId, reportedAt, declarationHash, tokenSubject, openClaims` | read |
| `GET /projects/{project}/agents/{name}` | one agent, plus `brief, recoveryPrompt, model, effort`; 404 unknown | read |
| `GET …/{name}/claims?state=open\|all` | the claims, newest first; `open` by default; 400 any other state | read |
| `GET …/{name}/turns?pending=true\|false` | the turns, oldest first; pending by default | runner |
| `POST …/{name}/turns/{turnId}/delivered` | 204, idempotent; 404 a turn of another agent | runner |
| `GET …/{name}/launch` | `project, projectId, name, generation, brief, model, effort, sessionId, token, tokenSubject`; 409 when RETIRED or no credential yet | runner |
| `PUT …/{name}/report` `{generation, state, sessionId?, detail?}` | 204; 400 no generation or an unreportable state; 409 a generation newer than the row's | runner |
| `GET /me` | the caller's agent by `token_subject = sub`: `project, projectId, name, generation`; 404 for anyone else | `qits:agent`, `qits:admin-agent`, `qits:desk-admin`, `qits:admin`, `qits:system` |

- **read**: `qits:admin`, `qits:admin-agent`, `qits:desk-admin`, `qits:desk`, `qits:agent`,
  `qits:system`.
- **runner**: `qits:admin`, `qits:desk-admin`, `qits:system`, and **self** — a `qits:agent` caller
  whose `sub` is that row's `token_subject` (`api/SelfOrOperator`). Any other `qits:agent`,
  another agent's credential included, is 403. The agent's harness holds no `qits:system`
  credential, so its in-container daemon calls these doors with the agent's own token.

The token value is served by `launch` and nowhere else.

## Claims and turns from qits-projects' events

Two durable consumptions (`bus/`), both consuming from now (no backfill), applied in `control/`:

- **`EntityAssigned`** (consumer `agents-assignments`, `control/Assignments`). An agent of the
  event's project *matches* a value equal to its name or its token subject. Previous assignee
  matches A and the new one does not: A's open claim on the item is released (`released_at` =
  `assignedAt`, `released_by` = `changedBy`). New assignee matches a RUNNING agent B: B claims the
  item and, unless `changedBy` is B's own subject, gets an ASSIGNED turn
  `(<changedByName> has assigned <qualifiedId> to you)`. A RETIRED agent gets neither.
- **`EntityCommented`** (consumer `agents-comments`, `control/CommentNotices`). Every RUNNING agent
  holding `rootEntityId` (else `entityId`) open, and not the comment's `changedBy`, gets a
  COMMENTED turn `(<who> just commented on <the claim's qualifiedId>)`; `who` is
  `<authorAgentOf>'s agent`, else `authorName`, else `author`.

A redelivered event changes nothing: a claim opened at the event's `assignedAt` is never opened
again, and a turn is unique per agent and `source_event_id`. Nothing new is published.

## The event

| Event | Payload |
|---|---|
| `ProjectAgentChanged` | `projectId, projectSlug, agentName, generation, desiredState, reportedState, declarationHash, changedAt` |

Published on a new agent, a generation change, a desired-state change and every report that
changes `reported_state`. The agent's name is `agentName`, never `name`: that is `QitsEvent.name()`,
the event's own name. `eventId` and `occurredAt` ride the envelope.

## Building

`./mvnw verify` on a clone alone, with the platform Maven repository reachable. No docker: the suite
spawns real postgres binaries (zonky) as a child process. See `AGENTS.md`.
