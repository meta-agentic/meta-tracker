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
 * skipped or degraded is named in {@link BacklogSnapshot#problems()}.</p>
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

    private final Path vaultRoot;
    private final String space;
    private final String originBase;

    /**
     * @param vaultRoot the {@code .vault} directory
     * @param space     the space to read, a lower-case directory name such as {@code "vec"}
     */
    public VaultBacklogSource(Path vaultRoot, String space) {
        Objects.requireNonNull(vaultRoot, "vaultRoot");
        Objects.requireNonNull(space, "space");
        if (!SPACE.matcher(space).matches()) {
            throw new IllegalArgumentException("space must be a lower-case directory name: " + space);
        }
        this.vaultRoot = vaultRoot.toAbsolutePath().normalize();
        this.space = space;
        Path rootName = this.vaultRoot.getFileName();
        this.originBase = (rootName == null ? "" : rootName + "/") + space;
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
        String keyPrefix = keyPrefix(root, problems);
        var mapper = new EntryMapper(keyPrefix);

        var items = new LinkedHashMap<String, SourceItem>();
        for (Tier tier : Tier.values()) {
            for (Path file : markdownFiles(root.resolve(tier.directory()), tier.directory(), true, problems)) {
                String origin = origin(tier.directory(), file);
                load(file, origin, problems)
                        .flatMap(entry -> mapper.item(entry, tier, fileName(file), origin, problems::add))
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
        for (Path file : markdownFiles(root.resolve(SPRINTS), SPRINTS, false, problems)) {
            String origin = origin(SPRINTS, file);
            load(file, origin, problems)
                    .flatMap(entry -> mapper.sprint(entry, fileName(file), origin, problems::add))
                    .ifPresent(sprints::add);
        }

        List<SourceItem> ordered = items.values().stream().sorted(Comparator.comparing(
                SourceItem::key, Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder())))
                .toList();
        sprints.sort(Comparator.comparing(SourceSprint::key));
        return new BacklogSnapshot(keyPrefix, ordered, sprints, problems);
    }

    /** The prefix from {@code idPolicy.prefix}, or the upper-cased space name when the meta file does not say. */
    private String keyPrefix(Path root, List<String> problems) {
        String origin = originBase + "/" + META;
        String fallback = space.toUpperCase(Locale.ROOT);
        String prefix = fallback;
        Path meta = root.resolve(META);
        if (!Files.exists(meta, LinkOption.NOFOLLOW_LINKS)) {
            problems.add(origin + ": missing; key prefix taken from the space name as " + fallback);
        } else {
            try {
                Object idPolicy = VaultFiles.mapping(VaultFiles.read(meta), "meta file").get("idPolicy");
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
            throw new BacklogSourceException("vault space '" + space + "' has key prefix '" + prefix
                    + "', which is not 2-10 upper-case letters or digits starting with a letter");
        }
        return prefix;
    }

    /** The {@code .md} files directly in {@code dir}, in name order, without {@code _index.md}. */
    private List<Path> markdownFiles(Path dir, String label, boolean required, List<String> problems) {
        String origin = originBase + "/" + label + "/";
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
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(path -> fileName(path).endsWith(".md") && !fileName(path).equals(INDEX))
                    .filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(VaultBacklogSource::fileName))
                    .toList();
        } catch (IOException e) {
            problems.add(origin + ": directory cannot be listed; skipped");
            return List.of();
        }
    }

    private static Optional<VaultFiles.Entry> load(Path file, String origin, List<String> problems) {
        try {
            return Optional.of(VaultFiles.entry(VaultFiles.read(file)));
        } catch (VaultFiles.Unreadable e) {
            problems.add(origin + ": " + e.getMessage() + "; skipped");
            return Optional.empty();
        }
    }

    private Path spaceRoot() {
        return vaultRoot.resolve(space);
    }

    private String origin(String directory, Path file) {
        return originBase + "/" + directory + "/" + fileName(file);
    }

    private static String fileName(Path path) {
        return String.valueOf(path.getFileName());
    }
}
