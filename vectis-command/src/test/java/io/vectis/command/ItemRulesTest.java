// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vectis.domain.template.Violation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The rules an item's own values follow: one value per row, kept or refused. */
class ItemRulesTest {

    // --- title -----------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"A", " padded ", "Ünïcödé", "a title with spaces"})
    void aTitleWithTextIsKept(String title) {
        assertEquals(List.of(), title(title));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t", "\n", " "})
    void aMissingOrBlankTitleIsRefused(String title) {
        assertEquals(List.of(new Violation("title", "workspace", "an item has a title that is not blank", "required")), title(title));
    }

    @Test
    void aTitleOfTheLongestLengthIsKept() {
        assertEquals(List.of(), title("t".repeat(ItemRules.TITLE_MAX)));
    }

    @Test
    void aTitleOneCharacterTooLongIsRefused() {
        assertEquals(List.of(new Violation("title", "workspace", "a title has at most 500 characters", "tooLong")),
                title("t".repeat(ItemRules.TITLE_MAX + 1)));
    }

    @Test
    void aCharacterOutsideTheBasicPlaneCountsOnce() {
        assertEquals(List.of(), title("🚀".repeat(ItemRules.TITLE_MAX)));
    }

    // --- rank ------------------------------------------------------------------------------------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "a", "z", "00000042", "k9m", "0123456789abcdefghijklmnopqrstuvwxyz"})
    void aRankOfDigitsAndLowerCaseLettersOrNoneIsKept(String rank) {
        assertEquals(List.of(), rank(rank));
    }

    @Test
    void aRankOfTheLongestLengthIsKept() {
        assertEquals(List.of(), rank("m".repeat(128)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "A", "Kz", "a-b", "a b", " a", "a.b", "ä", "１"})
    void aRankWithAnyOtherCharacterIsRefused(String rank) {
        List<Violation> refused = rank(rank);
        assertEquals(1, refused.size(), refused::toString);
        assertEquals("rank", refused.getFirst().field());
    }

    @Test
    void aRankOneCharacterTooLongIsRefused() {
        assertEquals(1, rank("m".repeat(129)).size());
    }

    // --- field names -----------------------------------------------------------------------------

    static Stream<Arguments> keptFields() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("gone", null);
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(Map.of()),
                Arguments.of(Map.of("storyPoints", 3)),
                Arguments.of(withNull));
    }

    @ParameterizedTest
    @MethodSource("keptFields")
    void namedFieldsOrNoneAreKept(Map<String, Object> fields) {
        assertEquals(List.of(), fieldNames(fields));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void aFieldWithABlankNameIsRefused(String name) {
        assertEquals(List.of(new Violation("fields", "workspace", "a field has a name that is not blank")),
                fieldNames(Map.of(name, 1)));
    }

    private static List<Violation> title(String title) {
        List<Violation> out = new ArrayList<>();
        ItemRules.title(title, out);
        return out;
    }

    private static List<Violation> rank(String rank) {
        List<Violation> out = new ArrayList<>();
        ItemRules.rank(rank, out);
        return out;
    }

    private static List<Violation> fieldNames(Map<String, Object> fields) {
        List<Violation> out = new ArrayList<>();
        ItemRules.fieldNames(fields, out);
        return out;
    }
}
