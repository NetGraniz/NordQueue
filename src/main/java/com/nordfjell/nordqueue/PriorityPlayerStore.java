package com.nordfjell.nordqueue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Persistent priority roster. All disk mutations are serialized and replaced atomically. */
final class PriorityPlayerStore {
    private static final String HEADER = "# NordQueue priority players\n"
            + "# One Minecraft username or UUID per line. Lines starting with # are ignored.\n";

    record Snapshot(Set<UUID> uuids, Set<String> names) {
        boolean contains(String identity) {
            String normalized = normalize(identity);
            try {
                return uuids.contains(UUID.fromString(normalized));
            } catch (IllegalArgumentException ignored) {
                return names.contains(normalized);
            }
        }

        int size() {
            return uuids.size() + names.size();
        }
    }

    record Update(Snapshot snapshot, boolean changed) { }

    private final Path file;

    PriorityPlayerStore(Path file) {
        this.file = file;
    }

    synchronized Snapshot load() throws IOException {
        ensureFile();
        return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    synchronized Update add(String identity) throws IOException {
        String normalized = normalize(identity);
        List<String> lines = readLines();
        Snapshot current = parse(lines);
        if (current.contains(normalized)) return new Update(current, false);
        lines.add(normalized);
        writeAtomically(lines);
        return new Update(parse(lines), true);
    }

    synchronized Update remove(String identity) throws IOException {
        String normalized = normalize(identity);
        List<String> lines = readLines();
        Snapshot current = parse(lines);
        if (!current.contains(normalized)) return new Update(current, false);

        List<String> retained = new ArrayList<>(lines.size());
        for (String sourceLine : lines) {
            String line = sourceLine.trim();
            if (line.isEmpty() || line.startsWith("#") || !sameIdentity(line, normalized)) {
                retained.add(sourceLine);
            }
        }
        writeAtomically(retained);
        return new Update(parse(retained), true);
    }

    static String normalize(String identity) {
        String value = identity == null ? "" : identity.trim();
        if (value.isEmpty() || value.length() > 64 || value.startsWith("#")
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Identity must be a username or UUID of 1 to 64 characters");
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException ignored) {
            return value.toLowerCase(Locale.ROOT);
        }
    }

    private List<String> readLines() throws IOException {
        ensureFile();
        return new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    private void ensureFile() throws IOException {
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) Files.writeString(file, HEADER, StandardCharsets.UTF_8);
    }

    private static Snapshot parse(List<String> lines) {
        Set<UUID> uuids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (String sourceLine : lines) {
            String line = sourceLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String normalized = normalize(line);
            try {
                uuids.add(UUID.fromString(normalized));
            } catch (IllegalArgumentException ignored) {
                names.add(normalized);
            }
        }
        return new Snapshot(Set.copyOf(uuids), Set.copyOf(names));
    }

    private static boolean sameIdentity(String left, String right) {
        try {
            return UUID.fromString(left).equals(UUID.fromString(right));
        } catch (IllegalArgumentException ignored) {
            return left.equalsIgnoreCase(right);
        }
    }

    private void writeAtomically(List<String> lines) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "priority-players-", ".tmp");
        try {
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
