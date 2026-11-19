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

-- The number in the last item key minted for the workspace: an item created through the API is
-- `<workspace key>-<item_seq>`, taken under the workspace row lock the write path holds, so two
-- creates never draw the same number and a deleted item's key is never handed out again.
alter table workspace add column item_seq bigint not null default 0;

-- An existing workspace continues after the highest number among its own `<key>-<n>` items.
update workspace w
   set item_seq = coalesce((select max(substring(i.key from length(w.key) + 2)::bigint)
                              from item i
                             where i.workspace_id = w.id
                               and i.key ~ ('^' || w.key || '-[0-9]{1,18}$')), 0);
