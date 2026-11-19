-- VEC-16: the item write path of /api/v1, which creates, edits, moves and deletes items through the
-- command layer.

-- The backlog source a workspace is bound to, by the source's id (`vault`), or null for a workspace
-- Vectis owns. A bound workspace is a projection of its source, which stays the system of record
-- (ADR-VEC-04 D1): until write-back exists (D4), a client's write there is refused, and only the
-- import changes it.
alter table workspace add column source text;

-- A workspace the vault import has already written into is bound to the vault. Its writes carry the
-- origin `vault-import`; the import binds every workspace it writes into from now on.
update workspace w
   set source = 'vault'
 where exists (select 1
                 from workspace_event e
                where e.workspace_id = w.id
                  and e.payload::jsonb ->> 'origin' = 'vault-import');

-- The number in an item key of the workspace's own form, `<workspace key>-<n>` with n of 1 to 18
-- digits, or null for a key of any other form. Compared as text rather than as a pattern built from
-- the workspace key, so nothing in a key can act as a pattern.
create function item_key_number(item_key text, workspace_key text) returns bigint
    language sql immutable as $$
    select case
             when starts_with(item_key, workspace_key || '-')
              and substring(item_key from length(workspace_key) + 2) ~ '^[0-9]{1,18}$'
             then substring(item_key from length(workspace_key) + 2)::bigint
           end
$$;

-- The number in the last item key minted for the workspace, never behind the highest number among
-- its own items: an item created through the API is `<workspace key>-<item_seq>`, taken under the
-- workspace row lock the write path holds, so two creates never draw the same number, a deleted
-- item's key is never handed out again, and no minted key is one an item already has.
alter table workspace add column item_seq bigint not null default 0;

-- An existing workspace continues after the highest number among its own items.
update workspace w
   set item_seq = coalesce((select max(item_key_number(i.key, w.key)) from item i where i.workspace_id = w.id), 0);

-- Every insert keeps the counter up with the keys it brings, whichever writer it comes from: the
-- import, a bulk insert, a seed. It runs in the inserting statement's transaction, once per
-- statement; on the write path that transaction already holds the workspace row lock, so the update
-- waits for nothing. Keys never change once written, so an update needs no such trigger.
create function item_advance_key_counter() returns trigger
    language plpgsql as $$
begin
    update workspace w
       set item_seq = brought.highest
      from (select i.workspace_id, max(item_key_number(i.key, ws.key)) as highest
              from inserted i
              join workspace ws on ws.id = i.workspace_id
             group by i.workspace_id) brought
     where w.id = brought.workspace_id
       and w.item_seq < brought.highest;
    return null;
end
$$;

create trigger item_advance_key_counter
    after insert on item
    referencing new table as inserted
    for each statement execute function item_advance_key_counter();
