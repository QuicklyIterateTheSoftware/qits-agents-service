-- qits-agents, the first lineage (epic qits-2518). Schema `agents` (quarkus.flyway.agents.schemas).
--
-- agent        one row per declared agent of a project's roster. Never deleted: an agent its roster
--              no longer declares is RETIRED, and its claims and turns stay for the record.
-- agent_claim  the work items an agent holds; open while released_at is null.
-- agent_turn   the agent's inbox: the turns the runtime hands it, acknowledged by delivered_at.

create table agent (
  id uuid primary key,
  project_id uuid not null,
  project_slug varchar(63) not null,
  name varchar(32) not null,
  description text not null,
  brief text not null,
  recovery_prompt text not null,
  model varchar(64),
  effort varchar(32),
  declaration_hash char(64) not null,
  generation integer not null,            -- 1 at first sight, +1 per declaration_hash change
  desired_state varchar(16) not null,     -- RUNNING | RETIRED
  reported_state varchar(16) not null,    -- UNKNOWN until the runtime reports; then STARTING | IDLE | BUSY | EXITED | FAILED
  reported_generation integer,            -- the generation the runtime says it runs
  session_id varchar(128),
  reported_detail text,
  reported_at timestamptz,
  token_id uuid,
  token_subject varchar(128),
  token_value text,                       -- handed to the runtime by launch; never served elsewhere
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (project_id, name)
);

create table agent_claim (
  id uuid primary key,
  agent_id uuid not null references agent(id),
  entity_id uuid not null,
  qualified_id varchar(64) not null,
  archetype varchar(16) not null,
  title text not null,
  claimed_by varchar(256) not null,
  claimed_at timestamptz not null,
  released_at timestamptz,
  released_by varchar(256)
);
create unique index agent_claim_open_uq on agent_claim (agent_id, entity_id) where released_at is null;

create table agent_turn (
  id uuid primary key,
  agent_id uuid not null references agent(id),
  generation integer not null,
  kind varchar(16) not null,              -- RECOVERY | ASSIGNED | COMMENTED
  text text not null,
  source_event_id uuid,                   -- the projects event that caused it; null for RECOVERY
  created_at timestamptz not null,
  delivered_at timestamptz
);
create unique index agent_turn_source_uq on agent_turn (agent_id, source_event_id) where source_event_id is not null;
