-- The workspace the boards journey (tests/boards.spec.ts) reads. Load it into the server's
-- database after the server has started, because Flyway creates the schema and the built-in
-- scrum@1 template at boot:
--
--   psql -v ON_ERROR_STOP=1 -h <host> -U vectis -d vectis -f e2e/seed/boards.sql
--
-- and build the web client with VITE_VECTIS_WORKSPACE_KEY=E2E so the SPA loads it.
-- Re-running it replaces the workspace.
--
-- To Do holds 205 items, more than one page (the client asks for 200, the server's
-- maximum), and its first card by rank is its oldest item, which arrives on the second
-- page: the journey only shows it if the client follows the cursor. Item ids are
-- time-ordered like the server's, so newest first is a known order.

begin;

delete from workspace where key = 'E2E';

insert into workspace (id, key, name, template_id, template_version)
values ('01900000-0000-7000-8000-00000000e2e0', 'E2E', 'Boards journey',
        (select id from workflow_template where key = 'scrum' and tenant_id is null), 1);

insert into board (id, workspace_id, key, name)
values ('01900000-0000-7000-8000-00000000b0a1', '01900000-0000-7000-8000-00000000e2e0', 'E2E', 'Delivery');

-- Inserted out of ordinal order, so the page shows the stored order, not the insert order.
insert into board_column (id, board_id, key, name, ordinal, on_board) values
    ('01900000-0000-7000-8000-0000000c0102', '01900000-0000-7000-8000-00000000b0a1', 'done', 'Done', 2, true),
    ('01900000-0000-7000-8000-0000000c0100', '01900000-0000-7000-8000-00000000b0a1', 'todo', 'To Do', 0, true),
    ('01900000-0000-7000-8000-0000000c0103', '01900000-0000-7000-8000-00000000b0a1', 'icebox', 'Icebox', 3, false),
    ('01900000-0000-7000-8000-0000000c0101', '01900000-0000-7000-8000-00000000b0a1', 'doing', 'In Progress', 1, true);

-- E2E-1 .. E2E-205 in To Do, E2E-1 the oldest and the first by rank.
insert into item (id, workspace_id, board_id, column_id, key, title, rank, fields)
select ('01900000-0000-7000-8000-' || lpad(to_hex(n), 12, '0'))::uuid,
       '01900000-0000-7000-8000-00000000e2e0', '01900000-0000-7000-8000-00000000b0a1',
       '01900000-0000-7000-8000-0000000c0100', 'E2E-' || n, 'Seeded item ' || n, lpad(n::text, 4, '0'),
       '{"type": "story"}'::jsonb
from generate_series(1, 205) as n;

insert into item (id, workspace_id, board_id, column_id, key, title, rank, fields) values
    ('01900000-0000-7000-8000-000000001001', '01900000-0000-7000-8000-00000000e2e0',
     '01900000-0000-7000-8000-00000000b0a1', '01900000-0000-7000-8000-0000000c0101',
     'E2E-301', 'Render the board from the server', 'm', '{"type": "story"}'),
    ('01900000-0000-7000-8000-000000001002', '01900000-0000-7000-8000-00000000e2e0',
     '01900000-0000-7000-8000-00000000b0a1', '01900000-0000-7000-8000-0000000c0101',
     'E2E-302', 'Follow the cursor', 'b', '{"type": "task"}'),
    ('01900000-0000-7000-8000-000000001003', '01900000-0000-7000-8000-00000000e2e0',
     '01900000-0000-7000-8000-00000000b0a1', '01900000-0000-7000-8000-0000000c0103',
     'E2E-303', 'Kept off the board', 'a', '{"type": "story"}');

commit;
