-- Attaches the workspaces that predate the template hierarchy (VEC-68 AC2), then makes the
-- reference and the column keys mandatory.
--
-- Every existing workspace was provisioned from the Scrum columns, so each is pinned to the
-- built-in scrum@1 (inserted by V4.1) with an empty delta, and each of its columns gets the key
-- of the scrum@1 state with the same name. Nothing is invented: a column whose name is no state
-- of scrum@1, or two columns of one board with the same name, fail the migration with the
-- workspace key and the column name, to be resolved by hand before migrating again.
--
-- Lock time: the key backfill is one UPDATE of every board_column row, and the `set not null`
-- statements scan workspace and board_column once each, all under the ACCESS EXCLUSIVE locks
-- V4 took. Acceptable at current volumes; a large board_column would need the backfill batched
-- ahead of the constraint.

do $$
declare
    scrum  uuid;
    states jsonb;
    bad    text;
begin
    select t.id, v.resolved -> 'states' into scrum, states
      from workflow_template t
      join workflow_template_version v on v.template_id = t.id and v.version = 1
     where t.tenant_id is null and t.key = 'scrum';
    if scrum is null then
        raise exception 'built-in scrum@1 is missing; V4.1 inserts it';
    end if;

    update workspace set template_id = scrum, template_version = 1 where template_id is null;

    select string_agg(format('workspace %s: column "%s"', w.key, c.name), '; ' order by w.key, c.ordinal)
      into bad
      from board_column c
      join board b on b.id = c.board_id
      join workspace w on w.id = b.workspace_id
     where c.key is null
       and not exists (select 1 from jsonb_each(states) s where s.value ->> 'name' = c.name);
    if bad is not null then
        raise exception 'columns that map to no state of scrum@1: %', bad;
    end if;

    select string_agg(format('workspace %s: column "%s" appears %s times on one board', w.key, d.name, d.n), '; '
                      order by w.key, d.name)
      into bad
      from (select board_id, name, count(*) as n from board_column
             where key is null group by board_id, name having count(*) > 1) d
      join board b on b.id = d.board_id
      join workspace w on w.id = b.workspace_id;
    if bad is not null then
        raise exception 'columns that map to the same state of scrum@1: %', bad;
    end if;

    update board_column c
       set key = s.key,
           on_board = coalesce((s.value ->> 'onBoard')::boolean, true)
      from jsonb_each(states) s
     where c.key is null and s.value ->> 'name' = c.name;
end;
$$;

alter table workspace
    alter column template_id set not null,
    alter column template_version set not null;

alter table board_column alter column key set not null;
