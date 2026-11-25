-- qits-agents, the evaluation ledger (epic qits-2521): the raw facts the two delivery models —
-- WORKSPACE (per-ticket workspaces the desk dispatches) and PROJECT_AGENT (a roster's long-running
-- agent) — are compared on. The ledger keeps facts only; GET /projects/{project}/evaluation derives
-- every figure from them, so a changed definition needs a code change and no re-collection.
--
-- Fed by the durable eval-* consumptions (bus/Eval*Listener, from 2026-11-25 on, replayed from the
-- log's start), by control/Agents.changed (the project agent's own reported state) and by the usage
-- door (the project agent's transcript usage). Every write is an upsert or an insert keyed by the
-- source, so a replay gives the same ledger.
--
-- eval_item          one EPIC/TICKET: its identity and when each model took it
-- eval_transition    every status an item entered, and when
-- eval_block         every block set on an item (EntityBlockChanged blocked=true)
-- eval_request       a release request's source branches and last state
-- eval_request_state the first time a request entered each state
-- eval_run           a CI run: first seen, terminal time and status, and its release request
-- eval_activity      the busy/idle/down states of an agent: a workspace agent's AgentActivityChanged,
--                    a project agent's reported state
-- eval_exit          a workspace agent's session that ended by itself or was killed
-- eval_wait          a closed wait of either model
-- eval_usage         a workspace agent's transcript usage (AgentUsageRecorded)
-- eval_usage_message a project agent's transcript usage, one row per message (the usage door)

create table eval_item (
  work_id uuid primary key,
  project_id uuid,                        -- null until a qits-projects event names the item
  qualified_id text,                      -- <project slug>-<number>
  archetype text,                         -- EPIC | TICKET | ... (only EPIC and TICKET are evaluated)
  workspace_taken_at timestamptz,         -- the first AgentPlaced naming the item
  agent_taken_at timestamptz,             -- the first EntityAssigned to a roster agent
  agent_name text                         -- that agent
);
create index eval_item_project_ix on eval_item (project_id);

create table eval_transition (
  work_id uuid not null,
  status text not null,
  at timestamptz not null,
  primary key (work_id, status, at)
);

create table eval_block (
  source_event_id uuid primary key,
  work_id uuid not null,
  at timestamptz not null
);
create index eval_block_work_ix on eval_block (work_id);

create table eval_request (
  request_id text primary key,
  project_id uuid,
  qualified_id text,
  repo_name text,
  branches text[] not null,               -- the request's source branches, as last seen
  state text not null,
  changed_at timestamptz not null
);

create table eval_request_state (
  request_id text not null,
  state text not null,
  at timestamptz not null,                -- the first time the request entered the state
  primary key (request_id, state)
);

create table eval_run (
  run_id text primary key,
  project_id uuid,
  request_id text,
  first_at timestamptz not null,          -- the first BuildStatusChanged seen for the run
  terminal_at timestamptz,                -- its terminal one (SUCCESS, FAILED, CANCELLED, CONFIG_ERROR, TIMED_OUT)
  terminal_status text
);
create index eval_run_request_ix on eval_run (request_id);

create table eval_activity (
  source_event_id uuid primary key,
  model text not null,                    -- WORKSPACE | PROJECT_AGENT
  project_id uuid,
  subject text not null,                  -- the workspace agent's id, or the project agent's name
  work_id uuid,
  state text not null,                    -- IDLE | BUSY | WAITING | ENDED, or a project agent's reported state
  at timestamptz not null
);
create index eval_activity_subject_ix on eval_activity (model, subject, at);

create table eval_exit (
  source_event_id uuid primary key,
  model text not null,
  subject text not null,
  work_id uuid,
  at timestamptz not null,
  detail text
);

create table eval_wait (
  wait_id text primary key,
  model text not null,
  work_id uuid,
  state text not null,                    -- MATCHED | TIMED_OUT | BROKEN | CANCELLED
  closed_at timestamptz not null
);

create table eval_usage (
  source_event_id uuid primary key,
  model text not null,
  project_id uuid,
  work_id uuid,
  session_id text not null,
  claude_model text not null,
  messages int not null,
  input_tokens bigint not null,
  output_tokens bigint not null,
  cache_creation_tokens bigint not null,
  cache_read_tokens bigint not null,
  at timestamptz not null
);

create table eval_usage_message (
  session_id text not null,
  message_id text not null,
  agent_id uuid not null,                 -- agent.id
  work_qualified_id text,                 -- null for the overseer's own messages
  claude_model text not null,
  input_tokens bigint not null,
  output_tokens bigint not null,
  cache_creation_tokens bigint not null,
  cache_read_tokens bigint not null,
  at timestamptz not null,
  primary key (session_id, message_id)
);
