// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.representer.Representer;

/**
 * Reading vault files as untrusted input. A vault is contributed through public pull
 * requests, so every limit here bounds what a hostile file can cost: no symbolic
 * link is followed, a file is read up to a fixed size, and YAML is parsed into plain
 * maps with alias, nesting and size limits and duplicate keys refused.
 */
final class VaultFiles {

    static final int MAX_FILE_BYTES = 256 * 1024;

    private static final int MAX_ALIASES = 10;
    private static final int MAX_NESTING = 16;
    private static final String FENCE = "---";
    private static final String BYTE_ORDER_MARK = "﻿";

    /** Front-matter fields and the Markdown below them. */
    record Entry(Map<String, Object> fields, String body) {}

    /** Why a single file was skipped; the message is reported, never thrown further. */
    static final class Unreadable extends Exception {
        Unreadable(String message) {
            super(message);
        }
    }

    private VaultFiles() {
    }

    /** Reads a regular file as strict UTF-8, refusing symbolic links and oversized files. */
    static String read(Path file) throws Unreadable {
        try {
            var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) {
                throw new Unreadable("a symbolic link, not followed");
            }
            if (!attributes.isRegularFile()) {
                throw new Unreadable("not a regular file");
            }
            byte[] bytes;
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = in.readNBytes(MAX_FILE_BYTES + 1);
            }
            if (bytes.length > MAX_FILE_BYTES) {
                throw new Unreadable("larger than " + MAX_FILE_BYTES / 1024 + " KiB");
            }
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new Unreadable("not valid UTF-8");
        } catch (IOException e) {
            throw new Unreadable("unreadable");
        }
    }

    /**
     * Splits a Markdown file into its front-matter — the YAML between a first line of
     * {@code ---} and the next such line — and the body after it.
     */
    static Entry entry(String text) throws Unreadable {
        List<String> lines = (text.startsWith(BYTE_ORDER_MARK) ? text.substring(1) : text).lines().toList();
        if (lines.isEmpty() || !lines.get(0).stripTrailing().equals(FENCE)) {
            throw new Unreadable("no front-matter (the first line is not '---')");
        }
        int close = -1;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).stripTrailing().equals(FENCE)) {
                close = i;
                break;
            }
        }
        if (close < 0) {
            throw new Unreadable("unterminated front-matter (no closing '---')");
        }
        Map<String, Object> fields = mapping(String.join("\n", lines.subList(1, close)), "front-matter");
        String body = String.join("\n", lines.subList(close + 1, lines.size())).strip();
        return new Entry(fields, body);
    }

    /** Parses a YAML document that must be a mapping, e.g. front-matter or a space's meta file. */
    static Map<String, Object> mapping(String yaml, String what) throws Unreadable {
        Object document;
        try {
            document = parser().load(yaml);
        } catch (YAMLException e) {
            throw new Unreadable(what + " is not valid YAML: " + describe(e));
        }
        if (document == null) {
            throw new Unreadable(what + " is empty");
        }
        if (!(document instanceof Map<?, ?> map)) {
            throw new Unreadable(what + " is not a mapping");
        }
        var fields = new LinkedHashMap<String, Object>();
        map.forEach((key, value) -> fields.put(String.valueOf(key), value));
        return fields;
    }

    private static Yaml parser() {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(MAX_ALIASES);
        options.setNestingDepthLimit(MAX_NESTING);
        options.setCodePointLimit(MAX_FILE_BYTES);
        options.setAllowRecursiveKeys(false);
        var dumper = new DumperOptions();
        return new Yaml(new SafeConstructor(options), new Representer(dumper), dumper, options);
    }

    /** The parser's own diagnosis, e.g. "found duplicate key title", without its multi-line context. */
    private static String describe(YAMLException e) {
        if (e instanceof MarkedYAMLException marked && marked.getProblem() != null) {
            return marked.getProblem().strip();
        }
        return firstLine(e.getMessage());
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unparseable";
        }
        String line = message.lines().findFirst().orElse("unparseable").strip();
        return line.isEmpty() ? "unparseable" : line;
    }
}
