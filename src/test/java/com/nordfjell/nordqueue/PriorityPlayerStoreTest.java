package com.nordfjell.nordqueue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PriorityPlayerStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void addAndRemoveArePersistentAndIdempotent() throws Exception {
        Path file = temporaryDirectory.resolve("priority.db");
        PriorityPlayerStore store = new PriorityPlayerStore(file);

        assertTrue(store.add("ExamplePlayer").changed());
        assertFalse(store.add("exampleplayer").changed());
        assertTrue(store.load().names().contains("exampleplayer"));

        assertTrue(store.remove("EXAMPLEPLAYER").changed());
        assertFalse(store.remove("exampleplayer").changed());
        assertFalse(store.load().contains("exampleplayer"));
    }

    @Test
    void rejectsEntriesThatCouldCorruptTheRoster() {
        assertThrows(IllegalArgumentException.class, () -> PriorityPlayerStore.normalize(""));
        assertThrows(IllegalArgumentException.class, () -> PriorityPlayerStore.normalize("#comment"));
        assertThrows(IllegalArgumentException.class, () -> PriorityPlayerStore.normalize("player\nother"));
    }
}
