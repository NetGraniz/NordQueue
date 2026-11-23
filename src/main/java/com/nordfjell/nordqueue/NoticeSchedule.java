package com.nordfjell.nordqueue;

import java.util.*;

/** Session/connection identity, monotonic deadlines, bounded round-robin work. */
final class NoticeSchedule<S> {
    static final int CAPACITY = 4096, SCAN_BUDGET = 256, SEND_BUDGET = 32;
    static final long TICK_MILLIS = 250;
    static final class Entry<S> {
        final UUID id;
        final S owner;
        final Object connection;
        private long due;
        Entry(UUID id, S owner, Object connection, long due) {this.id=id;this.owner=owner;this.connection=connection;this.due=due;}
    }
    private final LinkedHashMap<UUID,Entry<S>> entries = new LinkedHashMap<>();
    private boolean closed;
    synchronized boolean track(UUID id, S owner, Object connection, long now, long initialDelay) {
        if (closed || id == null || owner == null || connection == null) return false;
        Entry<S> existing = entries.get(id);
        if (existing != null && existing.owner == owner && existing.connection == connection) return true;
        if (existing == null && entries.size() >= CAPACITY) return false;
        entries.put(id, new Entry<>(id, owner, connection, now + initialDelay));
        return true;
    }
    synchronized Entry<S> next() {
        if (closed || entries.isEmpty()) return null;
        Iterator<Entry<S>> iterator = entries.values().iterator();
        Entry<S> entry = iterator.next();iterator.remove();entries.put(entry.id,entry);
        return entry;
    }
    synchronized boolean claim(Entry<S> entry, long now, long interval) {
        if (closed || entries.get(entry.id) != entry || now - entry.due < 0) return false;
        entry.due = now + interval;
        return true;
    }
    synchronized boolean due(Entry<S> entry, long now) {
        return !closed && entries.get(entry.id)==entry && now-entry.due>=0;
    }
    synchronized void remove(UUID id, S owner) {
        Entry<S> entry = entries.get(id);
        if (entry != null && entry.owner == owner) entries.remove(id);
    }
    synchronized void reset(long now, long initialDelay) {
        if (!closed) for (Entry<S> entry : entries.values()) entry.due = now + initialDelay;
    }
    synchronized int size() { return entries.size(); }
    synchronized void close() { closed=true;entries.clear(); }
}

