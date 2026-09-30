// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

import io.vectis.extension.spi.BacklogSnapshot;
import io.vectis.extension.spi.BacklogSource;
import io.vectis.extension.spi.BacklogSourceException;
import io.vectis.extension.spi.SourceItem;
import io.vectis.extension.spi.SourceSprint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A {@link BacklogSource} over one space of a {@code .vault/} directory: one Markdown
 * file per item with a YAML front-matter header, where the directory the file sits in
 * ({@code raw/}, {@code wiki/}, {@code output/}) is its status tier.
 *
 * <p>Each read lists {@code <space>/raw}, {@code wiki}, {@code output} and
 * {@code sprints} without descending into subdirectories, skips the generated
 * {@code _index.md} files, and takes the key prefix from {@code _backlog-meta.yaml}.
 * The input is untrusted — a vault is contributed through public pull requests — so
 * files are read under the limits in {@link VaultFiles}, and every file that is
 * skipped or degraded is named in {@link BacklogSnapshot#problems()}, with at most
 * 16 problems for one file. A read also stops after 20,000 directory entries, 64 MiB of
 * files or 10,000 problems in total, and then marks the snapshot incomplete with a
 * {@link BacklogSnapshot#FATAL} problem.</p>
 *
 * <p>Plain Java with no framework: the vault location comes from the caller's
 * configuration, never from a request.</p>
 */
public final class VaultBacklogSource implements BacklogSource {

    /** The id this source is selected by. */
    public static final String ID = "vault";

    private static final Pattern SPACE = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern KEY_PREFIX = Pattern.compile("[A-Z][A-Z0-9]{1,9}");
    private static final String INDEX = "_index.md";
    private static final String META = "_backlog-meta.yaml";
    private static final String SPRINTS = "sprints";
    private static final int MAX_ENTRIES = 20_000;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    private static final int MAX_PROBLEMS = 10_000;
    static final int MAX_PROBLEMS_PER_FILE = 16;

    private final Path vaultRoot;
    private final String space;
    private final String originBase;
    private final int maxEntries;
    private final long maxTotalBytes;
    private final int maxProblems;

    /**
     * @param vaultRoot the {@code .vault} directory
     * @param space     the space to read, a lower-case directory name such as {@code "vec"}
     */
    public VaultBacklogSource(Path vaultRoot, String space) {
        this(vaultRoot, space, MAX_ENTRIES, MAX_TOTAL_BYTES, MAX_PROBLEMS);
    }

    VaultBacklogSource(Path vaultRoot, String space, int maxEntries, long maxTotalBytes, int maxProblems) {
        Objects.requireNonNull(vaultRoot, "vaultRoot");
        Objects.requireNonNull(space, "space");
        if (!SPACE.matcher(space).matches()) {
            throw new IllegalArgumentException("space must be a lower-case directory name: " + space);
        }
        this.vaultRoot = vaultRoot.toAbsolutePath().normalize();
        this.space = space;
        Path rootName = this.vaultRoot.getFileName();
        this.originBase = (rootName == null ? "" : Text.escape(rootName.toString()) + "/") + space;
        this.maxEntries = maxEntries;
        this.maxTotalBytes = maxTotalBytes;
        this.maxProblems = maxProblems;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean available() {
        return Files.isDirectory(spaceRoot(), LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public BacklogSnapshot read() {
        Path root = spaceRoot();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new BacklogSourceException("vault space '" + space + "' is not a directory under the vault root");
        }
        var problems = new ArrayList<String>();
        var budget = new ReadBudget(maxEntries, maxTotalBytes, maxProblems);
        String keyPrefix = keyPrefix(root, budget, problems);
        var mapper = new EntryMapper(keyPrefix);

        var items = new LinkedHashMap<String, SourceItem>();
        for (Tier tier : Tier.values()) {
            for (Path file : markdownFiles(root.resolve(tier.directory()), tier.directory(), true, budget, problems)) {
                if (budget.exhausted()) {
                    break;
                }
                String origin = origin(tier.directory(), file);
                parse(file, origin, budget, problems,
                        (entry, report) -> mapper.item(entry, tier, fileName(file), origin, report))
                        .ifPresent(item -> {
                            SourceItem stale = items.put(item.key(), item);
                            if (stale != null) {
                                problems.add(origin + ": " + item.key() + " is also at " + stale.origin()
                                        + "; the further tier wins and that copy is ignored");
                            }
                        });
            }
        }

        var sprints = new ArrayList<SourceSprint>();
        for (Path file : markdownFiles(root.resolve(SPRINTS), SPRINTS, false, budget, problems)) {
            if (budget.exhausted()) {
                break;
            }
            String origin = origin(SPRINTS, file);
            parse(file, origin, budget, problems,
                    (entry, report) -> mapper.sprint(entry, fileName(file), origin, report))
                    .ifPresent(sprints::add);
        }

        if (budget.exhausted()) {
            problems.add(BacklogSnapshot.FATAL + originBase + ": stopped reading after " + budget.exceeded()
                    + "; the snapshot is incomplete");
        }
        List<SourceItem> ordered = items.values().stream().sorted(Comparator.comparing(
                SourceItem::key, Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder())))
                .toList();
        sprints.sort(Comparator.comparing(SourceSprint::key));
        return new BacklogSnapshot(keyPrefix, ordered, sprints, problems);
    }

    /** The prefix from {@code idPolicy.prefix}, or the upper-cased space name when the meta file does not say. */
    private String keyPrefix(Path root, ReadBudget budget, List<String> problems) {
        String origin = originBase + "/" + META;
        String fallback = space.toUpperCase(Locale.ROOT);
        String prefix = fallback;
        Path meta = root.resolve(META);
        if (!Files.exists(meta, LinkOption.NOFOLLOW_LINKS)) {
            problems.add(origin + ": missing; key prefix taken from the space name as " + fallback);
        } else {
            try {
                Object idPolicy = VaultFiles.mapping(VaultFiles.read(meta, budget), "meta file").get("idPolicy");
                if (idPolicy instanceof Map<?, ?> policy && policy.get("prefix") instanceof String declared) {
                    prefix = declared.strip();
                } else {
                    problems.add(origin + ": no 'idPolicy.prefix'; key prefix taken from the space name as "
                            + fallback);
                }
            } catch (VaultFiles.Unreadable e) {
                problems.add(origin + ": " + e.getMessage() + "; key prefix taken from the space name as " + fallback);
            }
        }
        if (!KEY_PREFIX.matcher(prefix).matches()) {
            throw new BacklogSourceException("vault space '" + space + "' has key prefix " + Text.quote(prefix)
                    + ", which is not 2-10 upper-case letters or digits starting with a letter");
        }
        return prefix;
    }

    /**
     * The {@code .md} files directly in {@code dir}, in name order, without {@code _index.md}.
     * Listing stops once the budget's entry allowance is spent.
     */
    private List<Path> markdownFiles(Path dir, String label, boolean required, ReadBudget budget,
                                     List<String> problems) {
        String origin = originBase + "/" + label + "/";
        if (budget.exhausted()) {
            return List.of();
        }
        if (Files.isSymbolicLink(dir)) {
            problems.add(origin + ": a symbolic link, not followed; skipped");
            return List.of();
        }
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            if (required) {
                problems.add(origin + ": directory is missing");
            }
            return List.of();
        }
        List<Path> listed;
        try (Stream<Path> entries = Files.list(dir)) {
            listed = entries.limit(budget.remainingEntries() + 1L).toList();
        } catch (IOException e) {
            problems.add(origin + ": directory cannot be listed; skipped");
            return List.of();
        }
        budget.listed(listed.size());
        return listed.stream()
                .filter(path -> fileName(path).endsWith(".md") && !fileName(path).equals(INDEX))
                .filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .sorted(Comparator.comparing(VaultBacklogSource::fileName))
                .toList();
    }

    /**
     * Reads and maps one file, reporting at most {@value #MAX_PROBLEMS_PER_FILE} problems for it
     * and charging them to the budget. A file that cannot be read or parsed becomes exactly one
     * problem. So does one the mapping fails on, which is a fault in this reader rather than in
     * the file, so the problem names the exception. Either way the rest of the read goes on.
     */
    static <T> Optional<T> parse(Path file, String origin, ReadBudget budget, List<String> problems,
                                 BiFunction<VaultFiles.Entry, Consumer<String>, Optional<T>> mapping) {
        Optional<T> result = Optional.empty();
        try {
            VaultFiles.Entry entry = VaultFiles.entry(VaultFiles.read(file, budget));
            var reported = new ArrayList<String>();
            try {
                result = mapping.apply(entry, reported::add);
                if (reported.size() > MAX_PROBLEMS_PER_FILE) {
                    int shown = MAX_PROBLEMS_PER_FILE - 1;
                    problems.addAll(reported.subList(0, shown));
                    problems.add(origin + ": " + (reported.size() - shown) + " more problems not listed");
                } else {
                    problems.addAll(reported);
                }
            } catch (RuntimeException | StackOverflowError e) {
                problems.add(origin + ": the reader failed on this file (" + e.getClass().getSimpleName()
                        + "); skipped");
            }
        } catch (VaultFiles.Unreadable e) {
            problems.add(origin + ": " + e.getMessage() + "; skipped");
        }
        budget.reported(problems.size());
        return result;
    }

    private Path spaceRoot() {
        return vaultRoot.resolve(space);
    }

    private String origin(String directory, Path file) {
        return originBase + "/" + directory + "/" + Text.escape(fileName(file));
    }

    private static String fileName(Path path) {
        return String.valueOf(path.getFileName());
    }
}
