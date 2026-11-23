package com.nordfjell.nordqueue;

/** 128 attempts/sec with a maximum 32-frame burst; monotonic, no catch-up flood. */
final class DispatchBudget {
    static final long CAPACITY_NANOS = 250_000_000L, COST_NANOS = CAPACITY_NANOS / 32;
    private long credit = CAPACITY_NANOS, last;
    private boolean initialized;
    private void refill(long now) {
        if (!initialized) {initialized=true;last=now;return;}
        long elapsed = now-last;
        if (elapsed > 0) {
            credit = elapsed>=CAPACITY_NANOS?CAPACITY_NANOS:Math.min(CAPACITY_NANOS,credit+elapsed);
            last=now;
        }
    }
    synchronized boolean available(long now) {refill(now);return credit>=COST_NANOS;}
    synchronized boolean take(long now) {
        refill(now);if(credit<COST_NANOS)return false;credit-=COST_NANOS;return true;
    }
}

