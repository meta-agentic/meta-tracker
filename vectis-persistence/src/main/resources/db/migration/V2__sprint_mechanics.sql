-- Sprint mechanics (VEC-43).
--
-- Cardinality decision: a board may have at most one ACTIVE sprint. Every other space
-- in the estate already runs one active sprint at a time per its cadence, and Jira
-- makes multi-active an opt-in board setting precisely because it is the exception, not
-- the default. Single-active is the simpler invariant and the one that matches actual
-- usage.
--
-- It is enforced here as a partial unique index, not just an application-level check,
-- so it holds even under two concurrent attempts to start a sprint on the same board —
-- the same reasoning as `unique (board_id, ordinal)` on board_column.

create table sprint (
    id           uuid        primary key,
    board_id     uuid        not null references board (id) on delete cascade,
    name         text        not null,
    status       text        not null default 'FUTURE' check (status in ('FUTURE', 'ACTIVE', 'COMPLETED')),
    started_at   timestamptz,
    completed_at timestamptz,
    created_at   timestamptz not null default now()
);

create index sprint_board_idx on sprint (board_id);

create unique index sprint_board_single_active_idx on sprint (board_id) where status = 'ACTIVE';

-- Sprint scope for an item, alongside board_id/column_id: null means backlog.
alter table item add column sprint_id uuid references sprint (id);

create index item_sprint_idx on item (board_id, sprint_id);
