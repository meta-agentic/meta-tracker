-- Template hierarchy (VEC-68, ADR-VEC-03, docs/spikes/template-hierarchy.md §9).
--
-- A workspace no longer copies its columns from a template: it pins one immutable template
-- version and stores its own delta over it, and board_column becomes a projection of the
-- effective states, addressed by state key. This migration builds the shape; V4.1 inserts the
-- built-in versions from their files and V4.2 attaches the workspaces that predate it.
--
-- Lock time: every statement here is catalogue-only or scans an empty or small table, except
-- the board_column constraint rebuilds, which scan board_column once under an ACCESS EXCLUSIVE
-- lock held to commit. V4.2 then backfills the keys in one UPDATE.

create table workflow_template (
    id        uuid primary key,
    -- null: built-in, shipped by a Vectis release and readable by every tenant (VEC-80 adds RLS).
    tenant_id uuid,
    key       text not null check (key ~ '^[a-z][a-zA-Z0-9-]{0,31}$'),
    follow    text not null default 'manual' check (follow in ('manual', 'auto')),
    -- one key per tenant and one per built-in; nulls not distinct makes the built-ins one owner
    constraint workflow_template_owner_key unique nulls not distinct (tenant_id, key)
);

-- The unique constraint alone lets a tenant key equal a built-in key. Keys are not shadowed, in
-- either direction: a tenant may not take a built-in key, and a migration adding a built-in key a
-- tenant already uses fails, to be resolved by hand. The advisory lock serialises inserts of one
-- key, so two transactions cannot each see the other's row missing.
create function workflow_template_refuse_shadowing() returns trigger
    language plpgsql as $$
begin
    perform pg_advisory_xact_lock(hashtext('workflow_template:' || new.key));
    if exists (select 1 from workflow_template t
                where t.key = new.key
                  and t.id <> new.id
                  and (t.tenant_id is null) <> (new.tenant_id is null)) then
        raise exception 'template key "%" is taken: a tenant template may not shadow a built-in, nor a built-in a tenant template',
            new.key
            using errcode = 'unique_violation';
    end if;
    return new;
end;
$$;

create trigger workflow_template_refuse_shadowing
    before insert or update of key, tenant_id on workflow_template
    for each row execute function workflow_template_refuse_shadowing();

create table workflow_template_version (
    template_id      uuid  not null references workflow_template (id),
    version          int   not null check (version >= 1),
    parent_id        uuid,
    parent_version   int,
    -- as authored: complete for a root, identity plus a delta over the parent otherwise
    document         jsonb not null,
    -- materialised at publish; the inputs are immutable, so it is never stale
    resolved         jsonb not null,
    resolver_version int   not null,
    -- {"kind": "authored"} or {"kind": "propagated", "from": "base@2"}
    cause            jsonb not null,
    published_at     timestamptz not null default now(),
    primary key (template_id, version),
    foreign key (parent_id, parent_version) references workflow_template_version (template_id, version),
    check ((parent_id is null) = (parent_version is null))
);

-- A published version is immutable: a change is a new version, and every workspace pinned to
-- this one keeps meaning exactly what it meant.
create function workflow_template_version_immutable() returns trigger
    language plpgsql as $$
declare
    template_key text;
begin
    select key into template_key from workflow_template where id = old.template_id;
    raise exception 'template version %@% is published and immutable; publish a new version instead',
        coalesce(template_key, old.template_id::text), old.version
        using errcode = 'restrict_violation';
end;
$$;

create trigger workflow_template_version_immutable
    before update or delete on workflow_template_version
    for each row execute function workflow_template_version_immutable();

-- template_id and template_version become not null in V4.2, once existing rows are attached.
alter table workspace
    add column template_id      uuid,
    add column template_version int,
    add column config_delta     jsonb  not null default '{}'::jsonb,
    add column config_revision  bigint not null default 0,
    add column follow           text   not null default 'manual',
    add constraint workspace_config_delta_object check (jsonb_typeof(config_delta) = 'object'),
    add constraint workspace_follow_check check (follow in ('manual', 'auto')),
    add constraint workspace_template_fk
        foreign key (template_id, template_version) references workflow_template_version (template_id, version);

create index workspace_template_idx on workspace (template_id, template_version);

-- key becomes not null in V4.2, once existing rows are keyed.
alter table board_column
    add column key      text check (key ~ '^[a-z][a-zA-Z0-9-]{0,31}$'),
    -- false for a state kept off the board, such as no-go; its row exists either way
    add column on_board boolean not null default true,
    add constraint board_column_board_key_key unique (board_id, key),
    -- a reorder or an inserted column shifts ordinals; an immediate unique check would fail on
    -- the first UPDATE of a reconciliation, so the check runs at commit
    drop constraint board_column_board_id_ordinal_key,
    add constraint board_column_board_id_ordinal_key unique (board_id, ordinal) deferrable initially deferred;
