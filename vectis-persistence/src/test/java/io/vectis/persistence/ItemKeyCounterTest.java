// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import io.vectis.domain.Board;
import io.vectis.domain.Item;
import io.vectis.domain.Workspace;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A workspace's key counter, {@code item_seq}, is never behind the highest number among its own
 * {@code <key>-<n>} items, whichever way an item got in, so that the key a create mints is free: an
 * item an import brings in, inserted through the repository or written straight into the table,
 * moves the counter up in the inserting transaction. A create also mints past any number already
 * taken, so a counter left behind by a load that skipped triggers still mints a free key.
 */
@QuarkusTest
class ItemKeyCounterTest {

    private static final TemplateRef SCRUM = TemplateRef.parse("scrum@1");
    private static final AtomicInteger KEY_SEQ = new AtomicInteger();

    @Inject WorkspaceRepository workspaces;
    @Inject ItemRepository items;
    @Inject Pool pool;

    private record Fixture(Workspace workspace, Board board) {

        String key(String suffix) {
            return workspace.key() + "-" + suffix;
        }

        UUID backlog() {
            return board.columns().stream().filter(c -> c.key().equals("backlog")).findFirst().orElseThrow().id();
        }

        Item item(String key) {
            return Item.create(workspace.id(), board.id(), backlog(), key, "Title " + key, "r");
        }
    }

    private Fixture newWorkspace() {
        ProvisionedWorkspace p = workspaces.provision(
                Workspace.create("KC" + KEY_SEQ.incrementAndGet(), "Key counter"), null, SCRUM, null)
                .await().indefinitely();
        return new Fixture(p.workspace(), p.board());
    }

    private long counter(Fixture f) {
        return pool.preparedQuery("select item_seq from workspace where id = $1").execute(Tuple.of(f.workspace().id()))
                .await().indefinitely().iterator().next().getLong("item_seq");
    }

    private void setCounter(Fixture f, long value) {
        pool.preparedQuery("update workspace set item_seq = $2 where id = $1")
                .execute(Tuple.of(f.workspace().id(), value)).await().indefinitely();
    }

    /** An item written straight into the table, as a seed or a hand repair would. */
    private Uni<?> insertDirectly(Fixture f, String key) {
        Item item = f.item(key);
        return pool.preparedQuery("""
                        insert into item (id, workspace_id, board_id, column_id, key, title, rank)
                        values ($1, $2, $3, $4, $5, $6, $7)
                        """)
                .execute(Tuple.from(new Object[] {item.id(), item.workspaceId(), item.boardId(), item.columnId(),
                    item.key(), item.title(), item.rank()}));
    }

    private Item create(Fixture f) {
        return items.create(new NewItem(f.workspace().id(), f.board().id(), f.backlog(), "Created", null, Map.of()),
                null).await().indefinitely();
    }

    // --- every way an item with a key gets in moves the counter up ---------------------------------

    /** One row per way an item with a given key gets in: each repository insert and a plain SQL insert. */
    static Stream<Arguments> insertPaths() {
        return Stream.<Map.Entry<String, BiFunction<ItemKeyCounterTest, Fixture, Uni<?>>>>of(
                Map.entry("insert(Item)", (t, f) -> t.items.insert(f.item(f.key("41")))),
                Map.entry("insert(Item,String)", (t, f) -> t.items.insert(f.item(f.key("41")), "tab-1")),
                Map.entry("insertAll(List)", (t, f) -> t.items.insertAll(
                        List.of(f.item(f.key("41")), f.item(f.key("7"))))),
                Map.entry("insertAll(List,String)", (t, f) -> t.items.insertAll(
                        List.of(f.item(f.key("7")), f.item(f.key("41"))), "tab-1")),
                Map.entry("upsertAll(UUID,List,String)", (t, f) -> t.items.upsertAll(f.board().id(), List.of(
                        new ImportedItem(f.key("41"), "Imported", "backlog", "a", Map.of()),
                        new ImportedItem(f.key("7"), "Imported", "todo", "b", Map.of())), "vault-import")),
                Map.entry("plain SQL insert", (t, f) -> t.insertDirectly(f, f.key("41"))))
                .map(row -> Arguments.of(row.getKey(), row.getValue()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("insertPaths")
    void anInsertedKeyMovesTheCounterUpToItsNumber(String path, BiFunction<ItemKeyCounterTest, Fixture, Uni<?>> insert) {
        Fixture f = newWorkspace();
        insert.apply(this, f).await().indefinitely();
        assertEquals(41, counter(f));
    }

    /** The guard on the matrix above: a new public insert of {@link ItemRepository} without a row fails here. */
    @Test
    void everyRepositoryInsertHasARowInTheCounterMatrix() {
        Set<String> rows = insertPaths().map(a -> (String) a.get()[0]).collect(Collectors.toSet());
        Set<String> inserts = Arrays.stream(ItemRepository.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && m.getName().matches("(insert|upsert).*"))
                .map(ItemKeyCounterTest::signature)
                .collect(Collectors.toSet());
        assertEquals(inserts, rows.stream().filter(r -> !r.equals("plain SQL insert")).collect(Collectors.toSet()));
    }

    private static String signature(Method m) {
        return m.getName() + Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName)
                .collect(Collectors.joining(",", "(", ")"));
    }

    @Test
    void anInsertBelowTheCounterLeavesItWhereItIs() {
        Fixture f = newWorkspace();
        setCounter(f, 50);
        items.insert(f.item(f.key("10"))).await().indefinitely();
        assertEquals(50, counter(f));
    }

    @Test
    void aKeyWithLeadingZerosCountsByItsNumber() {
        Fixture f = newWorkspace();
        items.insert(f.item(f.key("007"))).await().indefinitely();
        assertEquals(7, counter(f));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OTHER-99", "%s0-99", "%s-x", "%s-", "%s-1e3", "%s-1234567890123456789"})
    void aKeyNotOfTheWorkspacesFormLeavesTheCounterAlone(String pattern) {
        Fixture f = newWorkspace();
        items.insert(f.item(pattern.formatted(f.workspace().key()))).await().indefinitely();
        assertEquals(0, counter(f));
    }

    @Test
    void anInsertMovesOnlyItsOwnWorkspacesCounter() {
        Fixture mine = newWorkspace();
        Fixture other = newWorkspace();
        items.insert(mine.item(mine.key("41"))).await().indefinitely();
        assertEquals(0, counter(other));
    }

    // --- create mints a free key ---------------------------------------------------------------

    @Test
    void aCreateAfterAnImportMintsTheNumberAfterTheImportsHighest() {
        Fixture f = newWorkspace();
        items.upsertAll(f.board().id(), List.of(
                new ImportedItem(f.key("12"), "Imported", "backlog", "a", Map.of()),
                new ImportedItem(f.key("3"), "Imported", "backlog", "b", Map.of())), "vault-import")
                .await().indefinitely();
        assertEquals(f.key("13"), create(f).key());
    }

    @Test
    void aCreateWhoseCounterIsBehindAnExistingKeyMintsTheNextFreeNumber() {
        Fixture f = newWorkspace();
        items.insert(f.item(f.key("5"))).await().indefinitely();
        setCounter(f, 0); // as a load that skipped triggers would leave it
        assertEquals(f.key("6"), create(f).key());
    }

    @Test
    void aCreateStillNeverReusesTheNumberOfADeletedItem() {
        Fixture f = newWorkspace();
        Item first = create(f);
        items.delete(first, 1, null).await().indefinitely();
        assertEquals(f.key("2"), create(f).key());
    }
}
