// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

import io.vectis.extension.spi.SourceItem;
import io.vectis.extension.spi.SourceSprint;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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

    /** The most entries a list field keeps; the rest are cut and reported. */
    static final int MAX_LIST_ENTRIES = 256;

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
            problems.accept(origin + ": id " + Text.quote(key) + " is not of the form " + keyPrefix + "-<number>; skipped");
            return Optional.empty();
        }
        if (!fileName.equals(key + ".md")) {
            problems.accept(origin + ": file name does not match id " + Text.quote(key) + "; skipped");
            return Optional.empty();
        }
        String title = text(fields.get("title"));
        if (title.isEmpty()) {
            problems.accept(origin + ": 'title' is missing or not text; skipped");
            return Optional.empty();
        }
        String status = text(fields.get("status"));
        Optional<VaultStatus> placed = placed(status, tier, origin, problems);
        var links = new LinkedHashMap<String, List<String>>();
        putLinks(links, SourceItem.DEPENDS_ON, keys(fields, "dependencies", origin, problems));
        putLinks(links, SourceItem.RELATES_TO, keys(fields, "relates", origin, problems));
        return Optional.of(new SourceItem(
                key,
                title,
                kind,
                status,
                placed.isPresent() ? placed.get().category() : tier.category(),
                placed.isPresent() ? placed.get().outcome() : tier.outcome(),
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
            problems.accept(origin + ": file name does not match sprintId " + Text.quote(key) + "; skipped");
            return Optional.empty();
        }
        return Optional.of(new SourceSprint(
                key,
                sprintState(text(fields.get("state")), origin, problems),
                date(fields, "start", origin, problems),
                date(fields, "end", origin, problems),
                date(fields, "closed", origin, problems)));
    }

    /**
     * The status that places the item, when it is known and belongs in the item's tier.
     * Otherwise the directory wins, category and outcome alike, and the reason is reported:
     * an item in {@code output/} without a status of its own is taken as delivered. The
     * exception is {@code NO GO}, which places the item wherever it is filed, as ended and
     * discontinued, because an aborted item is never delivered or not started; a misfiled
     * one is still reported.
     */
    private static Optional<VaultStatus> placed(String status, Tier tier, String origin, Consumer<String> problems) {
        String byDirectory = "; placed by its directory " + tier.directory() + "/ as " + tier.placement();
        if (status.isEmpty()) {
            problems.accept(origin + ": 'status' is missing or not text" + byDirectory);
            return Optional.empty();
        }
        Optional<VaultStatus> known = VaultStatus.of(status);
        if (known.isEmpty()) {
            problems.accept(origin + ": unknown status " + Text.quote(status) + byDirectory);
            return Optional.empty();
        }
        if (known.get().tier() != tier && known.get().placesAnywhere()) {
            problems.accept(origin + ": status " + Text.quote(status) + " belongs in " + known.get().tier().directory()
                    + "/, so the item is misfiled; placed by its status as " + known.get().placement());
            return known;
        }
        if (known.get().tier() != tier) {
            problems.accept(origin + ": status " + Text.quote(status) + " belongs in " + known.get().tier().directory()
                    + "/, contradicting its directory" + byDirectory);
            return Optional.empty();
        }
        return known;
    }

    private static SourceSprint.State sprintState(String state, String origin, Consumer<String> problems) {
        return switch (state.toLowerCase(Locale.ROOT)) {
            case "closed" -> SourceSprint.State.CLOSED;
            case "active" -> SourceSprint.State.ACTIVE;
            case "future", "planned" -> SourceSprint.State.FUTURE;
            default -> {
                String reason = state.isEmpty() ? "'state' is missing or not text" : "unknown sprint state " + Text.quote(state);
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
        problems.accept(origin + ": storyPoints " + describe(value) + " is not a non-negative number; left unestimated");
        return null;
    }

    /**
     * A day written as {@code YYYY-MM-DD}. A timestamp with a time of day is refused rather than
     * reduced to a day: which day it falls on depends on a zone the field does not name.
     */
    private static LocalDate date(Map<String, Object> fields, String name, String origin, Consumer<String> problems) {
        Object value = fields.get(name);
        if (value == null) {
            return null;
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

    /**
     * A list of short strings, or a single one. A list longer than {@value #MAX_LIST_ENTRIES} is
     * cut, and entries that are not text, blank and null ones included, are dropped. Each is
     * reported once for the field, with a count, so a hostile list costs at most two problems.
     */
    private static List<String> keys(Map<String, Object> fields, String name, String origin,
                                     Consumer<String> problems) {
        Object value = fields.get(name);
        if (value == null) {
            return List.of();
        }
        List<?> values = value instanceof List<?> list ? list : List.of(value);
        if (values.size() > MAX_LIST_ENTRIES) {
            problems.accept(origin + ": '" + name + "' has " + values.size() + " entries; only the first "
                    + MAX_LIST_ENTRIES + " are kept");
            values = values.subList(0, MAX_LIST_ENTRIES);
        }
        var keys = new ArrayList<String>();
        int dropped = 0;
        for (Object element : values) {
            String text = element instanceof Number number ? number.toString() : text(element);
            if (text.isEmpty()) {
                dropped++;
            } else {
                keys.add(text);
            }
        }
        if (dropped > 0) {
            String count = dropped == 1 ? "1 entry that is" : dropped + " entries that are";
            problems.accept(origin + ": '" + name + "' has " + count + " not text; dropped");
        }
        return keys;
    }

    private static void putLinks(Map<String, List<String>> links, String type, List<String> targets) {
        if (!targets.isEmpty()) {
            links.put(type, targets);
        }
    }

    /**
     * A bounded, escaped rendering of a scalar for a problem report. Collections are named, never
     * rendered: a self-referencing one would recurse without end.
     */
    private static String describe(Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return Text.quote(value.toString());
        }
        return value instanceof List<?> ? "(a list)" : value instanceof Map<?, ?> ? "(a mapping)" : "(a value)";
    }

    /** A YAML string, stripped; empty for anything else, which callers treat as absent. */
    private static String text(Object value) {
        return value instanceof String text ? text.strip() : "";
    }
}
