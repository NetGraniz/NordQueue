package com.nordfjell.nordqueue;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BanDatabaseReaderTest {
    @TempDir Path directory;

    @Test
    void readsNordBansDatabaseWithoutWritingIt() throws Exception {
        Path database = directory.resolve("bans.db");
        long expires = System.currentTimeMillis() + 60_000;
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE bans (normalized_name TEXT PRIMARY KEY, player_name TEXT NOT NULL, expires_at INTEGER NOT NULL, reason TEXT NOT NULL, actor TEXT NOT NULL)");
            try (var insert = connection.prepareStatement("INSERT INTO bans VALUES (?,?,?,?,?)")) {
                insert.setString(1, "playerone");
                insert.setString(2, "PlayerOne");
                insert.setLong(3, expires);
                insert.setString(4, "Test ban");
                insert.setString(5, "Console");
                insert.executeUpdate();
            }
        }

        BanDatabaseReader reader = new BanDatabaseReader(database);
        reader.refresh();
        assertTrue(reader.ready(), reader.problem());
        assertEquals("Test ban", reader.active("PLAYERONE").orElseThrow().reason());
        reader.close();
        assertFalse(reader.ready());
    }

    @Test
    void failsClosedWhenDatabaseIsUnavailable() {
        BanDatabaseReader reader = new BanDatabaseReader(directory.resolve("missing.db"));
        reader.refresh();
        assertFalse(reader.ready());
        assertTrue(reader.active("Player").isEmpty());
    }
}
