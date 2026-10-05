package com.nordfjell.nordqueue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Immutable, internally consistent view. Positions retain the existing per-group API. */
public record QueueSnapshot(long version, List<UUID> orderedIds, Map<UUID, Integer> positions,
                            Map<UUID, Integer> absolutePositions, Set<UUID> priorityPlayers,
                            int regularSize, int prioritySize) {
    public QueueSnapshot {
        orderedIds = List.copyOf(orderedIds);
        positions = Map.copyOf(positions);
        absolutePositions = Map.copyOf(absolutePositions);
        priorityPlayers = Set.copyOf(priorityPlayers);
    }

    public int size() { return regularSize + prioritySize; }
    public int position(UUID id) { return positions.getOrDefault(id, 0); }
    public int absolutePosition(UUID id) { return absolutePositions.getOrDefault(id, 0); }
    public boolean isQueued(UUID id) { return positions.containsKey(id); }
    public boolean isPriority(UUID id) { return priorityPlayers.contains(id); }
}
