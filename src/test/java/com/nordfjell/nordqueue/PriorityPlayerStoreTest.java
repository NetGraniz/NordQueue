package com.nordfjell.nordqueue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PriorityPlayerStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void addAndRemoveArePersistentAndIdempotent() throws Exception {
        Path file = temporaryDirectory.resolve("priority-players.txt");
        PriorityPlayerStore store = new PriorityPlayerStore(file);

        assertTrue(store.add("ExamplePlayer").changed());
        assertFalse(store.add("exampleplayer").changed());
        assertTrue(store.load().names().contains("exampleplayer"));

        assertTrue(store.remove("EXAMPLEPLAYER").changed());
        assertFalse(store.remove("exampleplayer").changed());
        assertFalse(store.load().contains("exampleplayer"));
    }

    @Test
    void legacyRosterMigratesOnceAndRemainsUntouched() throws Exception {
        Path file = temporaryDirectory.resolve("priority-players.txt");
        UUID uuid = UUID.randomUUID();
        Files.writeString(file, "# custom note\n" + uuid + "\n", StandardCharsets.UTF_8);
        PriorityPlayerStore store = new PriorityPlayerStore(file);

        assertTrue(store.add("Bedrock.Player").changed());
        assertEquals(2, store.load().size());
        String legacy = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(legacy.contains("# custom note"));
        assertTrue(store.remove(uuid.toString()).changed());
        assertTrue(store.load().contains("bedrock.player"));
        assertEquals(legacy, Files.readString(file, StandardCharsets.UTF_8));
        Files.writeString(file, legacy + "LateLegacyPlayer\n", StandardCharsets.UTF_8);
        assertFalse(store.load().contains("latelegacyplayer"));
    }

    @Test
    void rejectsEntriesThatCouldCorruptTheRoster() {
        assertThrows(IllegalArgumentException.class, () -> PriorityPlayerStore.normalize(""));
        assertThrows(IllegalArgumentException.class, () -> PriorityPlayerStore.normalize("#comment"));
        assertThrows(IllegalArgumentException.class, () -> PriorityPlayerStore.normalize("player\nother"));
    }
}
