package com.nordfjell.nordqueue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.IntSupplier;

/** All queue/session/reservation mutations share one monitor; never does disk/network I/O. */
final class QueueState<T> {
    static final class Session<T> {
        final UUID id;
        final T owner;
        boolean banNoticeShown;
        Session(UUID id, T owner) { this.id = id; this.owner = owner; }
    }

    private static final class Entry<T> {
        final Session<T> session;
        final long queuedAt;
        long retryAfter = Long.MIN_VALUE;
        boolean priority;
        Entry(Session<T> session, boolean priority, long now) {
            this.session = session;
            this.priority = priority;
            this.queuedAt = now;
        }
    }

    static final class Attempt<T> {
        final Session<T> session;
        final String target;
        private final Entry<T> entry;
        private boolean admissionConsumed;
        Attempt(Entry<T> entry, String target) {
            this.entry = entry;
            this.session = entry.session;
            this.target = target;
        }
    }

    private final Map<UUID, Session<T>> sessions = new HashMap<>();
    private final Map<UUID, Entry<T>> entries = new HashMap<>();
    private final LinkedHashMap<UUID, Entry<T>> priority = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Entry<T>> regular = new LinkedHashMap<>();
    private Attempt<T> pending;
    private boolean closed;
    private long nextTransferAt = Long.MIN_VALUE;
    private long version;
    private QueueSnapshot cached;

    synchronized Session<T> begin(UUID id, T owner) {
        if (closed) return null;
        Session<T> old = sessions.get(id);
        if (old != null) end(id, old.owner);
        Session<T> session = new Session<>(id, owner);
        sessions.put(id, session);
        return session;
    }

    synchronized Session<T> find(UUID id, T owner) {
        Session<T> session = sessions.get(id);
        return session != null && session.owner == owner ? session : null;
    }

    synchronized boolean current(Session<T> session) {
        return !closed && session != null && sessions.get(session.id) == session;
    }

    synchronized boolean end(UUID id, T owner) {
        Session<T> session = find(id, owner);
        if (session == null) return false;
        removeQueued(session);
        if (pending != null && pending.session == session) pending = null;
        sessions.remove(id);
        return true;
    }

    synchronized void enqueue(Session<T> session, boolean isPriority, long now) {
        if (!current(session) || entries.containsKey(session.id)) return;
        Entry<T> entry = new Entry<>(session, isPriority, now);
        entries.put(session.id, entry);
        (isPriority ? priority : regular).put(session.id, entry);
        changed();
    }

    synchronized void removeQueued(Session<T> session) {
        if (!current(session)) return;
        Entry<T> removed = entries.remove(session.id);
        if (removed == null) return;
        priority.remove(session.id);
        regular.remove(session.id);
        changed();
    }

    synchronized boolean contains(UUID id) { return entries.containsKey(id); }
    synchronized boolean priority(UUID id) { return priority.containsKey(id); }
    synchronized int size() { return entries.size(); }
    synchronized int regularSize() { return regular.size(); }
    synchronized int prioritySize() { return priority.size(); }
    synchronized int sessionCount() { return sessions.size(); }
    synchronized Attempt<T> pending() { return pending; }

    synchronized void rebalance(Predicate<T> isPriority) {
        List<Entry<T>> ordered = new ArrayList<>(priority.values());
        ordered.addAll(regular.values());
        priority.clear();
        regular.clear();
        for (Entry<T> entry : ordered) {
            entry.priority = isPriority.test(entry.session.owner);
            (entry.priority ? priority : regular).put(entry.session.id, entry);
        }
        changed();
    }

    synchronized void prune(Predicate<T> isActive) {
        List<Session<T>> stale = sessions.values().stream()
            .filter(session -> !isActive.test(session.owner)).toList();
        for (Session<T> session : stale) end(session.id, session.owner);
    }

    synchronized Attempt<T> select(IntSupplier connected, int capacity, long now, long minimumWait, long interval,
                                   String target, Predicate<T> eligible) {
        if (closed || pending != null || now < nextTransferAt || connected.getAsInt() >= capacity) return null;
        Entry<T> chosen = candidate(priority, now, minimumWait, eligible);
        if (chosen == null) chosen = candidate(regular, now, minimumWait, eligible);
        if (chosen == null) return null;
        pending = new Attempt<>(chosen, target);
        nextTransferAt = now + interval;
        return pending;
    }

    private Entry<T> candidate(LinkedHashMap<UUID, Entry<T>> group, long now, long wait,
                               Predicate<T> eligible) {
        for (Entry<T> entry : group.values()) {
            if (now - entry.queuedAt >= wait && now >= entry.retryAfter
                    && current(entry.session) && eligible.test(entry.session.owner)) return entry;
        }
        return null;
    }

    synchronized boolean consumeAdmission(Session<T> session, String target) {
        if (!current(session) || pending == null || pending.session != session
                || !pending.target.equalsIgnoreCase(target) || pending.admissionConsumed
                || entries.get(session.id) != pending.entry) return false;
        pending.admissionConsumed = true;
        return true;
    }

    synchronized boolean admissionConsumed(Attempt<T> attempt) {
        return pending == attempt && attempt.admissionConsumed;
    }

    synchronized boolean relevant(Attempt<T> attempt) {
        return pending == attempt && current(attempt.session)
            && entries.get(attempt.session.id) == attempt.entry;
    }

    synchronized boolean finish(Attempt<T> attempt, boolean success, long now, long retryDelay) {
        if (pending != attempt) return false;
        if (current(attempt.session) && entries.get(attempt.session.id) == attempt.entry) {
            if (success) removeQueued(attempt.session);
            else attempt.entry.retryAfter = now + retryDelay;
        }
        // Release only after the mutation; a stale callback cannot release another attempt.
        pending = null;
        return true;
    }

    synchronized Attempt<T> arrived(Session<T> session) {
        if (!current(session)) return null;
        removeQueued(session);
        if (pending == null || pending.session != session) return null;
        Attempt<T> completed = pending;
        pending = null;
        return completed;
    }

    synchronized boolean expire(Attempt<T> attempt) {
        if (pending != attempt) return false;
        // Invalidate the entire connection before disconnecting its network transport.
        end(attempt.session.id, attempt.session.owner);
        pending = null;
        return true;
    }

    synchronized boolean markBanNotice(Session<T> session) {
        if (!current(session) || session.banNoticeShown) return false;
        session.banNoticeShown = true;
        return true;
    }

    synchronized void clearBanNotice(Session<T> session) {
        if (current(session)) session.banNoticeShown = false;
    }

    synchronized boolean hasBanNotice(Session<T> session) {
        return current(session) && session.banNoticeShown;
    }

    synchronized QueueSnapshot snapshot() {
        if (cached != null) return cached;
        List<UUID> ids = new ArrayList<>(entries.size());
        Map<UUID, Integer> positions = new HashMap<>();
        Map<UUID, Integer> absolute = new HashMap<>();
        int index = 0;
        for (UUID id : priority.keySet()) {
            positions.put(id, ++index);
            ids.add(id);
            absolute.put(id, ids.size());
        }
        index = 0;
        for (UUID id : regular.keySet()) {
            positions.put(id, ++index);
            ids.add(id);
            absolute.put(id, ids.size());
        }
        cached = new QueueSnapshot(version, ids, positions, absolute,
            new HashSet<>(priority.keySet()), regular.size(), priority.size());
        return cached;
    }

    synchronized void close() {
        closed = true;
        sessions.clear();
        entries.clear();
        priority.clear();
        regular.clear();
        pending = null;
        changed();
    }

    private void changed() { version++; cached = null; }
}
