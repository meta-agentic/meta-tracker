// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.vectis.domain.Board;
import io.vectis.domain.BoardColumn;
import io.vectis.domain.TimeOrderedId;
import io.vectis.domain.Workspace;
import io.vectis.domain.template.TemplateJson;
import io.vectis.domain.template.TemplateResolver;
import io.vectis.domain.template.Violation;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Provisioning by reference (VEC-68 AC4) and the template reads it stands on (AC5), against a
 * real PostgreSQL.
 */
@QuarkusTest
class ProvisionTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** The import's worked instance (VEC-45): a Refined state after the Backlog. */
    private static final Map<String, Object> REFINED = TemplateJson.parseObject("""
            {"states": {"refined": {"name": "Refined", "category": "START_STATE", "after": "backlog"}}}
            """);

    @Inject WorkspaceRepository workspaces;
    @Inject TemplateRepository templates;
    @Inject BoardRepository boards;
    @Inject Pool pool;

    private static Workspace newWorkspace() {
        int n = SEQ.incrementAndGet();
        return Workspace.create("PV" + n, "Provisioned " + n);
    }

    private Row workspaceRow(String key) {
        var rows = pool.preparedQuery("""
                        select t.key as template_key, t.tenant_id, w.template_version, w.config_delta::text as delta,
                               w.config_revision, w.follow
                          from workspace w join workflow_template t on t.id = w.template_id
                         where w.key = $1
                        """)
                .execute(Tuple.of(key)).await().indefinitely();
        return rows.rowCount() == 0 ? null : rows.iterator().next();
    }

    private long count(String sql, Object param) {
        return pool.preparedQuery(sql).execute(Tuple.of(param)).await().indefinitely().iterator().next().getLong(0);
    }

    private static List<String> keys(Board board) {
        return board.orderedColumns().stream().map(BoardColumn::key).toList();
    }

    /**
     * Publishes a tenant template extending scrum@1 the only way one exists before VEC-71: as rows,
     * with its resolution from the production resolver.
     */
    private TemplateRef publishTenantTemplate(UUID tenant, String key) {
        Map<String, Object> document = TemplateJson.parseObject("""
                {"template": "%s", "version": 1, "name": "Tenant", "extends": {"template": "scrum", "version": 1},
                 "states": {"qa": {"name": "QA", "category": "IN_PROGRESS", "after": "in-review"}}}
                """.formatted(key));
        Map<String, Object> resolved = TemplateResolver.resolve(List.of(
                TemplateJson.parseObject(BuiltInTemplates.file("base.v1")),
                TemplateJson.parseObject(BuiltInTemplates.file("scrum.v1")),
                document), null).effective();
        UUID id = TimeOrderedId.next();
        pool.withTransaction(c -> c.preparedQuery("insert into workflow_template (id, tenant_id, key) values ($1, $2, $3)")
                .execute(Tuple.of(id, tenant, key))
                .chain(() -> c.preparedQuery("""
                                insert into workflow_template_version
                                       (template_id, version, parent_id, parent_version, document, resolved, resolver_version, cause)
                                select $1, 1, p.id, 1, $2::text::jsonb, $3::text::jsonb, $4, '{"kind": "authored"}'
                                  from workflow_template p where p.tenant_id is null and p.key = 'scrum'
                                """)
                        .execute(Tuple.of(id, TemplateJson.compact(document), TemplateJson.compact(resolved),
                                TemplateResolver.RESOLVER_VERSION))))
                .await().indefinitely();
        return new TemplateRef(key, 1);
    }

    @Test
    void withoutADeltaTheBoardProjectsTheTemplateStatesInOrder() {
        Workspace ws = newWorkspace();

        ProvisionedWorkspace p = workspaces.provision(ws, null, TemplateRef.parse("scrum@1"), null)
                .await().indefinitely();

        assertEquals(Map.of(), p.delta());
        Board stored = boards.findById(p.board().id()).await().indefinitely().orElseThrow();
        assertEquals(List.of("backlog", "todo", "in-progress", "in-review", "done", "no-go"), keys(stored));
        assertEquals(List.of("Backlog", "To Do", "In Progress", "In Review", "Done", "No Go"),
                stored.orderedColumns().stream().map(BoardColumn::name).toList());
        assertEquals(List.of(0, 1, 2, 3, 4, 5), stored.orderedColumns().stream().map(BoardColumn::position).toList());
        assertEquals(List.of(true, true, true, true, true, false),
                stored.orderedColumns().stream().map(BoardColumn::onBoard).toList());
        assertEquals(ws.id(), stored.workspaceId());
        assertEquals(ws.name(), stored.name());
        assertEquals(p.board(), stored);

        Row row = workspaceRow(ws.key());
        assertEquals("scrum", row.getString("template_key"));
        assertNull(row.getUUID("tenant_id"));
        assertEquals(1, row.getInteger("template_version"));
        assertEquals("{}", row.getString("delta"));
        assertEquals(0L, row.getLong("config_revision"));
        assertEquals("manual", row.getString("follow"));
        assertEquals(ws, workspaces.findByKey(ws.key()).await().indefinitely().orElseThrow());
    }

    @Test
    void aStartingDeltaIsStoredAndProjected() {
        Workspace ws = newWorkspace();

        ProvisionedWorkspace p = workspaces.provision(ws, null, TemplateRef.parse("scrum@1"), REFINED)
                .await().indefinitely();

        assertEquals(List.of("backlog", "refined", "todo", "in-progress", "in-review", "done", "no-go"), keys(p.board()));
        assertEquals(p.board(), boards.findById(p.board().id()).await().indefinitely().orElseThrow());
        assertEquals(REFINED, TemplateJson.parseObject(workspaceRow(ws.key()).getString("delta")));
    }

    @Test
    void anotherBuiltInProvisionsItsOwnStates() {
        ProvisionedWorkspace p = workspaces.provision(newWorkspace(), null, TemplateRef.parse("kanban@1"), null)
                .await().indefinitely();

        Map<String, Object> resolved = templates.findVersion(null, TemplateRef.parse("kanban@1")).await().indefinitely()
                .orElseThrow().resolved();
        assertEquals(resolved.get("stateOrder"), keys(p.board()));
    }

    @Test
    void anInvalidDeltaIsRefusedWithTheResolverViolationsAndNothingIsWritten() {
        Workspace ws = newWorkspace();
        Map<String, Object> invalid = TemplateJson.parseObject("{\"states\": {\"qa\": {\"name\": \"QA\"}}}");

        ConfigurationViolationException e = assertThrows(ConfigurationViolationException.class,
                () -> workspaces.provision(ws, null, TemplateRef.parse("scrum@1"), invalid).await().indefinitely());

        assertFalse(e.violations().isEmpty());
        assertTrue(e.violations().stream().allMatch(v -> TemplateResolver.WORKSPACE.equals(v.level())), e::getMessage);
        assertTrue(e.violations().stream().map(Violation::field).anyMatch(f -> f.startsWith("states.qa")), e::getMessage);
        assertNoTrace(ws);
    }

    /**
     * Provisioning is one transaction: a failure after the workspace and the board are written, on
     * the column insert, leaves no row of either. A trigger installed for this test only fails the
     * column insert of the probe workspace's board, which is named after it.
     */
    @Test
    void aFailureAfterTheWorkspaceIsWrittenLeavesNothingBehind() {
        Workspace ws = Workspace.create("PVROLLBACK", "Rollback probe");
        pool.query("""
                create function provision_probe_fail() returns trigger language plpgsql as $$
                begin
                    if exists (select 1 from board b join workspace w on w.id = b.workspace_id
                                where b.id = new.board_id and w.key = 'PVROLLBACK') then
                        raise exception 'probe: column insert refused';
                    end if;
                    return new;
                end;
                $$;
                create trigger provision_probe_fail before insert on board_column
                    for each row execute function provision_probe_fail();
                """).execute().await().indefinitely();
        try {
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> workspaces.provision(ws, null, TemplateRef.parse("scrum@1"), null).await().indefinitely());
            assertTrue(e.getMessage().contains("probe: column insert refused"), e::getMessage);
            assertNoTrace(ws);
            assertEquals(0, count("select count(*) from board where name = $1", ws.name()));
        } finally {
            pool.query("drop trigger provision_probe_fail on board_column; drop function provision_probe_fail()")
                    .execute().await().indefinitely();
        }
        assertEquals(ws, workspaces.provision(ws, null, TemplateRef.parse("scrum@1"), null).await().indefinitely()
                .workspace(), "the same workspace provisions once the failure is gone");
    }

    @Test
    void anUnknownTemplateIsRefusedAndNothingIsWritten() {
        Workspace ws = newWorkspace();

        TemplateNotFoundException e = assertThrows(TemplateNotFoundException.class,
                () -> workspaces.provision(ws, null, TemplateRef.parse("scrum@2"), null).await().indefinitely());

        assertEquals(TemplateRef.parse("scrum@2"), e.ref());
        assertNoTrace(ws);
    }

    @Test
    void aTenantProvisionsFromItsOwnTemplateButNotFromAnotherTenants() {
        UUID tenant = UUID.randomUUID();
        TemplateRef own = publishTenantTemplate(tenant, "tenant" + SEQ.incrementAndGet());

        ProvisionedWorkspace p = workspaces.provision(newWorkspace(), tenant, own, null).await().indefinitely();
        assertEquals(List.of("backlog", "todo", "in-progress", "in-review", "qa", "done", "no-go"), keys(p.board()));
        assertEquals(tenant, workspaceRow(p.workspace().key()).getUUID("tenant_id"));

        Workspace other = newWorkspace();
        assertThrows(TemplateNotFoundException.class,
                () -> workspaces.provision(other, UUID.randomUUID(), own, null).await().indefinitely());
        assertThrows(TemplateNotFoundException.class,
                () -> workspaces.provision(other, null, own, null).await().indefinitely());
        assertNoTrace(other);
    }

    @Test
    void theBuiltInsAreVisibleToEveryoneAndATenantsTemplatesOnlyToIt() {
        UUID tenant = UUID.randomUUID();
        TemplateRef own = publishTenantTemplate(tenant, "tenant" + SEQ.incrementAndGet());

        List<TemplateSummary> builtIns = templates.findVisible(null).await().indefinitely();
        assertEquals(List.of("base@1", "hybrid@1", "kanban@1", "scrum@1"),
                builtIns.stream().map(t -> t.latest().toString()).toList());
        assertTrue(builtIns.stream().allMatch(TemplateSummary::builtIn));
        assertEquals(List.of("-", "scrum@1", "base@1", "base@1"),
                builtIns.stream().map(t -> t.parent() == null ? "-" : t.parent().toString()).toList());
        assertTrue(builtIns.stream().allMatch(t -> t.follow().equals("manual")));

        List<TemplateSummary> visible = templates.findVisible(tenant).await().indefinitely();
        assertEquals(builtIns, visible.subList(0, 4));
        assertEquals(List.of(own), visible.subList(4, visible.size()).stream().map(TemplateSummary::latest).toList());
        assertEquals(TemplateRef.parse("scrum@1"), visible.getLast().parent());
        assertEquals(tenant, visible.getLast().tenantId());

        assertEquals(builtIns, templates.findVisible(UUID.randomUUID()).await().indefinitely());
    }

    @Test
    void aVersionIsFoundByKeyAtVersionWithItsMaterialisedResolution() {
        PublishedTemplate hybrid = templates.findVersion(null, TemplateRef.parse("hybrid@1")).await().indefinitely()
                .orElseThrow();

        assertEquals(TemplateRef.parse("hybrid@1"), hybrid.ref());
        assertTrue(hybrid.builtIn());
        assertEquals(TemplateRef.parse("scrum@1"), hybrid.parent());
        assertEquals(TemplateJson.parseObject(BuiltInTemplates.file("hybrid.v1")), hybrid.document());
        assertEquals(TemplateResolver.resolve(List.of(
                        TemplateJson.parseObject(BuiltInTemplates.file("base.v1")),
                        TemplateJson.parseObject(BuiltInTemplates.file("scrum.v1")),
                        TemplateJson.parseObject(BuiltInTemplates.file("hybrid.v1"))), null).effective(),
                hybrid.resolved());
        assertEquals(TemplateResolver.RESOLVER_VERSION, hybrid.resolverVersion());
        assertEquals(Map.of("kind", "authored"), hybrid.cause());
        assertTrue(templates.findVersion(null, TemplateRef.parse("hybrid@2")).await().indefinitely().isEmpty());
        assertTrue(templates.findVersion(null, TemplateRef.parse("nosuch@1")).await().indefinitely().isEmpty());
    }

    @Test
    void aTemplateReferenceIsKeyAtVersion() {
        assertEquals(new TemplateRef("scrum", 1), TemplateRef.parse("scrum@1"));
        assertEquals("acme-platform@12", TemplateRef.parse("acme-platform@12").toString());
        for (String bad : List.of("scrum", "scrum@", "@1", "scrum@0", "scrum@-1", "scrum@1.0", "Scrum@1", "scrum@x")) {
            assertThrows(IllegalArgumentException.class, () -> TemplateRef.parse(bad), bad);
        }
    }

    private void assertNoTrace(Workspace ws) {
        assertEquals(0, count("select count(*) from workspace where key = $1", ws.key()));
        assertEquals(0, count("select count(*) from board where workspace_id = $1", ws.id()));
    }
}
