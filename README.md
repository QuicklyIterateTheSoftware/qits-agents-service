# qits-agents-service

The **control plane of a project's long-running agents** (epic qits-2518). A project declares its
agents as code — an `AgentRoster` in its own `<projectslug>-agents-roster-javalib`, against
[qits-agents-contract](https://github.com/QuicklyIterateTheSoftware/qits-agents-contract-javalib) —
and this service turns each declaration into a row: which generation of it should run, what the
runtime last reported, the agent's credential, the items it holds and the turns waiting for it.

It runs no agent itself: its runtime (`eu.wohlben.qits.agents.runtime`, qits-2519, below) leases a
container per RUNNING agent from qits-system, and the daemon in it reads its `launch`, writes its
`report` and takes its turns; qits-2520's CLI asks `GET /me` who the caller is. The
design pages are on the epic's dossier (`design-and-boundaries`,
`qits-agents-service-skeleton-tables-and-doors`).

The application is `qits-agents`, image `qits/qits-agents`, served at
`https://agents.qits.<domain>/agents`: the API under `/agents/api`, health and the API document
under `/agents/q`. The deployment is `exposure: private`, served by qits-edge-private, because it
calls qits-projects, which is private (qits-1980). Every door takes a bearer.
It reaches qits-projects only through the `--dns` qits-deployments hands a private service
(the private edge's VIP, then an upstream): a task created without one resolves
`projects.qits.<domain>` to the public tunnel address 100.96.0.1 (qits-2273), unreachable from
qits-private, and every call times out (qits-2586).
qits-deployments logs `dev-qits-agents resolves through the private edge: resolvers [...]` when it
hands them, and `... gets no private-edge resolver: <why>` when it cannot.

```
 roster jars (ServiceLoader) ──▶ RosterReconcile ──▶ agent / agent_turn (RECOVERY)
                                   │  slug → id: qits-projects GET /projects/api/projects
                                   │  credential: qits-idp POST/PUT/DELETE /idp/api/tokens
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
   "claims":{"project":"<projectId>"},"gitRefs":[<refs>]}`. qits-idp answers the fixed subject
   `tok-project-agent-<contextId>` (`tok-project-agent-qits-developer`); commissioning again
   rotates the value. Revocation is `DELETE {idp}/api/tokens/{tokenId}`.
5. **The Git refs follow the claims** (qits-2705). `<refs>` is what the agent's open claims grant
   (`control/AgentGitRefs`): a TICKET `refs/heads/ticket/<qid>` and `refs/heads/ticket/<qid>-*`, an
   EPIC the same under `epic/`, anything else nothing; sorted, at most 500. Every run then replaces
   the list of every other RUNNING agent holding a token, `PUT {idp}/api/tokens/{tokenId}/git-refs`
   `{"gitRefs":[…]}` — the backfill, and the retry of a replace an assignment could not make (a
   refused one leaves the run incomplete). Nothing is stored for it: the replace is idempotent.

Every write that moves a generation, a desired state or a reported state publishes
`ProjectAgentChanged` from `control/Agents`, in the row's transaction. qits-idp and qits-projects
are called outside it; a failed call is retried by the next run. `reconcile/RosterReconcileTrigger`
re-runs the reconcile every `qits.agents.roster-reconcile-interval` (10 minutes), which writes
nothing over an unchanged roster.

## The runtime: one AGENT lease per RUNNING agent (qits-2519)

qits-system maps tenants by client id, and this service's `qits` client is `qits-agents`
(`qits.system.targets.tenants.qits-agents=AGENT`), so the agents' lease client lives here. It reads
and writes rows only through `control/Agents`, never moves a generation and never queues a turn.

- **`AgentLeases.apply`**: a RUNNING agent with a token and no lease asks for one, `POST
  {qits.agents.system.url}/system/api/leases` `{slotType: AGENT, ownerRef: <agentId>, workKey:
  agent-<agentId>, priority: 0, spec}` with the `qits` client's bearer, and stores the id in
  `agent.lease_id`. A `LEASE_EXISTS` refusal adopts this tenant's live lease for the agent from `GET
  /system/api/leases` (a crash between the ask and the store).
- **`AgentLeases.observe`**: `GET …/leases/{id}`; EXITED or FAILED is ended and asked for again,
  ENDED or gone is forgotten and asked for again.
- **`AgentLeases.end`**: `DELETE …/leases/{id}`, then `lease_id` cleared. The work volume is never
  touched; the AGENT policy keeps it.
- **`AgentSpecRoll`**: a live lease whose `specHash` is not `AgentSpecs.hash(compose(agent))` gets
  `PUT …/leases/{id}/spec`; the container restarts on the same volume and the daemon resumes the same
  generation. qits-system hashes the spec it is sent exactly as `AgentSpecs.hash` does (sha256 of
  the canonical JSON); its answers redact env values, so the hash is how a rotated token is seen.
- **`AgentLeaseSweep`**: every `qits.agents.runtime.sweep-interval` (30 s) and at once after every
  committed `ProjectAgentChanged` (a CDI event fired beside the bus publish in `Agents.changed`): the
  RUNNING rows applied or observed and rolled, the RETIRED rows holding a lease ended. Idempotent.

**The spec** (`AgentSpecs.compose`): image `registry.qits.<domain>/qits/agent:<AgentImage.VERSION>`
from the pinned `qits-agents-daemon-protocol`; user `1000`; limits the front desk's (memory and
swap `4g`, `oomScoreAdj 800`); and exactly this env:

| Name | Value |
|---|---|
| `QITS_AGENTS_DAEMON_PROJECT` | the project slug |
| `QITS_AGENTS_DAEMON_AGENT_NAME` | the agent's name |
| `QITS_AGENTS_DAEMON_WRAPPER` | `<slug>-<slug>`, the wrapper repository |
| `QITS_AGENTS_DAEMON_GIT_BASE` | `https://githost.qits.<domain>/git/<slug>` |
| `QITS_AGENTS_DAEMON_SERVICE_URL` | `https://agents.qits.<domain>/agents` |
| `QITS_TOKEN` | the row's `token_value` |
| `QITS_DOMAIN` | the domain |
| `GIT_CONFIG_GLOBAL` | `/etc/qits-gitconfig` |
| `QITS_GIT_AUTH_HOST` | `githost.qits.<domain>` |

Never `HOME`, `CLAUDE_CONFIG_DIR` or `BUILDKIT_HOST`: the policy owns them and qits-system refuses a
spec naming one.

## The data

`domain/src/main/resources/db/agents/migration/V1__agents.sql` to `V4__evaluation.sql`, schema
`agents`, diagram in `docs/database/`:

- `agent` — one declared agent: its declaration (`description`, `brief`, `recovery_prompt`,
  `model`, `effort`, `declaration_hash`), `generation`, `desired_state` (RUNNING, RETIRED), the
  runtime's report (`reported_state` UNKNOWN until the first, then STARTING, IDLE, BUSY, EXITED or
  FAILED; `reported_generation`, `session_id`, `reported_detail`, `reported_at`) and its credential
  (`token_id`, `token_subject`, `token_value`); `lease_id` (V2), the AGENT lease the runtime last
  asked for. Unique per project and name.
- `agent_claim` — an item the agent holds, open while `released_at` is null; at most one open per
  agent and item. Fed from qits-projects' `EntityAssigned` (below).
- `agent_turn` — the agent's inbox: RECOVERY (from the reconcile), ASSIGNED and COMMENTED (from
  qits-projects' events, keyed by `source_event_id`), acknowledged by `delivered_at`.
- `eval_*` (V4) — the evaluation ledger, below: raw facts only, every row keyed by its source.

## The doors (`/agents/api`)

`{project}` is the project's slug or id, `{name}` the agent's name.

| Door | Answer | Roles |
|---|---|---|
| `GET /projects/{project}/agents` | the agents by name: `name, description, generation, desiredState, reportedState, reportedGeneration, sessionId, reportedAt, reportedDetail, declarationHash, tokenSubject, openClaims` | read |
| `GET /projects/{project}/agents/{name}` | one agent, plus `brief, recoveryPrompt, model, effort`; 404 unknown | read |
| `GET …/{name}/claims?state=open\|all` | the claims, newest first; `open` by default; 400 any other state | read |
| `GET …/{name}/turns?pending=true\|false` | the turns, oldest first; pending by default | runner |
| `POST …/{name}/turns/{turnId}/delivered` | 204, idempotent; 404 a turn of another agent | runner |
| `GET …/{name}/launch` | `project, projectId, name, generation, brief, model, effort, sessionId, token, tokenSubject`; 409 when RETIRED or no credential yet | runner |
| `PUT …/{name}/report` `{generation, state, sessionId?, detail?}` | 204; 400 no generation or an unreportable state; 409 a generation newer than the row's | runner |
| `POST …/{name}/usage` `{sessionId, messages: [{messageId, workQualifiedId?, model, inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens, at}]}` | 204; each message recorded once per session (`eval_usage_message`, a resend is absorbed); 400 no session, no list, more than 500 messages, or a message without id, model or time | runner |
| `GET /projects/{project}/evaluation?since=&until=` | the evaluation report (below), JSON, or Markdown for `Accept: text/markdown`; 400 an unreadable instant or `until` not after `since`; 404 unknown project | read |
| `GET /me` | the caller's agent by `token_subject = sub`: `project, projectId, name, generation`; 404 for anyone else | `qits:agent`, `qits:admin-agent`, `qits:desk-admin`, `qits:admin`, `qits:system` |

- **read**: `qits:admin`, `qits:admin-agent`, `qits:desk-admin`, `qits:desk`, `qits:agent`,
  `qits:system`.
- **runner**: `qits:admin`, `qits:desk-admin`, `qits:system`, and **self** — a `qits:agent` caller
  whose `sub` is that row's `token_subject` (`api/SelfOrOperator`). Any other `qits:agent`,
  another agent's credential included, is 403. The agent's harness holds no `qits:system`
  credential, so its in-container daemon calls these doors with the agent's own token.

The token value is served by `launch` and nowhere else; besides that door it leaves this service
only in the agent's own lease spec, to qits-system.

## Claims and turns from qits-projects' events

Two durable consumptions (`bus/`), both consuming from now (no backfill), applied in `control/`:

- **`EntityAssigned`** (consumer `agents-assignments`, `control/Assignments`). An agent of the
  event's project *matches* a value equal to its name or its token subject. Previous assignee
  matches A and the new one does not: A's open claim on the item is released (`released_at` =
  `assignedAt`, `released_by` = `changedBy`). New assignee matches a RUNNING agent B: B claims the
  item and, unless `changedBy` is B's own subject, gets an ASSIGNED turn
  `(<changedByName> has assigned <qualifiedId> to you)`. A RETIRED agent gets neither. Once the
  claims have committed, every agent whose open claims moved and that holds a token gets its Git
  refs replaced at qits-idp (above, qits-2705); a failed replace is a WARN, the event is consumed
  anyway, and the next reconcile states the list again.
- **`EntityCommented`** (consumer `agents-comments`, `control/CommentNotices`). Every RUNNING agent
  holding `rootEntityId` (else `entityId`) open, and not the comment's `changedBy`, gets a
  COMMENTED turn `(<who> just commented on <the claim's qualifiedId>)`; `who` is
  `<authorAgentOf>'s agent`, else `authorName`, else `author`.

A redelivered event changes nothing: a claim opened at the event's `assignedAt` is never opened
again, and a turn is unique per agent and `source_event_id`. Nothing new is published.

## The evaluation: WORKSPACE against PROJECT_AGENT (epic qits-2521)

The two delivery models — per-ticket workspaces the desk dispatches (`WORKSPACE`) and a roster's
long-running agent (`PROJECT_AGENT`) — compared on the figures of the epic's page "Comparison
criteria". Read it with

    curl -H "Authorization: Bearer $QITS_TOKEN" -H 'Accept: text/markdown' \
      "https://agents.qits.$QITS_DOMAIN/agents/api/projects/qits/evaluation"

(`?since=<instant>&until=<instant>`; `since` defaults to `qits.agents.evaluation.since`,
`2026-11-25T00:00:00Z`, `until` to now; drop the `Accept` header for JSON).

**The ledger** keeps raw facts, and `control/Evaluation` derives every figure at read time, so a
changed definition needs a code change and no re-collection. Its writers (`control/EvaluationLedger`):

| consumerId | Event | Writes |
|---|---|---|
| `eval-transitions` | `EntityTransitioned` (projects) | `eval_item` (identity, `<slug>-<number>`), `eval_transition` per EPIC/TICKET status entered |
| `eval-assignments` | `EntityAssigned` (projects) | `eval_item.agent_taken_at` when the assignee is a roster agent (name or token subject) |
| `eval-blocks` | `EntityBlockChanged` (projects) | `eval_block` per `blocked=true` |
| `eval-requests` | `ReleaseRequestStateChanged` (projects) | `eval_request` (branches, last state), `eval_request_state` (first entry per state) |
| `eval-builds` | `BuildStatusChanged` (qits-ci) | `eval_run` (first event, terminal event and status, request) |
| `eval-placements` | `AgentPlaced` (workspaces) | `eval_item.workspace_taken_at` |
| `eval-activity` | `AgentActivityChanged` (workspaces) | `eval_activity`, model WORKSPACE |
| `eval-exits` | `AgentHarnessExited` (workspaces) | `eval_exit` when `stoppedBy` is SELF or KILLED |
| `eval-waits` | `AgentWaitClosed`, `ProjectAgentWaitClosed` (workspaces) | `eval_wait` |
| `eval-usage` | `AgentUsageRecorded` (workspaces) | `eval_usage` |
| `eval-project-agents` | `ProjectAgentChanged` (this service's own) | `eval_activity`, model PROJECT_AGENT |

Every one replays from the epoch (the backfill) and settles an event older than
`qits.agents.evaluation.since` without a write. The project agent's own state is consumed off the
bus too (`eval-project-agents`, one row per `ProjectAgentChanged`), so the window before a deploy is
replayed like the rest; its usage arrives through `POST …/usage` into `eval_usage_message`.

**The rules** the door applies (see `control/Evaluation`): an EPIC or TICKET is WORKSPACE's when
placed, PROJECT_AGENT's when assigned to a roster agent, at or after `since`; both is BOTH,
listed as `doubleWorked` and left out of both models. A request is an item's by a branch
`ticket/<qid>` or `epic/<qid>` (or that plus `-<name>`); a terminal CI run of the request gives each
attributed item `1/n` of the run and its seconds, `n` being all its branches. Nothing after `until`
counts. A project agent is idle only while it holds an open claim. Percentiles are nearest-rank,
null without data; per-verified figures are null with nothing verified; the project agent's
overhead (usage with no item) counts in its `tokensPerVerified`.

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
