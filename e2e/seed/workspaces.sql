-- SPDX-License-Identifier: Apache-2.0
-- A second workspace for the workspace journey (tests/workspaces.spec.ts), which switches
-- between it and the E2E workspace of boards.sql. Load it after the server has started, as
-- boards.sql is loaded:
--
--   psql -v ON_ERROR_STOP=1 -h <host> -U vectis -d vectis -f e2e/seed/workspaces.sql
--
-- Re-running it replaces the workspace. It touches nothing of E2E, so the other journeys
-- read what they did before. Its columns are named unlike E2E's, so a journey can tell from
-- the board alone which workspace is open.

begin;

delete from workspace where key = 'TWO';

insert into workspace (id, key, name, template_id, template_version)
values ('01900000-0000-7000-8000-0000000f0000', 'TWO', 'Second workspace',
        (select id from workflow_template where key = 'scrum' and tenant_id is null), 1);

insert into board (id, workspace_id, key, name)
values ('01900000-0000-7000-8000-0000000f0b01', '01900000-0000-7000-8000-0000000f0000', 'TWO', 'Operations');

insert into board_column (id, board_id, key, name, ordinal, on_board) values
    ('01900000-0000-7000-8000-0000000f0c00', '01900000-0000-7000-8000-0000000f0b01', 'todo', 'Queued', 0, true),
    ('01900000-0000-7000-8000-0000000f0c01', '01900000-0000-7000-8000-0000000f0b01', 'done', 'Shipped', 1, true);

insert into item (id, workspace_id, board_id, column_id, key, title, rank, fields) values
    ('01900000-0000-7000-8000-0000000f1001', '01900000-0000-7000-8000-0000000f0000',
     '01900000-0000-7000-8000-0000000f0b01', '01900000-0000-7000-8000-0000000f0c00',
     'TWO-1', 'Rotate the keys', 'a', '{"type": "task"}'),
    ('01900000-0000-7000-8000-0000000f1002', '01900000-0000-7000-8000-0000000f0000',
     '01900000-0000-7000-8000-0000000f0b01', '01900000-0000-7000-8000-0000000f0c01',
     'TWO-2', 'Renew the certificate', 'b', '{"type": "task"}');

commit;
