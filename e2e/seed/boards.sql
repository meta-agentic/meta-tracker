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

-- Three sprints for the Sprint tab (sprint.spec.ts): E2E-S1 closed, with E2E-1 .. E2E-3 still in
-- To Do; E2E-S2 active, with the two In Progress items and E2E-303, which sits in a column kept off the board; E2E-S3 planned, without dates.
insert into sprint (id, board_id, name, status, key, goal, start_date, end_date, closed_date) values
    ('01900000-0000-7000-8000-0000005b0001', '01900000-0000-7000-8000-00000000b0a1', 'E2E-S1', 'COMPLETED', 'E2E-S1',
     'Prove the harness', '2026-10-01', '2026-10-14', '2026-10-15'),
    ('01900000-0000-7000-8000-0000005b0002', '01900000-0000-7000-8000-00000000b0a1', 'E2E-S2', 'ACTIVE', 'E2E-S2',
     'Show the sprint', '2026-10-15', '2026-10-28', null),
    ('01900000-0000-7000-8000-0000005b0003', '01900000-0000-7000-8000-00000000b0a1', 'E2E-S3', 'FUTURE', 'E2E-S3',
     null, null, null, null);

-- E2E-1 .. E2E-205 in To Do, E2E-1 the oldest and the first by rank.
insert into item (id, workspace_id, board_id, column_id, key, title, rank, fields, sprint_id)
select ('01900000-0000-7000-8000-' || lpad(to_hex(n), 12, '0'))::uuid,
       '01900000-0000-7000-8000-00000000e2e0', '01900000-0000-7000-8000-00000000b0a1',
       '01900000-0000-7000-8000-0000000c0100', 'E2E-' || n, 'Seeded item ' || n, lpad(n::text, 4, '0'),
       '{"type": "story"}'::jsonb,
       case when n <= 3 then '01900000-0000-7000-8000-0000005b0001'::uuid end
from generate_series(1, 205) as n;

insert into item (id, workspace_id, board_id, column_id, key, title, rank, fields, sprint_id) values
    ('01900000-0000-7000-8000-000000001001', '01900000-0000-7000-8000-00000000e2e0',
     '01900000-0000-7000-8000-00000000b0a1', '01900000-0000-7000-8000-0000000c0101',
     'E2E-301', 'Render the board from the server', 'm', '{"type": "story", "storyPoints": 5}',
     '01900000-0000-7000-8000-0000005b0002'),
    ('01900000-0000-7000-8000-000000001002', '01900000-0000-7000-8000-00000000e2e0',
     '01900000-0000-7000-8000-00000000b0a1', '01900000-0000-7000-8000-0000000c0101',
     'E2E-302', 'Follow the cursor', 'b', '{"type": "task", "storyPoints": 3}',
     '01900000-0000-7000-8000-0000005b0002'),
    ('01900000-0000-7000-8000-000000001003', '01900000-0000-7000-8000-00000000e2e0',
     '01900000-0000-7000-8000-00000000b0a1', '01900000-0000-7000-8000-0000000c0103',
     'E2E-303', 'Kept off the board', 'a', '{"type": "story", "storyPoints": 2}',
     '01900000-0000-7000-8000-0000005b0002');

-- The item detail journey: a story under an epic that sits under another epic, and a story it
-- depends on. The two stories sit in Done; the epics are lifted out of the board.
insert into item (id, workspace_id, board_id, column_id, key, title, rank, fields)
select ('01900000-0000-7000-8000-0000000020' || lpad(n::text, 2, '0'))::uuid,
       '01900000-0000-7000-8000-00000000e2e0', '01900000-0000-7000-8000-00000000b0a1',
       '01900000-0000-7000-8000-0000000c0102', 'E2E-' || (400 + n), t, 'm' || n, f::jsonb
from (values
    (1, 'Outer epic', '{"type": "epic", "priority": "P0"}'),
    (2, 'Inner epic', '{"type": "epic", "parentId": "E2E-401", "priority": "P1"}'),
    (3, 'Story under the epics', '{"type": "story", "parentId": "E2E-402", "dependencies": ["E2E-404", "E2E-999"], "storyPoints": 3, "extension": 0.2, "intension": -0.3}'),
    (4, 'Story it depends on', '{"type": "story"}')
) as v(n, t, f);

commit;
