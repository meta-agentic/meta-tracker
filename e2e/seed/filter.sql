-- Epics for the board filter journey (tests/filter.spec.ts). Load it after boards.sql, which it
-- extends and which a re-run replaces:
--
--   psql -v ON_ERROR_STOP=1 -h <host> -U vectis -d vectis -f e2e/seed/filter.sql
--
-- Two epics, with E2E-4 .. E2E-8 under the first and E2E-9 .. E2E-11 under the second. All of
-- them sit in To Do and in no sprint, so the boards and sprint journeys read what they did before.

begin;

insert into item (id, workspace_id, board_id, column_id, key, title, rank, fields) values
    ('01900000-0000-7000-8000-000000002501', '01900000-0000-7000-8000-00000000e2e0',
     '01900000-0000-7000-8000-00000000b0a1', '01900000-0000-7000-8000-0000000c0100',
     'E2E-501', 'Checkout', 'e5', '{"type": "epic"}'),
    ('01900000-0000-7000-8000-000000002502', '01900000-0000-7000-8000-00000000e2e0',
     '01900000-0000-7000-8000-00000000b0a1', '01900000-0000-7000-8000-0000000c0100',
     'E2E-502', 'Reporting', 'e6', '{"type": "epic"}');

update item
   set fields = fields || jsonb_build_object('parentId', case when key in ('E2E-4', 'E2E-5', 'E2E-6', 'E2E-7', 'E2E-8')
                                                              then 'E2E-501' else 'E2E-502' end)
 where workspace_id = '01900000-0000-7000-8000-00000000e2e0'
   and key in ('E2E-4', 'E2E-5', 'E2E-6', 'E2E-7', 'E2E-8', 'E2E-9', 'E2E-10', 'E2E-11');

commit;
