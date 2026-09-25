package io.vectis.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectTemplateTest {

    @Test
    void kanbanAndScrumAreTheCatalogue() {
        assertEquals(List.of("kanban", "scrum"),
                List.of(ProjectTemplate.values()).stream().map(ProjectTemplate::id).toList());
    }

    @Test
    void lookupIsByWireIdAndRejectsUnknownOrMiscased() {
        assertEquals(Optional.of(ProjectTemplate.SCRUM), ProjectTemplate.byId("scrum"));
        assertTrue(ProjectTemplate.byId("SCRUM").isEmpty());
        assertTrue(ProjectTemplate.byId("waterfall").isEmpty());
        assertTrue(ProjectTemplate.byId(null).isEmpty());
    }

    @Test
    void instantiateLaysTheTemplateColumnsOutInOrder() {
        UUID workspaceId = TimeOrderedId.next();

        Board board = ProjectTemplate.SCRUM.instantiate(workspaceId);

        assertEquals(workspaceId, board.workspaceId());
        assertEquals(ProjectTemplate.SCRUM.columnNames(),
                board.orderedColumns().stream().map(BoardColumn::name).toList());
        assertEquals(List.of(0, 1, 2, 3, 4),
                board.orderedColumns().stream().map(BoardColumn::position).toList());
    }

    @Test
    void eachInstantiationMintsFreshIdentities() {
        UUID workspaceId = TimeOrderedId.next();

        Board first = ProjectTemplate.KANBAN.instantiate(workspaceId);
        Board second = ProjectTemplate.KANBAN.instantiate(workspaceId);

        assertNotEquals(first.id(), second.id());
        assertNotEquals(first.columns().get(0).id(), second.columns().get(0).id());
    }

    @Test
    void everyTemplateOffersTheEpicIssueType() {
        for (ProjectTemplate template : ProjectTemplate.values()) {
            assertTrue(template.issueTypes().contains(ProjectTemplate.EPIC_ISSUE_TYPE), template.id());
        }
    }
}
