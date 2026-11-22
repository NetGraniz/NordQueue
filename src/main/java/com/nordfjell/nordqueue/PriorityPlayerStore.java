package com.nordfjell.nordqueue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** SQLite-backed priority roster with one-time text-file migration. */
final class PriorityPlayerStore {
    private static final String MIGRATION_KEY = "priority_text_v1";

    record Snapshot(Set<UUID> uuids, Set<String> names) {
        boolean contains(String identity) {
            String normalized = normalize(identity);
            try { return uuids.contains(UUID.fromString(normalized)); }
            catch (IllegalArgumentException ignored) { return names.contains(normalized); }
        }
        int size() { return uuids.size() + names.size(); }
    }
    record Update(Snapshot snapshot, boolean changed) { }

    private final Path legacyFile;
    private final Path databaseFile;

    PriorityPlayerStore(Path legacyFile) {
        this.legacyFile = legacyFile.toAbsolutePath().normalize();
        this.databaseFile = this.legacyFile.resolveSibling("priority.db");
    }

    synchronized Snapshot load() throws IOException {
        try (Connection connection = open()) {
            initialize(connection);
            migrateLegacy(connection);
            return readSnapshot(connection);
        } catch (SQLException exception) { throw new IOException("Could not load priority database", exception); }
    }

    synchronized Update add(String identity) throws IOException {
        String normalized = normalize(identity);
        try (Connection connection = open()) {
            initialize(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO priority_players(identity) VALUES (?)")) {
                statement.setString(1, normalized);
                boolean changed = statement.executeUpdate() == 1;
                return new Update(readSnapshot(connection), changed);
            }
        } catch (SQLException exception) { throw new IOException("Could not add priority identity", exception); }
    }

    synchronized Update remove(String identity) throws IOException {
        String normalized = normalize(identity);
        try (Connection connection = open()) {
            initialize(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM priority_players WHERE identity = ?")) {
                statement.setString(1, normalized);
                boolean changed = statement.executeUpdate() == 1;
                return new Update(readSnapshot(connection), changed);
            }
        } catch (SQLException exception) { throw new IOException("Could not remove priority identity", exception); }
    }

    static String normalize(String identity) {
        String value = identity == null ? "" : identity.trim();
        if (value.isEmpty() || value.length() > 64 || value.startsWith("#")
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Identity must be a username or UUID of 1 to 64 characters");
        }
        try { return UUID.fromString(value).toString(); }
        catch (IllegalArgumentException ignored) { return value.toLowerCase(Locale.ROOT); }
    }

    private Connection open() throws SQLException, IOException {
        Files.createDirectories(databaseFile.getParent());
        try { Class.forName("org.sqlite.JDBC"); }
        catch (ClassNotFoundException exception) { throw new SQLException("SQLite driver unavailable", exception); }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private static void initialize(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS priority_players (identity TEXT PRIMARY KEY)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        }
    }

    private void migrateLegacy(Connection connection) throws SQLException, IOException {
        try (PreparedStatement check = connection.prepareStatement("SELECT 1 FROM metadata WHERE key = ?")) {
            check.setString(1, MIGRATION_KEY);
            try (ResultSet result = check.executeQuery()) { if (result.next()) return; }
        }
        connection.setAutoCommit(false);
        try {
            if (Files.isRegularFile(legacyFile)) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT OR IGNORE INTO priority_players(identity) VALUES (?)")) {
                    for (String sourceLine : Files.readAllLines(legacyFile, StandardCharsets.UTF_8)) {
                        String line = sourceLine.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        insert.setString(1, normalize(line));
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
            }
            try (PreparedStatement marker = connection.prepareStatement(
                    "INSERT INTO metadata(key,value) VALUES (?,?)")) {
                marker.setString(1, MIGRATION_KEY);
                marker.setString(2, "complete");
                marker.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | IOException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally { connection.setAutoCommit(true); }
    }

    private static Snapshot readSnapshot(Connection connection) throws SQLException {
        Set<UUID> uuids = new HashSet<>();
        Set<String> names = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT identity FROM priority_players")) {
            while (result.next()) {
                String identity = result.getString(1);
                try { uuids.add(UUID.fromString(identity)); }
                catch (IllegalArgumentException ignored) { names.add(identity); }
            }
        }
        return new Snapshot(Set.copyOf(uuids), Set.copyOf(names));
    }
}
