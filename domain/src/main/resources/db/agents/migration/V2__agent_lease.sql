-- qits-agents, the runtime's lease (epic qits-2519). Every RUNNING agent runs in an AGENT lease of
-- qits-system's (runtime/AgentLeases); the row names the lease it last asked for, and the sweep
-- ends it when the agent is RETIRED. Null: no lease asked for, or the last one ended.

alter table agent add column lease_id uuid;
