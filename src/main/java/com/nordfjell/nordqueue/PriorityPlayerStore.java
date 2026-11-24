package com.nordfjell.nordqueue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** SQLite-backed priority roster. */
final class PriorityPlayerStore {
    record Snapshot(Set<UUID> uuids, Set<String> names) {
        boolean contains(String identity) {
            String normalized = normalize(identity);
            try { return uuids.contains(UUID.fromString(normalized)); }
            catch (IllegalArgumentException ignored) { return names.contains(normalized); }
        }
        int size() { return uuids.size() + names.size(); }
    }
    record Update(Snapshot snapshot, boolean changed) { }

    private final Path databaseFile;

    PriorityPlayerStore(Path databaseFile) {
        this.databaseFile = databaseFile.toAbsolutePath().normalize();
    }

    synchronized Snapshot load() throws IOException {
        try (Connection connection = open()) {
            initialize(connection);
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
        }
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
