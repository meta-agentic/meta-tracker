-- VEC-40: a sprint an import brings is named by the key its source gives it (`VEC-S7`), unique on its
-- board, and carries what the source records of it: planned start and end, the day it was closed,
-- and the goal. These are dates as the source writes them, not the instants `started_at` and
-- `completed_at` stamp when a sprint is started or completed here; a sprint made in Vectis has
-- no key and none of these.
alter table sprint
    add column key         text,
    add column goal        text,
    add column start_date  date,
    add column end_date    date,
    add column closed_date date,
    add constraint sprint_board_key_key unique (board_id, key);
