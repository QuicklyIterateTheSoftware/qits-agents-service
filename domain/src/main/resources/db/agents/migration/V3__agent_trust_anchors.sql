-- qits-agents, the platform CA's anchor bundle an agent's current AGENT lease was asked for with
-- (qits-2636, runtime/AgentLeases). Null while none was held then, or before this column existed.
-- Frozen at the lease's creation: a CA rotation alone must never roll a running agent's container,
-- so the live bundle is read only when a new lease is asked for, never when composing the spec of
-- one that already exists. The new bundle reaches the agent at its next lease.

alter table agent add column trust_anchors text;
