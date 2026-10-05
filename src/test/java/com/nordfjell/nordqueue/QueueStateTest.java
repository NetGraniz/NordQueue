package com.nordfjell.nordqueue;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class QueueStateTest {
    private static int passed;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static QueueState.Session<Object> add(QueueState<Object> state, boolean priority, long time) {
        var session = state.begin(UUID.randomUUID(), new Object());
        state.enqueue(session, priority, time);
        return session;
    }
    private static QueueState.Attempt<Object> select(QueueState<Object> state, long now) {
        return state.select(() -> 0, 100, now, 0, 0, "main", player -> true);
    }
    private static void test(String name, Runnable test) {
        test.run(); passed++; System.out.println("PASS: " + name);
    }
    public static void main(String[] args) throws Exception {
        test("temporarily ineligible priority head does not block eligible regular players", () -> {
            var state = new QueueState<Object>();
            var regular = add(state, false, 0);
            var priority = add(state, true, 900);
            var attempt = state.select(() -> 0, 100, 1000, 500, 0, "main", p -> true);
            check(attempt.session == regular, "waiting priority must be skipped");
            state.finish(attempt, true, 1000, 0);
            check(state.snapshot().isPriority(priority.id), "skipped priority keeps its position");
        });
        test("failure backoff preserves FIFO position but allows the next eligible player", () -> {
            var state = new QueueState<Object>();
            var first = add(state, false, 0);
            var second = add(state, false, 0);
            var failed = select(state, 0);
            state.finish(failed, false, 0, 5000);
            check(state.snapshot().position(first.id) == 1, "failed head must retain position");
            check(select(state, 1000).session == second, "retry must not block second player");
        });
        test("player not on limbo is skipped without losing its place", () -> {
            var state = new QueueState<Object>();
            var first = add(state, false, 0);
            var second = add(state, false, 0);
            check(state.select(() -> 0, 1, 0, 0, 0, "main", p -> p != first.owner).session == second,
                "unavailable head must not block");
            check(state.snapshot().position(first.id) == 1, "position retained");
        });
        test("priority FIFO and per-group positions remain compatible", () -> {
            var state = new QueueState<Object>();
            var regular = add(state, false, 0);
            var first = add(state, true, 0);
            var second = add(state, true, 0);
            var snapshot = state.snapshot();
            check(snapshot.orderedIds().equals(java.util.List.of(first.id, second.id, regular.id)), "order");
            check(snapshot.position(regular.id) == 1 && snapshot.absolutePosition(regular.id) == 3, "positions");
            check(select(state, 0).session == first, "priority first");
        });
        test("old success/failure/disconnect cannot mutate a replacement session or reservation", () -> {
            var state = new QueueState<Object>();
            var old = add(state, false, 0);
            var oldAttempt = select(state, 0);
            var replacement = state.begin(old.id, new Object());
            state.enqueue(replacement, false, 1);
            var newAttempt = select(state, 1);
            check(!state.finish(oldAttempt, true, 1, 5), "ignore old success");
            check(!state.finish(oldAttempt, false, 1, 5), "ignore old error");
            check(!state.end(old.id, old.owner), "ignore old disconnect");
            check(state.pending() == newAttempt && state.size() == 1, "new reservation preserved");
        });
        test("same-session requeue is not removed by an old successful callback", () -> {
            var state = new QueueState<Object>();
            var player = add(state, false, 0);
            var old = select(state, 0);
            state.arrived(player);
            state.enqueue(player, false, 1);
            check(!state.finish(old, true, 1, 0) && state.size() == 1, "requeue must survive");
        });
        test("reservation excludes parallel transfers and cannot exceed capacity", () -> {
            var state = new QueueState<Object>();
            add(state, false, 0); add(state, false, 0);
            check(state.select(() -> 100, 100, 0, 0, 0, "main", p -> true) == null, "full");
            var attempt = state.select(() -> 99, 100, 0, 0, 0, "main", p -> true);
            check(attempt != null && select(state, 0) == null, "one in flight");
            state.arrived(attempt.session);
            check(state.select(() -> 100, 100, 1, 0, 0, "main", p -> true) == null, "arrival counts before next selection");
        });
        test("admission is single-use and belongs to the exact selected connection and target", () -> {
            var state = new QueueState<Object>();
            var first = add(state, false, 0); var second = add(state, false, 0);
            select(state, 0);
            check(!state.consumeAdmission(second, "main"), "no bypass");
            check(!state.consumeAdmission(first, "other"), "wrong target");
            check(state.consumeAdmission(first, "MAIN"), "selected player admitted");
            check(!state.consumeAdmission(first, "main"), "no parallel admission");
        });
        test("timeout invalidates session and late callbacks cannot release the next reservation", () -> {
            var state = new QueueState<Object>();
            var old = add(state, false, 0); var next = add(state, false, 0);
            var timedOut = select(state, 0);
            check(state.expire(timedOut), "expire");
            var current = select(state, 1);
            check(current.session == next && !state.current(old), "next is available");
            check(!state.finish(timedOut, true, 1, 0) && state.pending() == current, "late callback ignored");
        });
        test("immutable snapshots are cached and never expose partially changed state", () -> {
            var state = new QueueState<Object>();
            var first = add(state, false, 0);
            var old = state.snapshot();
            check(old == state.snapshot(), "cached");
            add(state, true, 0);
            check(old.size() == 1 && state.snapshot().size() == 2, "old view unchanged");
            try { old.orderedIds().clear(); throw new AssertionError("mutable list"); }
            catch (UnsupportedOperationException expected) { }
            check(old.position(first.id) == 1, "old positions unchanged");
        });
        test("disconnect/pruning removes all metadata and shutdown rejects new work", () -> {
            var state = new QueueState<Object>();
            var first = add(state, true, 0);
            select(state, 0);
            state.markBanNotice(first);
            state.prune(p -> false);
            check(state.size() == 0 && state.sessionCount() == 0 && state.pending() == null, "prune everything");
            state.close();
            check(state.begin(UUID.randomUUID(), new Object()) == null && select(state, 0) == null, "closed");
        });
        test("authentication failures are terminal, ordinary maintenance kicks are not", () -> {
            check(AuthenticationDisconnects.terminal("Login timed out."), "NordAuth timeout");
            check(AuthenticationDisconnects.terminal("Login timeout exceeded"), "AuthMe timeout");
            check(AuthenticationDisconnects.terminal("Authentication is temporarily unavailable."), "database failure");
            check(!AuthenticationDisconnects.terminal("Server restarting"), "maintenance");
        });
        test("rebalance retains waiting and retry metadata", () -> {
            var state = new QueueState<Object>();
            var first = add(state, false, 0); add(state, false, 0);
            state.finish(select(state, 0), false, 0, 5000);
            state.rebalance(p -> p == first.owner);
            check(state.snapshot().isPriority(first.id), "promoted");
            check(select(state, 1000).session != first, "backoff retained");
        });
        test("scheduler catch-up and reload cannot exceed the configured transfer rate", () -> {
            var limited = new QueueState<Object>();
            add(limited, false, 0); add(limited, false, 0);
            var attempt = limited.select(() -> 0, 100, 0, 0, 1000, "main", p -> true);
            limited.finish(attempt, true, 0, 0);
            limited.rebalance(p -> false);
            check(limited.select(() -> 0, 100, 999, 0, 1000, "main", p -> true) == null, "no burst");
            check(limited.select(() -> 0, 100, 1000, 0, 1000, "main", p -> true) != null, "interval elapsed");
        });
        var state = new QueueState<Object>();
        for (int i = 0; i < 1000; i++) add(state, i % 5 == 0, 0);
        var pool = Executors.newFixedThreadPool(4);
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(4);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        for (int i = 0; i < 4; i++) pool.execute(() -> {
            try {
                for (int n = 0; n < 5000; n++) {
                    var attempt = select(state, 0);
                    if (attempt != null && state.finish(attempt, true, 0, 0)) completed.incrementAndGet();
                    var snapshot = state.snapshot();
                    check(snapshot.size() == snapshot.orderedIds().size(), "consistent concurrent snapshot");
                }
            } catch (Throwable failure) {
                workerFailure.compareAndSet(null, failure);
            } finally { done.countDown(); }
        });
        check(done.await(30, TimeUnit.SECONDS), "workers finish");
        pool.shutdownNow();
        check(workerFailure.get() == null, "worker assertion failed: " + workerFailure.get());
        check(completed.get() == 1000 && state.size() == 0 && state.pending() == null, "exactly 1000 completed");
        passed++; System.out.println("PASS: 1000 synthetic entries with concurrent selection and snapshot readers");
        System.out.println("ALL " + passed + " QUEUE STATE SCENARIOS PASSED");
    }
}
