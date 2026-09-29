// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

import io.vectis.extension.spi.SourceItem;
import io.vectis.extension.spi.SourceSprint;
import io.vectis.extension.spi.StatusCategory;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Turns one parsed vault file into a snapshot entry, stating what the file says.
 *
 * <p>A file whose identity cannot be trusted (no id, an id of the wrong form, a file
 * name that disagrees with it, no title) is skipped. A file with a bad optional field
 * is kept with that field left empty. Either way the reason goes to {@code problems}.</p>
 */
final class EntryMapper {

    private final Pattern keyPattern;
    private final String keyPrefix;

    EntryMapper(String keyPrefix) {
        this.keyPrefix = keyPrefix;
        this.keyPattern = Pattern.compile(Pattern.quote(keyPrefix) + "-[0-9]+");
    }

    Optional<SourceItem> item(VaultFiles.Entry entry, Tier tier, String fileName, String origin,
                              Consumer<String> problems) {
        Map<String, Object> fields = entry.fields();
        String kind = text(fields.get("kind")).toLowerCase(Locale.ROOT);
        if (kind.isEmpty()) {
            problems.accept(origin + ": 'kind' is missing or not text, so not an item; skipped");
            return Optional.empty();
        }
        if (kind.equals("adr")) {
            return Optional.empty();
        }
        if (kind.equals("sprint")) {
            problems.accept(origin + ": a sprint file in an item directory; skipped");
            return Optional.empty();
        }
        String key = text(fields.get("id"));
        if (key.isEmpty()) {
            problems.accept(origin + ": 'id' is missing or not text; skipped");
            return Optional.empty();
        }
        if (!keyPattern.matcher(key).matches()) {
            problems.accept(origin + ": id '" + key + "' is not of the form " + keyPrefix + "-<number>; skipped");
            return Optional.empty();
        }
        if (!fileName.equals(key + ".md")) {
            problems.accept(origin + ": file name does not match id '" + key + "'; skipped");
            return Optional.empty();
        }
        String title = text(fields.get("title"));
        if (title.isEmpty()) {
            problems.accept(origin + ": 'title' is missing or not text; skipped");
            return Optional.empty();
        }
        String status = text(fields.get("status"));
        var links = new LinkedHashMap<String, List<String>>();
        putLinks(links, SourceItem.DEPENDS_ON, keys(fields, "dependencies", origin, problems));
        putLinks(links, SourceItem.RELATES_TO, keys(fields, "relates", origin, problems));
        return Optional.of(new SourceItem(
                key,
                title,
                kind,
                status,
                category(status, tier, origin, problems),
                optionalText(fields, "epic", origin, problems),
                optionalText(fields, "sprint", origin, problems),
                points(fields.get("storyPoints"), origin, problems),
                optionalText(fields, "priority", origin, problems),
                keys(fields, "labels", origin, problems),
                links,
                entry.body(),
                origin));
    }

    Optional<SourceSprint> sprint(VaultFiles.Entry entry, String fileName, String origin,
                                  Consumer<String> problems) {
        Map<String, Object> fields = entry.fields();
        if (!text(fields.get("kind")).equalsIgnoreCase("sprint")) {
            problems.accept(origin + ": not 'kind: sprint'; skipped");
            return Optional.empty();
        }
        String key = text(fields.get("sprintId"));
        if (key.isEmpty()) {
            problems.accept(origin + ": 'sprintId' is missing or not text; skipped");
            return Optional.empty();
        }
        if (!fileName.equals(key + ".md")) {
            problems.accept(origin + ": file name does not match sprintId '" + key + "'; skipped");
            return Optional.empty();
        }
        return Optional.of(new SourceSprint(
                key,
                sprintState(text(fields.get("state")), origin, problems),
                date(fields, "start", origin, problems),
                date(fields, "end", origin, problems),
                date(fields, "closed", origin, problems)));
    }

    /** The directory wins whenever the status is missing, unknown or belongs to another tier. */
    private static StatusCategory category(String status, Tier tier, String origin, Consumer<String> problems) {
        String placed = "; placed by its directory " + tier.directory() + "/ as " + tier.category();
        if (status.isEmpty()) {
            problems.accept(origin + ": 'status' is missing or not text" + placed);
            return tier.category();
        }
        Optional<VaultStatus> known = VaultStatus.of(status);
        if (known.isEmpty()) {
            problems.accept(origin + ": unknown status '" + status + "'" + placed);
            return tier.category();
        }
        if (known.get().tier() != tier) {
            problems.accept(origin + ": status '" + status + "' belongs in " + known.get().tier().directory()
                    + "/, contradicting its directory" + placed);
            return tier.category();
        }
        return known.get().category();
    }

    private static SourceSprint.State sprintState(String state, String origin, Consumer<String> problems) {
        return switch (state.toLowerCase(Locale.ROOT)) {
            case "closed" -> SourceSprint.State.CLOSED;
            case "active" -> SourceSprint.State.ACTIVE;
            case "future", "planned" -> SourceSprint.State.FUTURE;
            default -> {
                String reason = state.isEmpty() ? "'state' is missing or not text" : "unknown sprint state '" + state + "'";
                problems.accept(origin + ": " + reason + "; taken as FUTURE");
                yield SourceSprint.State.FUTURE;
            }
        };
    }

    private static Double points(Object value, String origin, Consumer<String> problems) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            double points = number.doubleValue();
            if (Double.isFinite(points) && points >= 0) {
                return points;
            }
        }
        problems.accept(origin + ": storyPoints '" + value + "' is not a non-negative number; left unestimated");
        return null;
    }

    private static LocalDate date(Map<String, Object> fields, String name, String origin, Consumer<String> problems) {
        Object value = fields.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Date date) {
            return date.toInstant().atOffset(ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof String text) {
            try {
                return LocalDate.parse(text.strip());
            } catch (DateTimeParseException e) {
                // reported below
            }
        }
        problems.accept(origin + ": '" + name + "' is not a date (YYYY-MM-DD); left empty");
        return null;
    }

    private static String optionalText(Map<String, Object> fields, String name, String origin,
                                       Consumer<String> problems) {
        Object value = fields.get(name);
        if (value == null) {
            return null;
        }
        String text = text(value);
        if (text.isEmpty()) {
            problems.accept(origin + ": '" + name + "' is not text; left empty");
            return null;
        }
        return text;
    }

    /** A list of short strings, or a single one; anything else is reported and dropped. */
    private static List<String> keys(Map<String, Object> fields, String name, String origin,
                                     Consumer<String> problems) {
        Object value = fields.get(name);
        if (value == null) {
            return List.of();
        }
        List<?> values = value instanceof List<?> list ? list : List.of(value);
        var keys = new ArrayList<String>();
        for (Object element : values) {
            String text = element instanceof Number ? element.toString() : text(element);
            if (text.isEmpty()) {
                problems.accept(origin + ": '" + name + "' has an entry that is not text; entry dropped");
            } else {
                keys.add(text);
            }
        }
        return keys;
    }

    private static void putLinks(Map<String, List<String>> links, String type, List<String> targets) {
        if (!targets.isEmpty()) {
            links.put(type, targets);
        }
    }

    /** A YAML string, stripped; empty for anything else, which callers treat as absent. */
    private static String text(Object value) {
        return value instanceof String text ? text.strip() : "";
    }
}
