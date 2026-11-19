// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import io.vectis.domain.Board;
import io.vectis.domain.BoardColumn;
import io.vectis.domain.Item;
import io.vectis.domain.Workspace;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

/**
 * The writes the command layer makes for a client (VEC-16): a create that mints the item's key,
 * a move and a delete that name the version they are made against, the read of an item by key in
 * its workspace, and the refusal of every single-item write on a workspace bound to a backlog
 * source, checked under the workspace lock.
 */
@QuarkusTest
class ClientWritePathTest {

    private static final TemplateRef SCRUM = TemplateRef.parse("scrum@1");
    private static final AtomicInteger KEY_SEQ = new AtomicInteger();

    @Inject WorkspaceRepository workspaces;
    @Inject ItemRepository items;
    @Inject BoardRepository boards;
    @Inject Pool pool;

    private record Fixture(Workspace workspace, Board board) {

        UUID column(String key) {
            return board.columns().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow().id();
        }

        NewItem draft(String title) {
            return new NewItem(workspace.id(), board.id(), column("backlog"), title, null, Map.of("points", 3));
        }
    }

    private Fixture newWorkspace() {
        ProvisionedWorkspace p = workspaces.provision(
                Workspace.create("CW" + KEY_SEQ.incrementAndGet(), "Client writes"), null, SCRUM, null)
                .await().indefinitely();
        return new Fixture(p.workspace(), p.board());
    }

    private Item create(Fixture f, String title) {
        return items.create(f.draft(title), "tab-1").await().indefinitely();
    }

    private long head(Fixture f) {
        return pool.preparedQuery("select event_seq from workspace where id = $1").execute(Tuple.of(f.workspace().id()))
                .await().indefinitely().iterator().next().getLong("event_seq");
    }

    private long counter(Fixture f) {
        return pool.preparedQuery("select item_seq from workspace where id = $1").execute(Tuple.of(f.workspace().id()))
                .await().indefinitely().iterator().next().getLong("item_seq");
    }

    private JsonObject lastEvent(Fixture f) {
        return new JsonObject(pool.preparedQuery("""
                        select payload from workspace_event where workspace_id = $1 order by seq desc limit 1
                        """).execute(Tuple.of(f.workspace().id())).await().indefinitely().iterator().next()
                .getString("payload"));
    }

    private Optional<Item> stored(Item item) {
        return items.findById(item.id()).await().indefinitely();
    }

    private static Throwable failure(Uni<?> write) {
        return assertThrows(RuntimeException.class, () -> write.await().indefinitely());
    }

    // --- create ----------------------------------------------------------------------------------

    @Test
    void createMintsTheWorkspacesFirstKeyAtVersionOne() {
        Fixture f = newWorkspace();
        Item item = create(f, "First");
        assertEquals(f.workspace().key() + "-1", item.key());
        assertEquals(1, item.version());
    }

    @Test
    void createMintsTheNextNumberEachTime() {
        Fixture f = newWorkspace();
        create(f, "First");
        assertEquals(f.workspace().key() + "-2", create(f, "Second").key());
    }

    @Test
    void eachWorkspaceCountsItsOwnKeys() {
        Fixture one = newWorkspace();
        Fixture other = newWorkspace();
        create(one, "One");
        create(one, "Two");
        assertEquals(other.workspace().key() + "-1", create(other, "Other").key());
    }

    @Test
    void aDeletedItemsNumberIsNotMintedAgain() {
        Fixture f = newWorkspace();
        Item first = create(f, "First");
        items.delete(first, 1, null).await().indefinitely();
        assertEquals(f.workspace().key() + "-2", create(f, "Second").key());
    }

    @Test
    void anItemCreatedWithoutARankTakesItsNumberZeroPadded() {
        Fixture f = newWorkspace();
        create(f, "First");
        assertEquals("00000002", create(f, "Second").rank());
    }

    @Test
    void anItemCreatedWithARankKeepsIt() {
        Fixture f = newWorkspace();
        NewItem draft = new NewItem(f.workspace().id(), f.board().id(), f.column("todo"), "Ranked", "k", Map.of());
        assertEquals("k", items.create(draft, null).await().indefinitely().rank());
    }

    @Test
    void createStoresTheTitleFieldsAndColumnAndNoSprint() {
        Fixture f = newWorkspace();
        Item stored = stored(create(f, "Stored")).orElseThrow();
        assertEquals("Stored", stored.title());
        assertEquals(Map.of("points", 3), stored.fields());
        assertEquals(f.column("backlog"), stored.columnId());
        assertEquals(null, stored.sprintId());
    }

    @Test
    void createAppendsOneItemCreatedEventWithTheOrigin() {
        Fixture f = newWorkspace();
        long before = head(f);
        Item item = create(f, "Evented");
        assertEquals(before + 1, head(f));
        JsonObject event = lastEvent(f);
        assertEquals("item.created", event.getString("type"));
        assertEquals("tab-1", event.getString("origin"));
        assertEquals(item.key(), event.getJsonObject("item").getString("key"));
        assertEquals(1L, event.getJsonObject("item").getLong("version"));
    }

    @Test
    void aCreateOnAColumnOfAnotherBoardDrawsNoNumber() {
        Fixture f = newWorkspace();
        Board other = boards.insert(Board.create(f.workspace().id(), "Other",
                List.of(BoardColumn.create("triage", "Triage", 0)))).await().indefinitely();
        NewItem misplaced = new NewItem(f.workspace().id(), f.board().id(), other.columns().getFirst().id(), "Lost",
                null, Map.of());
        assertInstanceOf(PlacementException.class, failure(items.create(misplaced, null)));
        assertEquals(0, counter(f));
    }

    @Test
    void aCreateNamingAnotherWorkspaceForTheBoardIsAPlacementError() {
        Fixture f = newWorkspace();
        Fixture other = newWorkspace();
        NewItem foreign = new NewItem(other.workspace().id(), f.board().id(), f.column("backlog"), "Foreign", null,
                Map.of());
        assertInstanceOf(PlacementException.class, failure(items.create(foreign, null)));
        assertEquals(0, counter(f));
        assertEquals(0, counter(other));
    }

    // --- versioned move and delete ---------------------------------------------------------------

    @Test
    void aMoveAtTheStoredVersionMovesTheItemAndRaisesItsVersion() {
        Fixture f = newWorkspace();
        Item item = create(f, "Moving");
        Item moved = items.move(item, f.column("done"), "z", 1, "tab-1").await().indefinitely();
        assertEquals(f.column("done"), moved.columnId());
        assertEquals("z", moved.rank());
        assertEquals(2, moved.version());
        assertEquals("item.moved", lastEvent(f).getString("type"));
    }

    @Test
    void aMoveAtAnotherVersionIsAConflictAndWritesNothing() {
        Fixture f = newWorkspace();
        Item item = create(f, "Stale move");
        long before = head(f);
        ItemVersionConflictException conflict = assertInstanceOf(ItemVersionConflictException.class,
                failure(items.move(item, f.column("done"), "z", 7, null)));
        assertEquals(7, conflict.expected());
        assertEquals(1, conflict.actual());
        assertEquals(item, stored(item).orElseThrow());
        assertEquals(before, head(f));
    }

    @Test
    void aMoveToAColumnOfAnotherBoardAtTheStoredVersionIsAPlacementError() {
        Fixture f = newWorkspace();
        Item item = create(f, "Misplaced move");
        Board other = boards.insert(Board.create(f.workspace().id(), "Other",
                List.of(BoardColumn.create("triage", "Triage", 0)))).await().indefinitely();
        assertInstanceOf(PlacementException.class,
                failure(items.move(item, other.columns().getFirst().id(), "z", 1, null)));
        assertEquals(item, stored(item).orElseThrow());
    }

    @Test
    void aDeleteAtTheStoredVersionDeletesAndLeavesATombstone() {
        Fixture f = newWorkspace();
        Item item = create(f, "Deleting");
        items.delete(item, 1, "tab-1").await().indefinitely();
        assertTrue(stored(item).isEmpty());
        JsonObject event = lastEvent(f);
        assertEquals("item.deleted", event.getString("type"));
        assertEquals(2L, event.getJsonObject("item").getLong("version"));
    }

    @Test
    void aDeleteAtAnotherVersionIsAConflictAndDeletesNothing() {
        Fixture f = newWorkspace();
        Item item = create(f, "Stale delete");
        long before = head(f);
        ItemVersionConflictException conflict = assertInstanceOf(ItemVersionConflictException.class,
                failure(items.delete(item, 2, null)));
        assertEquals(1, conflict.actual());
        assertEquals(item, stored(item).orElseThrow());
        assertEquals(before, head(f));
    }

    @Test
    void aDeleteOfAnItemAlreadyGoneIsNotFound() {
        Fixture f = newWorkspace();
        Item item = create(f, "Gone");
        items.delete(item, 1, null).await().indefinitely();
        assertInstanceOf(ItemNotFoundException.class, failure(items.delete(item, 1, null)));
    }

    // --- findPlaced ------------------------------------------------------------------------------

    @Test
    void findPlacedAnswersTheItemWithTheKeysOfItsBoardAndColumn() {
        Fixture f = newWorkspace();
        Item item = create(f, "Placed");
        PlacedItem placed = items.findPlaced(f.workspace().id(), item.key()).await().indefinitely().orElseThrow();
        assertEquals(item, placed.item());
        assertEquals(f.workspace().key(), placed.boardKey());
        assertEquals("backlog", placed.columnKey());
        assertEquals(null, placed.sprintKey());
    }

    @Test
    void findPlacedDoesNotFindAnItemOfAnotherWorkspace() {
        Fixture f = newWorkspace();
        Fixture other = newWorkspace();
        Item item = create(f, "Mine");
        assertTrue(items.findPlaced(other.workspace().id(), item.key()).await().indefinitely().isEmpty());
    }

    @Test
    void findPlacedDoesNotFindAnUnknownKey() {
        Fixture f = newWorkspace();
        assertTrue(items.findPlaced(f.workspace().id(), f.workspace().key() + "-404").await().indefinitely()
                .isEmpty());
    }

    // --- a workspace bound to a backlog source ---------------------------------------------------

    /** One row per single-item write of {@link ItemRepository}, each overload its own row. */
    static Stream<Arguments> clientWrites() {
        return Stream.<Map.Entry<String, BiFunction<ItemRepository, Bound, Uni<?>>>>of(
                Map.entry("insert(Item)", (r, b) -> r.insert(b.fresh())),
                Map.entry("insert(Item,String)", (r, b) -> r.insert(b.fresh(), "tab-1")),
                Map.entry("create(NewItem,String)", (r, b) -> r.create(b.fixture().draft("New"), "tab-1")),
                Map.entry("move(Item,UUID,String)", (r, b) -> r.move(b.item(), b.fixture().column("done"), "z")),
                Map.entry("move(Item,UUID,String,String)",
                        (r, b) -> r.move(b.item(), b.fixture().column("done"), "z", "tab-1")),
                Map.entry("move(Item,UUID,String,long,String)",
                        (r, b) -> r.move(b.item(), b.fixture().column("done"), "z", 1, "tab-1")),
                Map.entry("moveToSprint(Item,UUID)", (r, b) -> r.moveToSprint(b.item(), null)),
                Map.entry("moveToSprint(Item,UUID,String)", (r, b) -> r.moveToSprint(b.item(), null, "tab-1")),
                Map.entry("edit(Item,long,String)", (r, b) -> r.edit(b.item().withTitle("Edited"), 1, "tab-1")),
                Map.entry("delete(Item,String)", (r, b) -> r.delete(b.item(), "tab-1")),
                Map.entry("delete(Item,long,String)", (r, b) -> r.delete(b.item(), 1, "tab-1")))
                .map(row -> Arguments.of(row.getKey(), row.getValue()));
    }

    private record Bound(Fixture fixture, Item item) {

        Item fresh() {
            return Item.create(fixture.workspace().id(), fixture.board().id(), fixture.column("backlog"),
                    fixture.workspace().key() + "-99", "Fresh", "r");
        }
    }

    /** A workspace with one item, then bound to the vault. */
    private Bound bound() {
        Fixture f = newWorkspace();
        Item item = create(f, "Imported");
        workspaces.bindToSource(f.workspace().id(), "vault").await().indefinitely();
        return new Bound(f, item);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clientWrites")
    void aClientWriteOnABoundWorkspaceIsRefusedAndWritesNothing(String write,
                                                               BiFunction<ItemRepository, Bound, Uni<?>> call) {
        Bound b = bound();
        long head = head(b.fixture());
        long counter = counter(b.fixture());

        SourceBoundWorkspaceException refused = assertInstanceOf(SourceBoundWorkspaceException.class,
                failure(call.apply(items, b)));

        assertEquals("vault", refused.source());
        assertEquals(b.item(), stored(b.item()).orElseThrow(), "the item is as it was");
        assertEquals(head, head(b.fixture()), "no event");
        assertEquals(counter, counter(b.fixture()), "no key drawn");
        assertTrue(items.findPlaced(b.fixture().workspace().id(), b.fixture().workspace().key() + "-99")
                .await().indefinitely().isEmpty(), "nothing inserted");
    }

    /** The guard on the matrix above: a new single-item write without a row fails here. */
    @Test
    void everySingleItemWriteHasARowInTheBoundMatrix() {
        Set<String> rows = clientWrites().map(a -> (String) a.get()[0]).collect(Collectors.toSet());
        Set<String> writes = Arrays.stream(ItemRepository.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && m.getReturnType() == Uni.class)
                .filter(m -> !m.getName().matches("(find|page|snapshot|insertAll|upsertAll).*"))
                .map(ClientWritePathTest::signature)
                .collect(Collectors.toSet());
        assertEquals(writes, rows);
    }

    private static String signature(Method m) {
        return m.getName() + Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName)
                .collect(Collectors.joining(",", "(", ")"));
    }

    @Test
    void theImportStillWritesABoundWorkspace() {
        Bound b = bound();
        ImportResult result = items.upsertAll(b.fixture().board().id(), List.of(new ImportedItem(b.item().key(),
                "Renamed at the source", "done", b.item().rank(), Map.of(), null)), "vault-import")
                .await().indefinitely();
        assertEquals(1, result.updated());
        assertEquals("Renamed at the source", stored(b.item()).orElseThrow().title());
    }

    @Test
    void aWorkspaceIsOwnedUntilItIsBound() {
        Fixture f = newWorkspace();
        assertEquals(null, workspaces.findSummary(f.workspace().key()).await().indefinitely().orElseThrow().source());
    }

    @Test
    void bindingNamesTheSource() {
        Fixture f = newWorkspace();
        workspaces.bindToSource(f.workspace().id(), "vault").await().indefinitely();
        WorkspaceSummary ws = workspaces.findSummary(f.workspace().key()).await().indefinitely().orElseThrow();
        assertEquals("vault", ws.source());
        assertTrue(ws.readOnly());
    }

    @Test
    void aBoundWorkspaceKeepsItsFirstSource() {
        Fixture f = newWorkspace();
        workspaces.bindToSource(f.workspace().id(), "vault").await().indefinitely();
        workspaces.bindToSource(f.workspace().id(), "other").await().indefinitely();
        assertEquals("vault", workspaces.findSummary(f.workspace().key()).await().indefinitely().orElseThrow().source());
    }
}
