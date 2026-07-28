-- Vectis initial schema (VEC-41).
--
-- Two deliberate shape decisions, both load-bearing:
--
--  1. Identifiers are uuid, generated application-side as UUIDv7 rather than by a
--     sequence or gen_random_uuid(). A sequence serialises concurrent inserts on one
--     page; a random uuid scatters index writes. A time-ordered uuid gives index
--     locality AND chronological sort, so "newest first" needs no extra index.
--     PostgreSQL orders uuid bytewise, which matches UUIDv7's big-endian timestamp.
--
--  2. Item carries both real columns and a jsonb payload. Anything the board must
--     index, join or order by is a column; everything a team models that Vectis
--     cannot know ahead of time lives in `fields`. A fully fixed schema forces teams
--     to bend to the tool; a fully schema-less one cannot order a column efficiently.

create table workspace (
    id         uuid        primary key,
    key        text        not null unique,
    name       text        not null,
    created_at timestamptz not null default now()
);

create table board (
    id           uuid        primary key,
    workspace_id uuid        not null references workspace (id) on delete cascade,
    name         text        not null,
    created_at   timestamptz not null default now()
);

create index board_workspace_idx on board (workspace_id);

create table board_column (
    id       uuid primary key,
    board_id uuid not null references board (id) on delete cascade,
    name     text not null,
    -- `position` is a reserved word in PostgreSQL; `ordinal` says the same thing.
    ordinal  int  not null,
    unique (board_id, ordinal)
);

create index board_column_board_idx on board_column (board_id, ordinal);

create table item (
    id           uuid        primary key,
    workspace_id uuid        not null references workspace (id) on delete cascade,
    board_id     uuid        not null references board (id) on delete cascade,
    column_id    uuid        not null references board_column (id),
    key          text        not null,
    title        text        not null,
    -- Sparse lexicographic rank, not a dense integer: dropping a card between two
    -- neighbours rewrites one row instead of renumbering the column.
    rank         text        not null,
    fields       jsonb       not null default '{}'::jsonb,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    unique (workspace_id, key)
);

-- The board read path: every card in a column, in display order.
create index item_board_column_rank_idx on item (board_id, column_id, rank);

-- Containment queries over the open field set (fields @> '{"epic":"VEC-1"}').
create index item_fields_idx on item using gin (fields jsonb_path_ops);
