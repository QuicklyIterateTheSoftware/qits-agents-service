-- qits-2718: a claim also closes when its item closes. released_on names the closed status that
-- released it (DONE, DROPPED, RESOLVED or OBSOLETE); null for a claim released by an assignee move.
-- A claim a closure released is the one a reopen of its item gives back.
alter table agent_claim add column released_on varchar(16);
