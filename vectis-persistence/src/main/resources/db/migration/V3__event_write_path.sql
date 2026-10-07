-- Event write path: the schema half of the real-time transport (docs/spikes/realtime-transport.md,
-- Rules → write path).
--
-- Every state change of a workspace is written in one transaction together with an event that
-- describes it: the workspace row is locked, the change is applied with item.version raised by
-- one, the next per-workspace `seq` is taken, the event row is appended and a doorbell is rung
-- with pg_notify. The event commits with the change or not at all, so there is no dual write.
-- WorkspaceEventLog is the one place that does this; a write made any other way reaches no board.

-- The revision a client compares to tell a newer representation of an item from an older one,
-- and what an If-Match on an item edit names. Existing items start at 1.
alter table item add column version bigint not null default 1;

-- The head of the workspace's stream: the seq of its last event, 0 for none. Taken under the
-- workspace row lock, which is held until commit, so a workspace's seqs are gap-free and commit
-- in seq order.
alter table workspace add column event_seq bigint not null default 0;

-- Identifies the workspace's stream across a database restore: a restore rewinds event_seq,
-- and the restore procedure must give every workspace a new epoch so that a client holding a
-- cursor from before it is told to reload instead of silently missing the rewound events. The
-- cursor a client holds is `<stream_epoch>.<seq>`.
--
-- The default is volatile, so this statement rewrites the table and evaluates it once per row:
-- every existing workspace gets its own fresh epoch, and every new one gets one at creation.
alter table workspace add column stream_epoch uuid not null default gen_random_uuid();

-- The log the stream is served from. `payload` is the exact text that goes on the wire, stored
-- as text rather than jsonb so that a replay is byte-identical to the live delivery (jsonb would
-- reorder the envelope's keys). Retention is pruned by seq, never by time: created_at is the
-- transaction's start time and a later seq can carry an earlier one.
create table workspace_event (
    workspace_id uuid        not null references workspace (id) on delete cascade,
    seq          bigint      not null check (seq > 0),
    type         text        not null,
    payload      text        not null,
    created_at   timestamptz not null default now(),
    primary key (workspace_id, seq)
);
