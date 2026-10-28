-- VEC-94: a board is addressed by key, as a workspace and a template are (ADR-VEC-05 §3), so
-- /api/v1 can name it without showing its id. The key is unique within its workspace and never
-- changes. A workspace's first board takes the workspace's key and a later one the key with
-- `-<n>` appended, the first n that is free; the key of a board created before this migration
-- follows the same rule in id (creation) order.
alter table board add column key text;

with numbered as (
    select b.id, w.key as workspace_key,
           row_number() over (partition by b.workspace_id order by b.id) as n
      from board b
      join workspace w on w.id = b.workspace_id
)
update board b
   set key = case when numbered.n = 1 then numbered.workspace_key else numbered.workspace_key || '-' || numbered.n end
  from numbered
 where numbered.id = b.id;

-- A writer that names no key gets the rule above, so provisioning, the repositories and an
-- import need not know it. Two boards created for one workspace at the same moment can draw the
-- same key; the constraint refuses the second rather than letting the two share it.
create function board_default_key() returns trigger
    language plpgsql as $$
declare
    workspace_key text;
    candidate     text;
    n             int := 1;
begin
    if new.key is null then
        select key into workspace_key from workspace where id = new.workspace_id;
        candidate := workspace_key;
        while exists (select 1 from board where workspace_id = new.workspace_id and key = candidate) loop
            n := n + 1;
            candidate := workspace_key || '-' || n;
        end loop;
        new.key := candidate;
    end if;
    return new;
end
$$;

create trigger board_default_key
    before insert on board
    for each row execute function board_default_key();

alter table board
    alter column key set not null,
    add constraint board_workspace_key_key unique (workspace_id, key);
