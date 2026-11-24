package com.nordfjell.nordqueue;

import java.util.concurrent.*;
import java.util.function.Consumer;

/** One reader, one in-flight request/result; no Velocity calls on the worker. */
final class ReloadReader<T> implements AutoCloseable {
    record Outcome<T>(T value, String error) {}
    private final ThreadPoolExecutor worker;
    private final ArrayBlockingQueue<Outcome<T>> result = new ArrayBlockingQueue<>(1);
    private boolean busy, closed;
    ReloadReader() {
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), task -> {
                    Thread thread = new Thread(task, "NordQueue-config-reader");
                    thread.setDaemon(true); return thread;
                });
    }
    synchronized boolean start(Callable<T> load) {
        if (closed || busy) return false;
        busy = true;
        try {
            worker.execute(() -> {
                Outcome<T> outcome;
                try { outcome = new Outcome<>(load.call(), null); }
                catch (Exception error) {
                    if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    outcome = new Outcome<>(null, error.getClass().getSimpleName());
                }
                synchronized (this) {if (!closed) result.offer(outcome);}
            });
        } catch (RejectedExecutionException closedExecutor) {busy=false;return false;}
        return true;
    }
    synchronized void drain(Consumer<Outcome<T>> apply) {
        Outcome<T> outcome = result.poll();
        if (outcome == null || closed) return;
        try {apply.accept(outcome);} finally {busy=false;}
    }
    synchronized boolean busy() {return busy;}
    @Override public void close() {
        synchronized (this) {closed=true;busy=false;result.clear();}
        worker.shutdownNow();
        try {worker.awaitTermination(2, TimeUnit.SECONDS);}
        catch (InterruptedException interrupted) {Thread.currentThread().interrupt();}
    }
}

