-- SPDX-License-Identifier: Apache-2.0
-- Two native (writable) workspaces for the item forms journey (tests/forms.spec.ts): FRM on the
-- built-in scrum@1 and GATE on the built-in stage-gate@1, each with one board whose columns are
-- its template's states. Load it after the server has started, as boards.sql is loaded:
--
--   psql -v ON_ERROR_STOP=1 -h <host> -U vectis -d vectis -f e2e/seed/forms.sql
--
-- RO is bound to a backlog source, as an import leaves a workspace, so a client may not write to it.
-- Re-running it replaces the three workspaces, so the journey starts again from the same items.

begin;

delete from workspace where key in ('FRM', 'GATE', 'RO');

insert into workspace (id, key, name, template_id, template_version) values
    ('01900000-0000-7000-8000-0000000a0000', 'FRM', 'Forms journey',
     (select id from workflow_template where key = 'scrum' and tenant_id is null), 1),
    ('01900000-0000-7000-8000-0000000b0000', 'GATE', 'Stage-gate journey',
     (select id from workflow_template where key = 'stage-gate' and tenant_id is null), 1);

insert into board (id, workspace_id, key, name) values
    ('01900000-0000-7000-8000-0000000a0b01', '01900000-0000-7000-8000-0000000a0000', 'FRM', 'Delivery'),
    ('01900000-0000-7000-8000-0000000b0b01', '01900000-0000-7000-8000-0000000b0000', 'GATE', 'Portfolio');

insert into board_column (id, board_id, key, name, ordinal, on_board) values
    ('01900000-0000-7000-8000-0000000a0c00', '01900000-0000-7000-8000-0000000a0b01', 'backlog', 'Backlog', 0, true),
    ('01900000-0000-7000-8000-0000000a0c01', '01900000-0000-7000-8000-0000000a0b01', 'todo', 'To Do', 1, true),
    ('01900000-0000-7000-8000-0000000a0c02', '01900000-0000-7000-8000-0000000a0b01', 'in-progress', 'In Progress', 2, true),
    ('01900000-0000-7000-8000-0000000a0c03', '01900000-0000-7000-8000-0000000a0b01', 'in-review', 'In Review', 3, true),
    ('01900000-0000-7000-8000-0000000a0c04', '01900000-0000-7000-8000-0000000a0b01', 'done', 'Done', 4, true),
    ('01900000-0000-7000-8000-0000000a0c05', '01900000-0000-7000-8000-0000000a0b01', 'no-go', 'No Go', 5, false),
    ('01900000-0000-7000-8000-0000000b0c00', '01900000-0000-7000-8000-0000000b0b01', 'idea', 'Idea', 0, true),
    ('01900000-0000-7000-8000-0000000b0c01', '01900000-0000-7000-8000-0000000b0b01', 'scoping', 'Scoping', 1, true),
    ('01900000-0000-7000-8000-0000000b0c02', '01900000-0000-7000-8000-0000000b0b01', 'gate-1', 'Gate 1: go to development', 2, true),
    ('01900000-0000-7000-8000-0000000b0c03', '01900000-0000-7000-8000-0000000b0b01', 'development', 'Development', 3, true),
    ('01900000-0000-7000-8000-0000000b0c04', '01900000-0000-7000-8000-0000000b0b01', 'gate-2', 'Gate 2: go to launch', 4, true),
    ('01900000-0000-7000-8000-0000000b0c05', '01900000-0000-7000-8000-0000000b0b01', 'done', 'Launched', 5, true),
    ('01900000-0000-7000-8000-0000000b0c06', '01900000-0000-7000-8000-0000000b0b01', 'no-go', 'Killed', 6, false);

insert into item (id, workspace_id, board_id, column_id, key, title, rank, fields) values
    ('01900000-0000-7000-8000-0000000a1001', '01900000-0000-7000-8000-0000000a0000',
     '01900000-0000-7000-8000-0000000a0b01', '01900000-0000-7000-8000-0000000a0c00',
     'FRM-1', 'Accounts', 'a', '{"type": "epic", "priority": "high"}'),
    ('01900000-0000-7000-8000-0000000b1001', '01900000-0000-7000-8000-0000000b0000',
     '01900000-0000-7000-8000-0000000b0b01', '01900000-0000-7000-8000-0000000b0c01',
     'GATE-1', 'Enter the Nordic market', 'a', '{"type": "project", "risk": "medium"}'),
    ('01900000-0000-7000-8000-0000000b1002', '01900000-0000-7000-8000-0000000b0000',
     '01900000-0000-7000-8000-0000000b0b01', '01900000-0000-7000-8000-0000000b0c00',
     'GATE-2', 'Second product line', 'b', '{"type": "project"}'),
    ('01900000-0000-7000-8000-0000000b1003', '01900000-0000-7000-8000-0000000b0000',
     '01900000-0000-7000-8000-0000000b0b01', '01900000-0000-7000-8000-0000000b0c01',
     'GATE-3', 'Pilot in one city', 'c', '{"type": "milestone", "targetLaunch": "2027-03-01", "parentId": "GATE-1"}');

insert into workspace (id, key, name, template_id, template_version, source)
values ('01900000-0000-7000-8000-0000000c0000', 'RO', 'Imported workspace',
        (select id from workflow_template where key = 'scrum' and tenant_id is null), 1, 'vault');

insert into board (id, workspace_id, key, name)
values ('01900000-0000-7000-8000-0000000c0b01', '01900000-0000-7000-8000-0000000c0000', 'RO', 'Imported');

insert into board_column (id, board_id, key, name, ordinal, on_board)
values ('01900000-0000-7000-8000-0000000c0c00', '01900000-0000-7000-8000-0000000c0b01', 'todo', 'To Do', 0, true);

insert into item (id, workspace_id, board_id, column_id, key, title, rank, fields)
values ('01900000-0000-7000-8000-0000000c1001', '01900000-0000-7000-8000-0000000c0000',
        '01900000-0000-7000-8000-0000000c0b01', '01900000-0000-7000-8000-0000000c0c00',
        'RO-1', 'Changed at the source', 'a', '{"type": "story"}');

commit;
